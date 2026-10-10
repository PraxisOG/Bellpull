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

    fun connect(client: HermesClient, capabilities: HermesClient.Capabilities): HermesStore {
        store?.let { return it }
        this.client = client
        notificationEventsAvailable = capabilities.notificationEvents
        val receipts = ConversationReadState(cache?.readReceipts().orEmpty())
        val next = HermesStore(scope, client, cache = cache, drafts = drafts, readState = receipts,
            supportsMessageBranching = capabilities.sessionForkAtMessage,
            supportsPromptEditing = capabilities.sessionForkBeforeMessage,
            durableRetention = capabilities.durableRunRetention, journal = cache)
        receipts.onChange = { id, receipt -> if (store === next) {
            cache?.writeReceipt(id, receipt)
            if (receipt.readThrough >= receipt.unreadReply) notifications?.read(id)
        } }
        store = next
        next.runs?.interactiveApprovals = capabilities.interactiveApprovals
        notifications = ReplyNotifications(context, scope, cache, next, profileId.orEmpty()) { it == visibleSessionId }
        next.onReply = { id, message -> notifications?.reply(id, message.id) }
        next.onAttention = { id, identity -> notifications?.attention(id, identity) }
        cache?.let {
            val catchUp = ReplyCatchUp(scope, client, it, receipts, next::discoveredReply)
            next.onSessions = { sessions -> catchUp.sync(sessions.filter { session -> next.runs?.states?.get(session.id)?.busy != true }) }
        }
        cache?.writeCapabilities(capabilities.raw.toString())
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
            store?.runs?.interactiveApprovals = result.value.interactiveApprovals
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
        connect(HermesClient(connection.profile.url, { connection.key }, memoryScopeKey = "hermes-android"), HermesClient.Capabilities(obj, flags))
    }

    fun disconnect() {
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
