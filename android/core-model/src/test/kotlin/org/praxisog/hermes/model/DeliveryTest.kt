package org.praxisog.hermes.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DeliveryTest {
    private val reminder = Reminder("a".repeat(32), "A reminder", 1000, "pending", 1)
    private fun state() = DeliveryState(planning = PlanningSnapshot(1, listOf(reminder), observedAt = 1.0))
    private fun operation(action: String, due: Long? = null) = ReminderOperation("b".repeat(32), reminder.id, 1, action, due)
    @Test fun missedNoticeUsesOriginalExecutionAndSurvivesCurrentReceiptAndOfflineRestore() {
        val job = "original-job"
        val original = "1".repeat(32)
        val latest = "2".repeat(32)
        val notice = BotChatReceipt("bot-chat:(own)", "a".repeat(64), "ambiguous", "deferred", original, true)
        val current = BotChatReceipt("bot-chat:(own)", "b".repeat(64), "settled", "owner", latest)
        val history = BotChatHistory(listOf(
            HistoricalBotChatDelivery(job, "2026-11-24T12:04:00Z", current),
            HistoricalBotChatDelivery(job, "2026-11-24T11:04:00Z", notice)))
        val routine = Routine(job, name = "Routine", fingerprint = "f", botChatReceipts = listOf(current.copy(executionId = null)))
        val snapshot = PlanningSnapshot(1, emptyList(), listOf(routine), observedAt = 2.0, botChatHistory = history)
        val first = DeliveryState().reconcile(snapshot, 100)
        assertEquals(listOf(notice, current), first.entries.map { it.botChatReceipt })
        assertEquals("Bot Chat delivery notice · ambiguous", first.entries.first().body)
        val second = first.reconcile(snapshot.copy(botChatHistory = null), 200)
        val restored = Json.decodeFromString<DeliveryState>(Json.encodeToString(second))
        assertEquals(first.entries, restored.entries)
        assertEquals(first.entries, restored.rememberHistory(history, 300).entries)
    }

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
    @Test fun observedBotChatReceiptSurvivesLaterJobsAndOfflineRestore() {
        val receiptId = "a".repeat(64)
        val otherId = "b".repeat(64)
        val run = RoutineExecution("first", "completed", "queued")
        fun snapshot(execution: RoutineExecution, vararg receipts: BotChatReceipt) =
            PlanningSnapshot(1, emptyList(), routines = listOf(Routine("job", name = "Morning report",
                fingerprint = "f", latestExecution = execution, botChatReceipts = receipts.toList())), observedAt = 1.0)
        val pending = BotChatReceipt("bot-chat:(own)", receiptId, "queued", "scheduler")
        val initial = DeliveryState().reconcile(snapshot(run, pending), 1000)
        val receipt = initial.entries.single { it.kind == "delivery" }
        val read = initial.copy(entries = initial.entries.map { if (it == receipt) it.copy(openedAt = 1100) else it })
        val ambiguous = pending.copy(status = "ambiguous", source = "owner")
        val updated = read.reconcile(snapshot(run, ambiguous), 1200)
        val stale = updated.reconcile(snapshot(run, pending), 1300)
        val rotated = stale.reconcile(snapshot(run.copy(id = "second"), pending.copy(id = otherId)), 1400)
        val offline = Json.decodeFromString<DeliveryState>(Json.encodeToString(rotated))
        val history = offline.entries.single { it.id == receipt.id }
        assertEquals("Bot Chat · ambiguous", history.body)
        assertEquals(1100L, history.openedAt)
        assertEquals(1000L, history.receivedAt)
        assertEquals("job", history.routineId)
        assertEquals(1200L, history.receiptChangedAt)
        assertEquals(ambiguous, history.botChatReceipt)
        assertEquals(2, offline.entries.count { it.kind == "delivery" })
        assertEquals(2, offline.entries.count { it.kind == "routine" })
    }
    @Test fun oldCacheImportsReceiptsOnceEvenWhenNextSnapshotOmitsTheEntireJob() {
        val id = "a".repeat(32)
        val receipt = BotChatReceipt("bot-chat:work", id, "failed", "deferred")
        val snap = PlanningSnapshot(1, emptyList(), routines = listOf(Routine("job", fingerprint = "f",
            botChatReceipts = listOf(receipt))), observedAt = 10.0)
        val legacy = Json.decodeFromString<DeliveryState>("""{"planning":${Json.encodeToString(snap)},"lastSync":12000}""")
        val restored = legacy.withCachedReceipts()
        assertEquals(restored, restored.withCachedReceipts())
        val original = restored.entries.single()
        assertEquals(receipt, original.botChatReceipt)
        assertEquals(12000L, original.receiptChangedAt)
        val updated = legacy.reconcile(snap.copy(routines = emptyList()), 14000)
        assertEquals(original, updated.entries.single())
        var crowded = restored
        repeat(200) { crowded = crowded.receive(InboxEntry("r$it", "reply", "Reply", "body", 15000)) }
        assertTrue(crowded.reconcile(snap, 16000).entries.none { it.id == original.id })
    }

    @Test fun aReplacementOwnerRemainsVisibleAcrossReplayUntilTheOriginalReceiptSettles() {
        val receipt = BotChatReceipt("bot-chat:(own)", "b".repeat(64), "claimed", "owner", "1".repeat(32))
        fun page(item: BotChatReceipt) = BotChatHistory(listOf(HistoricalBotChatDelivery("job", "stamp", item)))
        val first = DeliveryState().rememberHistory(page(receipt.copy(ownerState = "registered")), 100)
        val changed = first.rememberHistory(page(receipt.copy(ownerState = "replaced")), 200)
        val stale = changed.rememberHistory(page(receipt.copy(status = "queued", ownerState = "registered")), 300)
            .rememberHistory(page(receipt), 400)
        assertEquals(changed, stale)
        assertEquals("Bot Chat · claimed · owner changed, outcome unconfirmed", stale.entries.single().displayBody)
        val settled = stale.rememberHistory(page(receipt.copy(status = "settled")), 500)
        assertEquals("Bot Chat · completed", settled.entries.single().displayBody)
        assertEquals(settled, settled.rememberHistory(page(receipt.copy(ownerState = "replaced")), 600))
        val legacy = stale.entries.single().copy(body = "Bot Chat · processing", botChatReceipt = receipt)
        assertEquals("Bot Chat · claimed, outcome unconfirmed", legacy.displayBody)
    }

    @Test fun transferredDeferredReceiptCanAdvanceThroughNativeOwnerProcessingAndSettlement() {
        val receipt = BotChatReceipt("bot-chat:(own)", "b".repeat(64), "transferred", "deferred")
        val owned = receipt.copy(status = "queued", source = "owner")
        assertTrue(owned.supersedes(receipt))
        assertFalse(receipt.supersedes(owned))
        val claimed = owned.copy(status = "claimed")
        assertTrue(claimed.supersedes(owned))
        assertFalse(owned.supersedes(claimed))
        val settled = claimed.copy(status = "settled")
        assertTrue(settled.supersedes(claimed))
        assertFalse(claimed.supersedes(settled))
        assertFalse(settled.copy(status = "unknown").supersedes(settled))
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
