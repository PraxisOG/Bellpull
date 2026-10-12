package org.praxisog.hermes

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.praxisog.hermes.core.HermesClient

/** Application-owned state. Destroying an Activity only detaches its UI. */
internal class BellpullRuntime private constructor(context: Context) {
    private val context = context.applicationContext
    val speechLab by lazy { SpeechLab(this.context) }
    var voice by mutableStateOf<VoiceSession?>(null)
    var voiceStartError by mutableStateOf<String?>(null)
    var voiceRequestedSession by mutableStateOf<String?>(null)
    var voiceRequestedMode = VoiceMode.CONVERSATION
    var voiceButtonHeld = false
    var voiceDictationToDraft = false
    var cache: SessionCache? = null
        private set
    var drafts = ChatDrafts()
        private set
    var profileId by mutableStateOf<String?>(null)
        private set
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var store by mutableStateOf<HermesStore?>(null)
        private set
    var client: HermesClient? = null
        private set
    var phase3 by mutableStateOf<Phase3Workspace?>(null)
        private set
    var projects by mutableStateOf<ProjectWorkspace?>(null)
        private set
    var capabilities by mutableStateOf<HermesClient.Capabilities?>(null)
        private set
    var notificationEventsAvailable by mutableStateOf(false)
        private set
    private var notifications: ReplyNotifications? = null
    var visibleSessionId: String? = null
    private fun loadDrafts() {
        val profileCache = cache
        drafts = ChatDrafts()
        val values = cache?.readDrafts().orEmpty()
        val versions = cache?.readDraftRevisions().orEmpty()
        (values.keys + versions.keys).forEach { id ->
            drafts.restore(id, values[id] ?: androidx.compose.ui.text.input.TextFieldValue(), versions[id] ?: 1L)
        }
        val profileDrafts = drafts
        drafts.onChange = { id, value -> profileCache?.writeDraft(id, value, profileDrafts.revision(id)) }
    }

    fun prepare(id: String) {
        if (profileId == id) return
        disconnect()
        cache = SessionCache(context, id)
        profileId = id
        loadDrafts()
    }

    fun connect(client: HermesClient, capabilities: HermesClient.Capabilities, verifiedDiscovery: Boolean = true): HermesStore {
        store?.let { return it }
        this.client = client
        this.capabilities = capabilities
        client.configureCapabilities(capabilities)
        notificationEventsAvailable = capabilities.notificationEvents
        val receipts = ConversationReadState(cache?.readReceipts().orEmpty())
        val next = HermesStore(scope, client, cache = cache, drafts = drafts, readState = receipts,
            sessionSource = cache?.sessionSource(),
            supportsMessageBranching = capabilities.sessionForkAtMessage,
            supportsPromptEditing = capabilities.sessionForkBeforeMessage,
            durableRetention = capabilities.durableRunRetention, journal = cache)
        receipts.onChange = { id, receipt -> if (store === next) {
            cache?.writeReceipt(id, receipt)
            if (receipt.readThrough >= receipt.unreadReply) notifications?.read(id)
        } }
        store = next
        projects = cache?.let { ProjectWorkspace(scope, client, it, capabilities.features["bellpull_projects"] == true) }
        projects?.let { workspace -> next.newSessionRequest = workspace::creationRequest; workspace.refresh() }
        phase3 = cache?.let { profileCache -> Phase3Workspace(context, profileId.orEmpty(), scope, client, profileCache,
            capabilities.features["bellpull_persistent_artifacts"] == true,
            capabilities.features["bellpull_model_controls"] == true,
            capabilities.features["bellpull_artifact_lifecycle"] == true,
            capabilities.features["bellpull_run_attachments"] ?: (capabilities.features["bellpull_persistent_artifacts"] == true && capabilities.features["bellpull_model_controls"] == true)) }
        next.defaultModel = { phase3?.defaultSelection }
        next.runs?.onAccepted = { phase3?.accepted(it) }
        next.runs?.interactiveApprovals = capabilities.interactiveApprovals
        notifications = ReplyNotifications(context, scope, cache, next, profileId.orEmpty()) { it == visibleSessionId }
        next.onReply = { id, message -> notifications?.reply(id, message.id); phase3?.refreshArtifacts() }
        next.onAttention = { id, identity -> notifications?.attention(id, identity) }
        cache?.let {
            val catchUp = ReplyCatchUp(scope, client, it, receipts, next::discoveredReply)
            next.onSessions = { sessions ->
                catchUp.sync(sessions.filter { session -> next.runs?.states?.get(session.id)?.busy != true })
                projects?.refresh()
            }
        }
        if (verifiedDiscovery) cache?.writeCapabilities(capabilities.raw.toString())
        next.startRecovery()
        next.loadSessions()
        cache?.lastSession()?.let(next::openSession)
        refreshCapabilities()
        return next
    }

    fun refreshCapabilities() {
        val current = client ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) { current.capabilities() }
            if (client !== current || result !is org.praxisog.hermes.model.HermesResult.Ok) return@launch
            notificationEventsAvailable = result.value.notificationEvents
            capabilities = result.value
            projects?.configure(result.value.features["bellpull_projects"] == true)
            val fileSupport = result.value.features["bellpull_persistent_artifacts"] == true
            val modelSupport = result.value.features["bellpull_model_controls"] == true
            val lifecycleSupport = result.value.features["bellpull_artifact_lifecycle"] == true
            val inputSupport = result.value.features["bellpull_run_attachments"] ?: (fileSupport && modelSupport)
            if (phase3?.filesSupported != fileSupport || phase3?.modelsSupported != modelSupport || phase3?.lifecycleSupported != lifecycleSupport || phase3?.runAttachmentsSupported != inputSupport) {
                phase3?.close()
                phase3 = cache?.let { Phase3Workspace(context, profileId.orEmpty(), scope, current, it, fileSupport, modelSupport, lifecycleSupport, inputSupport) }
            }
            store?.updateCapabilities(result.value)
            cache?.writeCapabilities(result.value.raw.toString())
        }
    }

    fun wake(runId: String, sessionId: String) {
        val current = store ?: return
        current.runs?.observe(runId, sessionId, current.readState.snapshot()[sessionId]?.readThrough ?: 0)
        current.loadSessions()
    }

    fun catchUpNotifications() {
        val current = store ?: return
        current.readState.snapshot().forEach { (id, receipt) ->
            if (receipt.unreadReply > receipt.readThrough) notifications?.reply(id, receipt.unreadReply)
        }
    }

    /** A verified cached contract lets the chat shell open even on a cold offline launch. */
    fun restore() {
        if (store != null) return
        val secure = runCatching { SecureConnectionStore(context) }.getOrNull() ?: return
        val connection = secure.active() ?: return
        prepare(connection.profile.id)
        restore(connection)
    }

    fun restore(connection: ServerConnection) {
        if (store != null) return
        prepare(connection.profile.id)
        val raw = cache?.readCapabilities() ?: return
        val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val flags = obj["features"]?.jsonObject?.mapValues { runCatching { it.value.jsonPrimitive.booleanOrNull == true }.getOrDefault(false) }.orEmpty()
        connect(HermesClient(connection.profile.url, { connection.key }, memoryScopeKey = "hermes-android"),
            HermesClient.Capabilities(obj, flags), verifiedDiscovery = false)
    }

    fun disconnect() {
        voice?.close()
        voice = null
        voiceRequestedSession = null
        voiceStartError = null
        context.stopService(android.content.Intent(context, VoiceService::class.java))
        projects?.close()
        projects = null
        capabilities = null
        phase3?.close()
        phase3 = null
        store?.close()
        client?.close()
        cache?.close()
        store = null
        client = null
        notifications = null
        notificationEventsAvailable = false
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        drafts.clear()
        drafts.onChange = { _, _ -> }
        cache = null
        profileId = null
        visibleSessionId = null
        context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
    }
    companion object {
        @Volatile private var instance: BellpullRuntime? = null
        fun get(context: Context): BellpullRuntime = instance ?: synchronized(this) {
            instance ?: BellpullRuntime(context).also { instance = it }
        }
    }
}
