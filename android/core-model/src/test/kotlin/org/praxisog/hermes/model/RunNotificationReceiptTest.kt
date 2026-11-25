package org.praxisog.hermes.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RunNotificationReceiptTest {
    private val journal = "a".repeat(64)
    private val receipt = RunNotificationReceipt("b".repeat(64), 2, "hermes.run", "run_1", "session",
        "waiting_for_approval", "attention", 30.0, "request", "pending")

    private fun page(after: Long, event: RunNotificationReceipt, source: String = journal) =
        RunNotificationPage(1, source, after, listOf(event), event.revision, false)

    @Test fun liveAttentionAndDispatchUpdatesRetainOneOpenedOrDismissedItem() {
        val live = InboxEntry("approval:request", "attention", "Needs attention", "", 5, "session", 6, 7)
        val legacy = Json.decodeFromString<DeliveryState>("{}").receive(live)
        val stored = legacy.receiveRunNotifications(page(0, receipt), 100)
        assertEquals(2L, stored.notificationCursor)
        val accepted = receipt.copy(revision = 4, acceptedAt = 42.0, dispatch = "transport_accepted")
        val merged = stored.receiveRunNotifications(page(2, accepted), 110)
        val only = merged.entries.single()
        assertEquals(5L, only.receivedAt)
        assertEquals(6L, only.openedAt)
        assertEquals(7L, only.dismissedAt)
        assertEquals(accepted, only.runReceipt)
        assertEquals(merged, merged.receive(live))
        assertEquals(merged, merged.receiveRunNotifications(RunNotificationPage(1,journal,4, emptyList(),4,false),120))
        assertThrows(IllegalArgumentException::class.java) { merged.receiveRunNotifications(page(2, accepted), 120) }
    }

    @Test fun replacedJournalStartsAtZeroAndAnInvalidPageCannotAdvanceTheCursor() {
        val saved = DeliveryState().receiveRunNotifications(page(0, receipt), 30)
        val next = receipt.copy(eventId = "c".repeat(64), revision = 1, status = "completed", approvalId = null)
        val replacement = saved.receiveRunNotifications(page(0, next, "d".repeat(64)), 40)
        assertEquals(1L, replacement.notificationCursor)
        assertEquals(2, replacement.entries.size)
        assertThrows(IllegalArgumentException::class.java) { saved.receiveRunNotifications(page(2, next), 40) }
        assertThrows(IllegalArgumentException::class.java) {
            saved.receiveRunNotifications(RunNotificationPage(1,journal,2, emptyList(),8,true),40)
        }
    }
}
