package org.praxisog.hermes.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DeliveryTest {
    private val reminder = Reminder("a".repeat(32), "A reminder", 1000, "pending", 1)
    private fun state() = DeliveryState(planning = PlanningSnapshot(1, listOf(reminder), observedAt = 1.0))
    private fun operation(action: String, due: Long? = null) = ReminderOperation("b".repeat(32), reminder.id, 1, action, due)
    @Test fun receivedAndDisplayedAreDifferentAndSurviveRestart() {
        val received = state().receivedReminders(2000)
        assertEquals(1, received.entries.size)
        assertEquals(1, received.due(2000).size)
        val displayed = received.copy(displayed = setOf(DeliveryState.occurrence(reminder)))
        val restored = Json.decodeFromString<DeliveryState>(Json.encodeToString(displayed))
        assertTrue(restored.due(2000).isEmpty())
        assertEquals("pending", restored.reminders().single().state)
        assertNull(restored.entries.single().openedAt)
    }
    @Test fun dismissAndCompleteStayDistinctAndNeverSendTwiceAfterAcknowledgement() {
        assertEquals("dismissed", state().enqueue(operation("dismiss")).reminders().single().state)
        assertEquals("completed", state().enqueue(operation("complete")).reminders().single().state)
        val acknowledged = state().enqueue(operation("complete").copy(status = "acknowledged"))
        assertEquals("completed", acknowledged.reminders().single().state)
        assertEquals(0, acknowledged.reconcile(PlanningSnapshot(1, listOf(reminder.copy(state="completed", revision=2)), observedAt=2.0), 2000).operations.size)
    }
    @Test fun snoozeChangesOnlyTheOccurrenceAndConflictDoesNotOverwriteServer() {
        val saved = state().copy(displayed=setOf(DeliveryState.occurrence(reminder))).enqueue(operation("snooze", 4000))
        assertTrue(saved.due(2000).isEmpty())
        assertEquals(1, saved.due(4000).size)
        val conflict = saved.copy(operations=saved.operations.map { it.copy(status="blocked") })
        assertEquals(1000L, conflict.reminders().single().dueMs)
        assertEquals(1, conflict.operations.size)
    }
    @Test fun titleEditsAndReconnectDoNotRealert() {
        val delivered = state().copy(displayed=setOf(DeliveryState.occurrence(reminder)))
        val revised = PlanningSnapshot(1, listOf(reminder.copy(title="Changed title", revision=2)), observedAt=2.0)
        var value = delivered
        repeat(100) { value = value.reconcile(revised, 2000) }
        assertTrue(value.due(2000).isEmpty())
    }
    @Test fun inboxIsBoundedDeduplicatedAndMarksTruncation() {
        var value = DeliveryState()
        repeat(1000) { value = value.receive(InboxEntry("$it", "reply", "Reply", "x".repeat(25000), it.toLong())) }
        assertEquals(200, value.entries.size)
        assertTrue(value.entries.all { it.truncated && it.body.length == 24000 })
        assertEquals(value, value.receive(value.entries.last().copy(body="duplicate")))
    }
    @Test(expected=IllegalArgumentException::class) fun overlappingOfflineActionsRequireSyncOrReview() {
        state().enqueue(operation("snooze", 4000)).enqueue(operation("complete"))
    }
}
