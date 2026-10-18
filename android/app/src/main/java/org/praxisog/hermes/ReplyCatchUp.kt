package org.praxisog.hermes

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*

internal data class ReplyCursor(val activity: Double, val count: Int, val replyId: Int)
internal interface ReplyCursorStore {
    fun readCursor(id: String): ReplyCursor?
    fun writeCursor(id: String, value: ReplyCursor)
}

/** Incremental canonical-history catch-up, including work done by another client. */
internal class ReplyCatchUp(
    private val scope: CoroutineScope,
    private val client: HermesClient,
    private val cursors: ReplyCursorStore,
    private val receipts: ConversationReadState,
    private val onReply: (String, Message) -> Unit,
) {
    private val lock = Mutex()
    var archive: suspend (String, Message) -> Boolean = { _, _ -> true }
    private var job: Job? = null
    fun sync(sessions: List<Session>): Job {
        job?.takeIf { it.isActive }?.let { return it }
        return scope.launch {
        lock.withLock {
            for (session in sessions) {
                ensureActive()
                val old = withContext(Dispatchers.IO) { cursors.readCursor(session.id) }
                val activity = session.lastActive ?: session.startedAt ?: 0.0
                if (old?.activity == activity && old.count == session.messageCount) continue
                val result = withContext(Dispatchers.IO) { client.messages(session.id) }
                if (result !is HermesResult.Ok) continue // keep old cursor so reconnect retries
                val latest = result.value.data.lastOrNull { it.role == "assistant" && !it.content.isNullOrBlank() }
                // First sight is a baseline, not a pile of unread imported chats. Existing
                // receipt positions can establish that baseline after the feature upgrade.
                val receipt = receipts.snapshot()[session.id]
                val previous = old?.replyId ?: receipt?.let { maxOf(it.readThrough, it.unreadReply) }
                val received = if (previous == null) listOfNotNull(latest) else result.value.data.filter {
                    it.role == "assistant" && !it.content.isNullOrBlank() && it.id > previous
                }.takeLast(200)
                // Save bodies before advancing the cursor; a failed write must be retried.
                if (received.any { !archive(session.id, it) }) continue
                if (latest != null && previous != null && latest.id > previous) onReply(session.id, latest)
                withContext(Dispatchers.IO) { cursors.writeCursor(session.id, ReplyCursor(activity, session.messageCount, latest?.id ?: 0)) }
            }
        }
        }.also { job = it }
    }
}
