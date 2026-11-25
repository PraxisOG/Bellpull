package org.praxisog.hermes.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val journalIdPattern = Regex("[a-f0-9]{64}")
private val nativeRunPattern = Regex("run_[a-zA-Z0-9_-]{1,100}")
private val chatLinkPattern = Regex("[a-zA-Z0-9_-]{1,200}")

@Serializable
data class RunNotificationReceipt(
    @SerialName("event_id") val eventId: String,
    val revision: Long,
    val source: String,
    @SerialName("run_id") val runId: String,
    @SerialName("session_id") val sessionId: String,
    val status: String,
    val severity: String,
    @SerialName("occurred_at") val occurredAt: Double,
    @SerialName("approval_request_id") val approvalId: String? = null,
    val dispatch: String,
    @SerialName("accepted_at") val acceptedAt: Double? = null,
) {
    val canObserveRun: Boolean get() = runId.matches(nativeRunPattern) && sessionId.matches(chatLinkPattern)

    internal fun validate() {
        require(eventId.matches(journalIdPattern) && revision > 0 && source == "hermes.run")
        require(runId.length in 1..512 && sessionId.length in 1..512 && approvalId.orEmpty().length <= 512)
        require(status in setOf("completed", "failed", "cancelled", "interrupted", "waiting_for_approval"))
        require(severity in setOf("info", "warning", "error", "attention") && occurredAt.isFinite())
        require((dispatch == "pending" && acceptedAt == null) ||
            (dispatch == "transport_accepted" && acceptedAt?.isFinite() == true))
    }

    internal fun inboxEntry(now: Long): InboxEntry {
        val id = when {
            status == "waiting_for_approval" && !approvalId.isNullOrEmpty() -> "approval:$approvalId"
            status in setOf("failed", "interrupted") -> "run:$runId"
            else -> "run-event:$eventId"
        }
        val title = when (status) {
            "completed" -> "Run completed"
            "failed" -> "Run failed"
            "cancelled" -> "Run cancelled"
            "interrupted" -> "Run interrupted"
            else -> "Approval requested"
        }
        return InboxEntry(id, "Run activity", title, "Open the conversation to review this run.", now,
            sessionId.takeIf { it.matches(chatLinkPattern) }, receiptChangedAt = now, runReceipt = this)
    }
}

@Serializable
data class RunNotificationPage(
    val version: Int,
    @SerialName("journal_id") val journalId: String,
    val after: Long,
    val events: List<RunNotificationReceipt>,
    @SerialName("next_cursor") val nextCursor: Long,
    @SerialName("has_more") val hasMore: Boolean,
    val reset: Boolean = false,
) {
    fun validate(previousJournal: String?, previousCursor: Long): RunNotificationPage {
        require(version == 1 && journalId.matches(journalIdPattern))
        require(after == (if (previousJournal == journalId && !reset) previousCursor else 0L))
        require(events.size <= 50 && events.distinctBy { it.eventId }.size == events.size)
        var cursor = after
        for (receipt in events) {
            receipt.validate()
            require(receipt.revision > cursor)
            cursor = receipt.revision
        }
        require(nextCursor == cursor && (!hasMore || events.isNotEmpty()))
        return this
    }
}

fun DeliveryState.receiveRunNotifications(page: RunNotificationPage, now: Long): DeliveryState {
    page.validate(notificationJournal, notificationCursor)
    val merged = entries.toMutableList()
    for (receipt in page.events) {
        val item = receipt.inboxEntry(now)
        val index = merged.indexOfFirst { it.runReceipt?.eventId == receipt.eventId ||
            (it.id == item.id && it.runReceipt == null && it.sessionId == item.sessionId) }
        if (index < 0) {
            merged.add(if (merged.any { it.id == item.id }) item.copy(id = "run-event:${receipt.eventId}") else item)
        } else {
            val old = merged[index]
            merged[index] = item.copy(id = old.id, receivedAt = old.receivedAt, openedAt = old.openedAt,
                dismissedAt = old.dismissedAt, receiptChangedAt = if (receipt == old.runReceipt) old.receiptChangedAt else now)
        }
    }
    return copy(entries = merged.takeLast(DeliveryState.MAX_ENTRIES), notificationJournal = page.journalId,
        notificationCursor = page.nextCursor)
}
