package org.praxisog.hermes

import androidx.compose.runtime.mutableStateMapOf

/** Phone-owned receipts for canonical message IDs, separate from selection and run status. */
internal data class ConversationReceipt(val readThrough: Int = 0, val unreadReply: Int = 0)

internal class ConversationReadState(initial: Map<String, ConversationReceipt> = emptyMap()) {
    private val receipts = mutableStateMapOf<String, ConversationReceipt>().apply { putAll(initial) }
    var onChange: (String, ConversationReceipt) -> Unit = { _, _ -> }
    val unreadIds: Set<String> get() = receipts.filterValues { it.unreadReply > it.readThrough }.keys

    fun completed(sessionId: String, replyId: Int) {
        if (replyId <= 0) return
        val current = receipts[sessionId] ?: ConversationReceipt()
        update(sessionId, current.copy(unreadReply = maxOf(current.unreadReply, replyId)))
    }

    /** Called only when the saved reply's end is visible in the foreground transcript. */
    fun readThrough(sessionId: String, messageId: Int) {
        if (messageId <= 0) return
        val current = receipts[sessionId] ?: ConversationReceipt()
        update(sessionId, current.copy(readThrough = maxOf(current.readThrough, messageId)))
    }

    /** Native message IDs are database-wide; reading a continuation also reads older aliases. */
    fun readThroughAliases(sessionIds: Set<String>, messageId: Int) {
        sessionIds.forEach { readThrough(it, messageId) }
    }

    fun snapshot(): Map<String, ConversationReceipt> = receipts.toMap()

    private fun update(id: String, next: ConversationReceipt) {
        if (receipts[id] == next) return
        receipts[id] = next
        onChange(id, next)
    }
}
