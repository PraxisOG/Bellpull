package org.praxisog.hermes.model

import kotlinx.serialization.Serializable

private val RECEIPT_ID = Regex("[0-9a-f]{32,64}")

/** Phone-owned delivery receipts. Nothing here grants approval or completes a run. */
@Serializable data class InboxEntry(
    val id: String, val kind: String, val title: String, val body: String,
    val receivedAt: Long, val sessionId: String? = null, val openedAt: Long? = null,
    val dismissedAt: Long? = null, val truncated: Boolean = false,
    val routineId: String? = null, val botChatReceipt: BotChatReceipt? = null,
    val receiptChangedAt: Long? = null,
)
@Serializable data class ReminderOperation(
    val operationId: String, val reminderId: String, val revision: Long,
    val action: String, val dueMs: Long? = null, val status: String = "pending",
    val problem: String? = null,
)
@Serializable data class DeliveryState(
    val planning: PlanningSnapshot? = null,
    val entries: List<InboxEntry> = emptyList(),
    val operations: List<ReminderOperation> = emptyList(),
    val displayed: Set<String> = emptySet(),
    val lastSync: Long = 0, val receiptHistoryInitialized: Boolean = false,
) {
    fun reminders(): List<Reminder> = planning?.reminders.orEmpty().map { reminder ->
        val operation = operations.firstOrNull { it.reminderId == reminder.id && it.status != "blocked" }
        when (operation?.action) {
            "complete" -> reminder.copy(state = "completed")
            "dismiss" -> reminder.copy(state = "dismissed")
            "snooze" -> reminder.copy(dueMs = checkNotNull(operation.dueMs))
            else -> reminder
        }
    }
    fun receive(entry: InboxEntry): DeliveryState {
        if (entries.any { it.id == entry.id }) return this
        // Bounded by both count and per-entry size. Truncation is visible in the inbox.
        return copy(entries = (entries + entry.copy(title = entry.title.take(400), body = entry.body.take(MAX_BODY),
            truncated = entry.truncated || entry.body.length > MAX_BODY)).takeLast(MAX_ENTRIES))
    }
    fun enqueue(operation: ReminderOperation): DeliveryState {
        require(operation.action in setOf("snooze", "complete", "dismiss"))
        require(operation.operationId.matches(Regex("[a-f0-9]{32}")))
        require(operations.size < 100) { "Sync or review pending reminder actions before adding more." }
        require(operations.none { it.reminderId == operation.reminderId }) { "This reminder already has an action awaiting sync or review." }
        val reminder = checkNotNull(planning?.reminders?.find { it.id == operation.reminderId })
        require(reminder.revision == operation.revision && reminder.state == "pending") { "Refresh this reminder before changing it." }
        require(operation.action != "snooze" || (operation.dueMs ?: 0) > 0)
        return copy(operations = operations + operation)
    }
    fun reconcile(snapshot: PlanningSnapshot, now: Long): DeliveryState {
        snapshot.validated()
        // Acknowledged operations stay optimistic until a canonical snapshot arrives.
        val next = withCachedReceipts().copy(planning = snapshot, lastSync = now, operations = operations.filter { it.status != "acknowledged" })
        val valid = next.reminders().map { occurrence(it) }.toSet()
        return snapshot.routines.fold(next.copy(displayed = displayed.intersect(valid))) { state, routine ->
            val previous = planning?.routines?.find { it.id == routine.id }
            state.reconcileRoutine(routine, previous, now).rememberReceipts(routine, previous, now)
        }
    }
    /** Import the last planning snapshot saved before the inbox started retaining native receipts. */
    fun withCachedReceipts(): DeliveryState {
        if (receiptHistoryInitialized) return this
        val cachedAt = lastSync.takeIf { it > 0 } ?: ((planning?.observedAt ?: 0.0) * 1000).toLong()
        return planning?.routines.orEmpty().fold(copy(receiptHistoryInitialized = true)) { state, routine ->
            state.rememberReceipts(routine, null, cachedAt)
        }
    }

    private fun rememberReceipts(routine: Routine, previous: Routine?, now: Long): DeliveryState =
        routine.botChatReceipts.fold(this) { state, incoming ->
            if (!RECEIPT_ID.matches(incoming.id) || !incoming.target.startsWith("bot-chat:")) return@fold state
            val id = "bot-chat:${incoming.id}"
            val old = state.entries.find { it.id == id }
            if (old == null && incoming in previous?.botChatReceipts.orEmpty()) return@fold state // Already evicted.
            val receipt = incoming.copy(target = incoming.target.take(400))
            if (old?.botChatReceipt != null && !receipt.supersedes(old.botChatReceipt)) return@fold state
            if (old == null) state.receive(InboxEntry(id, "delivery", routine.name ?: "Routine delivery",
                receipt.label(), now, routineId = routine.id, botChatReceipt = receipt, receiptChangedAt = now))
            else state.copy(entries = state.entries.map {
                if (it.id == id) it.copy(body = receipt.label(), botChatReceipt = receipt, receiptChangedAt = now) else it
            })
        }

    private fun reconcileRoutine(routine: Routine, previous: Routine?, now: Long): DeliveryState {
        val run = routine.latestExecution
        if (run == null) {
            // Older plugins only supply the last run time and a summary.
            if (routine.last == null || previous == null || (previous.last == routine.last && previous.lastStatus == routine.lastStatus)) return this
            val body = listOfNotNull(routine.lastStatus ?: "Execution status unknown", routine.lastError, routine.lastDeliveryError).joinToString("\n")
            return receive(InboxEntry("routine:${routine.id}:${routine.last}:${routine.lastStatus}", "routine",
                routine.name ?: "Routine result", body, now, routineId = routine.id))
        }
        if (run.status !in setOf("completed", "failed", "unknown")) return this
        val id = "routine:${routine.id}:execution:${run.id}"
        val body = "Execution ${run.status}\n${run.deliveryLabel()}"
        val old = entries.find { it.id == id }
        if (old != null) {
            return if (old.body == body) this else copy(entries = entries.map { if (it.id == id) it.copy(body = body) else it })
        }
        if (previous?.latestExecution == run) return this
        return receive(InboxEntry(id, "routine", routine.name ?: "Routine result", body, now, routineId = routine.id))
    }
    fun due(now: Long): List<Reminder> = reminders().filter {
        it.state == "pending" && it.dueMs <= now && occurrence(it) !in displayed
    }
    fun receivedReminders(now: Long): DeliveryState = due(now).fold(this) { state, reminder ->
        state.receive(InboxEntry(occurrence(reminder), "reminder", reminder.title,
            "Reminder due", now))
    }
    companion object {
        const val MAX_ENTRIES = 200
        const val MAX_BODY = 24000
        // Title/revision-only edits must not re-alert an already delivered occurrence.
        fun occurrence(reminder: Reminder) = "reminder:${reminder.id}:${reminder.dueMs}"
    }
}
