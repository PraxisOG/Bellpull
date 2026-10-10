package org.praxisog.hermes

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.text.input.TextFieldValue

/** Phone-owned drafts, keyed by server session identity, including the cursor/selection. */
internal class ChatDrafts {
    private val values = mutableStateMapOf<String, TextFieldValue>()
    var onChange: (String, TextFieldValue) -> Unit = { _, _ -> }
    private val revisions = mutableMapOf<String, Long>()
    operator fun get(sessionId: String): TextFieldValue = values[sessionId] ?: TextFieldValue()
    fun set(sessionId: String, value: TextFieldValue) {
        values[sessionId] = value
        revisions[sessionId] = revision(sessionId) + 1
        onChange(sessionId, value)
    }
    fun revision(sessionId: String): Long = revisions[sessionId] ?: 0
    fun restore(sessionId: String, value: TextFieldValue, revision: Long) {
        values[sessionId] = value
        revisions[sessionId] = revision
    }
    fun accepted(sessionId: String, submittedRevision: Long) {
        // Acknowledging an older send must never erase text typed since that send.
        if (revision(sessionId) == submittedRevision) set(sessionId, TextFieldValue())
    }
    fun snapshot(): Map<String, TextFieldValue> = values.toMap()
    fun clear() { values.clear(); revisions.clear() }
}
