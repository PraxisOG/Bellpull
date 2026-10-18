package org.praxisog.hermes.model

import kotlinx.serialization.Serializable

/** Phone-owned delivery receipts. Nothing here grants approval or completes a run. */
@Serializable data class InboxEntry(
    val id: String, val kind: String, val title: String, val body: String,
    val receivedAt: Long, val sessionId: String? = null, val openedAt: Long? = null,
    val dismissedAt: Long? = null, val truncated: Boolean = false,
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
    val lastSync: Long = 0,
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
        val next = copy(planning = snapshot, lastSync = now, operations = operations.filter { it.status != "acknowledged" })
        val valid = next.reminders().map { occurrence(it) }.toSet()
        return next.copy(displayed = displayed.intersect(valid))
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
