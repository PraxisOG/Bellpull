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
    @Test fun executionAndDeliveryStayDistinctAcrossRestartAndQueuedUpdates() {
        val run = RoutineExecution("attempt1", "completed", "queued")
        fun snapshot(run: RoutineExecution) = PlanningSnapshot(1, emptyList(),
            routines = listOf(Routine("job", name="Morning report", fingerprint="f", latestExecution=run)), observedAt=1.0)
        val first = DeliveryState().reconcile(snapshot(run), 100)
        val entry = first.entries.single()
        assertEquals("Execution completed\nQueued for delivery", entry.body)
        val opened = first.copy(entries=listOf(entry.copy(openedAt=101)))
        val restored = Json.decodeFromString<DeliveryState>(Json.encodeToString(opened))
        val changed = restored.reconcile(snapshot(run.copy(deliveryOutcome="failed")), 110)
        assertEquals(entry.id, changed.entries.single().id)
        assertEquals(101L, changed.entries.single().openedAt)
        assertEquals("Execution completed\nDelivery failed", changed.entries.single().body)
        val another = changed.reconcile(snapshot(run.copy(id="attempt2", status="unknown", deliveryOutcome=null)), 120)
        assertEquals(2, another.entries.size)
        assertEquals("Execution unknown\nDelivery unconfirmed", another.entries.last().body)
        assertEquals(another.entries, another.reconcile(checkNotNull(another.planning), 130).entries)
    }
    @Test fun previousPluginsSupportBothSingleAndMultipleDeliveryDestinations() {
        fun read(field: String) = Json.decodeFromString<Routine>("""{"id":"job","fingerprint":"v","deliver":$field}""").deliver
        assertNull(read("null"))
        assertEquals("local", read(""""local""""))
        assertEquals("local, bot-chat", read("""["local","bot-chat"]"""))
    }
    @Test(expected=IllegalArgumentException::class) fun overlappingOfflineActionsRequireSyncOrReview() {
        state().enqueue(operation("snooze", 4000)).enqueue(operation("complete"))
    }
}
