package org.praxisog.hermes

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*

@Serializable
internal data class DelegationReceipt(
    val pending: Set<String> = emptySet(),
    val delivered: Set<String> = emptySet(),
)

internal interface DelegationJournal {
    fun readDelegations(): Map<String, DelegationReceipt>
    fun writeDelegations(sessionId: String, value: DelegationReceipt): Boolean
}

internal fun delegationDispatches(messages: List<Message>): Set<String> = messages.mapNotNull { message ->
    if (message.role != "tool") return@mapNotNull null
    val result = runCatching { Json.parseToJsonElement(message.content.orEmpty()) as? JsonObject }.getOrNull()
        ?: return@mapNotNull null
    fun field(name: String) = (result[name] as? JsonPrimitive)?.contentOrNull
    if (field("status") != "dispatched" || field("mode") != "background") return@mapNotNull null
    field("delegation_id")?.takeIf { it.isNotBlank() && it.length <= 200 }
}.toSet()

/** Tracks work independently of parent runs. All network requests here are reads.
 * The transcript remains authoritative; receipts only control polling and delivery.
 */
internal class BackgroundDelegations(
    private val scope: CoroutineScope,
    private val client: HermesClient,
    private val journal: DelegationJournal,
    private val blocked: (String) -> Boolean,
    private val onHistory: (String, List<Message>) -> Unit,
    private val onReply: (String, Message) -> Unit,
    private val pollMs: Long = 2_000,
) {
    val states = mutableStateMapOf<String, DelegationReceipt>()
    private val jobs = mutableMapOf<String, Job>()
    private var closed = false

    fun restore() {
        states.putAll(journal.readDelegations())
        states.filterValues { it.pending.isNotEmpty() }.keys.toList().forEach(::watch)
    }

    fun accept(sessionId: String, messages: List<Message>) {
        if (closed) return
        val old = states[sessionId] ?: DelegationReceipt()
        val deliveries = displayMessages(messages).filter { it.isDelegationDelivery && !it.content.isNullOrBlank() }
        val completed = deliveries.filter { it.delegationNotice.isNullOrBlank() }.mapNotNull { it.delegationId }.toSet()
        val pending = (old.pending + delegationDispatches(messages)) - completed - old.delivered
        val unseen = deliveries.filter { key(it) !in old.delivered }
        // Persist the watch before returning to the parent run's completion path.
        // A gate blocks delivery, not discovery or durable recovery.
        val waiting = pending + if (blocked(sessionId)) unseen.map { it.delegationId ?: key(it) } else emptyList()
        if (waiting.isNotEmpty() || unseen.isNotEmpty() || old.pending.isNotEmpty()) {
            val staged = old.copy(pending = waiting + unseen.map { it.delegationId ?: key(it) })
            states[sessionId] = staged
            if (!journal.writeDelegations(sessionId, staged)) { watch(sessionId); return }
            if (!blocked(sessionId)) {
                try {
                    onHistory(sessionId, messages)
                    unseen.forEach { onReply(sessionId, it) }
                } catch (cancel: CancellationException) { throw cancel
                } catch (_: Exception) { watch(sessionId); return }
                val next = DelegationReceipt(pending, old.delivered + unseen.map(::key))
                // If this commit fails the durable staged watch retries. Reply/read
                // receipts and inbox IDs make repeating that callback idempotent.
                if (journal.writeDelegations(sessionId, next)) states[sessionId] = next
            }
        }
        if (states[sessionId]?.pending?.isNotEmpty() == true) watch(sessionId)
    }

    private fun key(message: Message): String = message.delegationId?.let {
        if (message.delegationNotice.isNullOrBlank()) it else "$it:${message.delegationNotice}"
    } ?: "row:${message.id}"

    private fun watch(sessionId: String) {
        if (closed || jobs[sessionId]?.isActive == true) return
        jobs[sessionId] = scope.launch {
            do {
                delay(pollMs)
                if (!blocked(sessionId)) {
                    val result = withContext(Dispatchers.IO) { client.completeHistory(sessionId) }
                    if (closed) return@launch
                    if (result is HermesResult.Ok) accept(sessionId, result.value.data)
                }
            } while (isActive && states[sessionId]?.pending?.isNotEmpty() == true)
        }
    }

    fun close() { closed = true; jobs.values.forEach { it.cancel() }; jobs.clear() }
}
