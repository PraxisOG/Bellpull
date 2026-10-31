package org.praxisog.hermes

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.praxisog.hermes.core.HermesClient

/** Application-owned state. Destroying an Activity only detaches its UI. */
internal class BellpullRuntime private constructor(context: Context) {
    private val context = context.applicationContext
    private val fallbackLifecycle = LocalFallbackLifecycle(this.context as android.app.Application) {
        offlineWorkspace?.background()
    }
    fun speechConnection(): ServerConnection = checkNotNull(profileId?.let { SecureConnectionStore(context).connection(it) }) {
        "Select a server profile before using server speech."
    }
    val voiceOutputs by lazy { VoiceOutputLab(this.context, serverConnection = { speechConnection() }) }
    fun voiceProblem(config: VoiceConfiguration) = config.startProblem { speechLab.voiceStartProblem() } ?: voiceOutputs.startProblem(config)
    val speechLab by lazy { SpeechLab(this.context) }
    var voice by mutableStateOf<VoiceSession?>(null)
    var voiceStartError by mutableStateOf<String?>(null)
    var voiceRequestedSession by mutableStateOf<String?>(null)
    var voiceRequestedMode = VoiceMode.CALL
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
    var offlineWorkspace by mutableStateOf<OfflineWorkspace?>(null)
        private set
    var phoneContext by mutableStateOf<PhoneContextSync?>(null)
        private set
    val localSettings = LocalFallbackSettings(this.context)
    val localModel by lazy { LocalLlmLab.get(this.context) }
    var fallbackOffline by mutableStateOf(false)
        private set
    var fallbackProblem by mutableStateOf<String?>(null)
        private set
    suspend fun monitorConnection() {
        while (currentCoroutineContext().isActive) {
            val api = client
            if (api != null) {
                val result = withContext(Dispatchers.IO) { api.checkConnection() }
                currentCoroutineContext().ensureActive()
                if (api === client) {
                    val wasOffline = fallbackOffline
                    fallbackOffline = result !is org.praxisog.hermes.model.HermesResult.Ok
                    fallbackProblem = (result as? org.praxisog.hermes.model.HermesResult.Err)?.error?.toUserMessage()
                    if (wasOffline && !fallbackOffline) { store?.loadSessions(); store?.refreshHistory(); syncOfflineMemory() }
                }
            }
            delay(12000)
        }
    }
    private val localLoadCooldown = ModelLoadCooldown()
    suspend fun warmLocal(automatic: Boolean = false) {
        // A healthy connection returning cancels this delay before any native work.
        if (automatic) {
            delay(10_000)
            // The periodic monitor may not have observed a quick recovery yet.
            // Confirm the outage before spending CPU and memory on a native load.
            val api = client ?: return
            val connection = withContext(Dispatchers.IO) { api.checkConnection() }
            currentCoroutineContext().ensureActive()
            if (api !== client) return
            if (connection is org.praxisog.hermes.model.HermesResult.Ok) {
                fallbackOffline = false; fallbackProblem = null
                store?.loadSessions(); store?.refreshHistory()
                return
            }
        }
        val lab = localModel
        if (!lab.state.value.scanned || lab.state.value.busy) return
        val state = lab.state.value
        if (state.ready && !state.gpu && state.contextTokens == localSettings.value.contextTokens &&
            state.models.any { it.id == state.selected && it.sha256 == LocalLlmLab.E2B_SHA }) return
        if (!localLoadCooldown.begin(explicit = !automatic)) return
        val task = lab.prepareOffline(localSettings.value.contextTokens)
        try { task.await() } catch (e: CancellationException) {
            // Connectivity changes do not cancel a native load already in flight.
            // The foreground/background lifecycle still owns release and cancellation.
            throw e
        } catch (_: Exception) { /* The lab exposes the actionable loading error. */ }
    }
    private var memorySync: Job? = null
    private var memorySyncedAt = 0L
    private fun syncOfflineMemory() {
        val id = profileId ?: return
        val api = client ?: return
        val saved = cache ?: return
        if (capabilities?.features?.get("bellpull_offline") != true || memorySync?.isActive == true ||
            System.currentTimeMillis() - memorySyncedAt < 60000) return
        memorySync = scope.launch(Dispatchers.IO) {
            when (val result = api.offlineMemory()) {
                is org.praxisog.hermes.model.HermesResult.Ok -> if (profileId == id && client === api) {
                    saved.writeOfflineMemory(result.value); memorySyncedAt = System.currentTimeMillis()
                }
                else -> Unit
            }
        }
    }
    var phase3 by mutableStateOf<Phase3Workspace?>(null)
        private set
    var projects by mutableStateOf<ProjectWorkspace?>(null)
        private set
    var coding by mutableStateOf<CodingActivityWorkspace?>(null)
        private set
    var delivery by mutableStateOf<DeliveryWorkspace?>(null)
        private set
    var planning by mutableStateOf<PlanningWorkspace?>(null)
        private set
    var canvas by mutableStateOf<CanvasWorkspace?>(null)
        private set
    var capabilities by mutableStateOf<HermesClient.Capabilities?>(null)
        private set
    var notificationEventsAvailable by mutableStateOf(false)
        private set
    val replyNotificationsAvailable get() = store != null && (notificationEventsAvailable || delivery?.supported == true)
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
        phoneContext = PhoneContextSync(context, id, cache!!)
        offlineWorkspace = OfflineWorkspace(context, id, phoneContext = { phoneContext?.localContext().orEmpty() }) { client.takeIf { profileId == id } }
        loadDrafts()
    }

    fun connect(client: HermesClient, capabilities: HermesClient.Capabilities, verifiedDiscovery: Boolean = true): HermesStore {
        store?.let { return it }
        this.client = client
        this.capabilities = capabilities
        syncOfflineMemory()
        client.configureCapabilities(capabilities)
        phoneContext?.configure(client, capabilities.features["bellpull_phone_context"] == true)
        notificationEventsAvailable = capabilities.notificationEvents
        val receipts = ConversationReadState(cache?.readReceipts().orEmpty())
        val next = HermesStore(scope, client, cache = cache, drafts = drafts, readState = receipts,
            sessionSource = cache?.sessionSource(),
            supportsMessageBranching = capabilities.sessionForkAtMessage,
            supportsPromptEditing = capabilities.sessionForkBeforeMessage,
            durableRetention = capabilities.durableRunRetention, journal = cache,
            supportsVoiceTitles = capabilities.features["bellpull_voice_titles"] == true,
            backgroundFolders = capabilities.features["bellpull_background_folder"] == true)
        receipts.onChange = { id, receipt -> if (store === next) {
            cache?.writeReceipt(id, receipt)
            if (receipt.readThrough >= receipt.unreadReply) notifications?.read(id)
        } }
        val contextProfile = profileId.orEmpty()
        next.phoneNotificationContext = { query ->
            if (store === next && profileId == contextProfile &&
                !(phoneContext?.supported == true && contextProfile in PhoneNotificationRepository.get(context).state.value.policy.serverSharingProfiles))
                PhoneNotificationRepository.get(context).contextFor(contextProfile, query) else null
        }
        store = next
        canvas = cache?.let { CanvasWorkspace(scope, client, it, capabilities.features["bellpull_canvas"] == true) }
        coding = CodingActivityWorkspace(scope, client, cache?.let { saved -> object : CodingCache {
            override fun readCoding(id: String) = saved.readCoding(id)
            override fun writeCoding(value: org.praxisog.hermes.model.CodingSnapshot) = saved.writeCoding(value)
        } }, capabilities.features["bellpull_coding_activity"] == true)
        delivery = cache?.let { DeliveryWorkspace(context, profileId.orEmpty(), scope, it, client,
            capabilities.features["bellpull_planning"] == true) { snapshot -> planning?.acceptSnapshot(snapshot) } }
        planning = cache?.let { PlanningWorkspace(scope,client,it,context,capabilities.features["bellpull_planning"]==true, delivery) }
        scope.launch { snapshotFlow { planning?.policy }.collect { phoneContext?.request() } }
        projects = cache?.let { ProjectWorkspace(scope, client, it, capabilities.features["bellpull_projects"] == true,
            capabilities.features["bellpull_conversation_pins"] == true, capabilities.features["bellpull_background_folder"] == true) }
        projects?.let { workspace -> next.newSessionRequest = workspace::creationRequest; workspace.refresh() }
        phase3 = cache?.let { profileCache -> Phase3Workspace(context, profileId.orEmpty(), scope, client, profileCache,
            capabilities.features["bellpull_persistent_artifacts"] == true,
            capabilities.features["bellpull_model_controls"] == true || capabilities.features["bellpull_native_model_controls"] == true,
            capabilities.features["bellpull_artifact_lifecycle"] == true,
            capabilities.features["bellpull_run_attachments"] ?: (capabilities.features["bellpull_persistent_artifacts"] == true && capabilities.features["bellpull_model_controls"] == true)) }
        next.defaultModel = { phase3?.defaultSelection }
        next.runs?.onAccepted = { phase3?.accepted(it) }
        next.runs?.interactiveApprovals = capabilities.interactiveApprovals
        notifications = ReplyNotifications(context, scope, cache, next, profileId.orEmpty()) { it == visibleSessionId || voice?.sessionId == it }
        next.onReply = { id, message ->
            syncOfflineMemory()
            delivery?.receive(org.praxisog.hermes.model.InboxEntry("reply:$id:${message.id}", "reply",
                "Reply", message.content.orEmpty(), System.currentTimeMillis(), id))
            notifications?.reply(id, message.id); phase3?.refreshArtifacts()
        }
        next.onAttention = { id, identity ->
            delivery?.receive(org.praxisog.hermes.model.InboxEntry(identity, "attention", "Needs attention",
                if (identity.startsWith("approval:")) "Open the conversation to review the current approval request." else "Open the conversation to review the current run status.",
                System.currentTimeMillis(), id))
            notifications?.attention(id, identity)
        }
        cache?.let {
            val catchUp = ReplyCatchUp(scope, client, it, receipts, next::discoveredReply)
            val profileDelivery = delivery
            catchUp.archive = { id, message -> profileDelivery?.archiveReply(id, message) ?: false }
            next.onSessions = { sessions ->
                syncOfflineMemory()
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
            phoneContext?.configure(current, result.value.features["bellpull_phone_context"] == true)
            syncOfflineMemory()
            coding?.configure(result.value.features["bellpull_coding_activity"] == true)
            planning?.supported = result.value.features["bellpull_planning"]==true
            canvas?.supported = result.value.features["bellpull_canvas"]==true
            delivery?.supported = result.value.features["bellpull_planning"]==true
            projects?.configure(result.value.features["bellpull_projects"] == true, result.value.features["bellpull_conversation_pins"] == true, result.value.features["bellpull_background_folder"] == true)
            store?.backgroundFolders = result.value.features["bellpull_background_folder"] == true
            val fileSupport = result.value.features["bellpull_persistent_artifacts"] == true
            val modelSupport = result.value.features["bellpull_model_controls"] == true || result.value.features["bellpull_native_model_controls"] == true
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
        delivery?.tick()
        val current = store ?: return
        current.runs?.observe(runId, sessionId, current.readState.snapshot()[sessionId]?.readThrough ?: 0)
        current.loadSessions()
    }

    fun catchUpNotifications() {
        delivery?.tick()
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
        offlineWorkspace?.close(); offlineWorkspace = null
        fallbackOffline = false; fallbackProblem = null
        memorySync?.cancel(); memorySync = null; memorySyncedAt = 0
        delivery?.close(); delivery = null
        coding?.close(); coding = null
        voice?.close()
        voice = null
        voiceRequestedSession = null
        voiceStartError = null
        context.stopService(android.content.Intent(context, VoiceService::class.java))
        projects?.close()
        projects = null
        phoneContext?.close(); phoneContext = null
        planning?.close()
        canvas?.close(); canvas = null
        planning = null
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
