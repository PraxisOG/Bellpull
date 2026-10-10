package org.praxisog.hermes

import org.praxisog.hermes.model.Message

internal data class TranscriptGroup(val messages: List<Message>, val tools: Boolean) {
    val key: String get() = (if (tools) "tools-" else "message-") + messages.first().id
}

/** Commentary/user/system messages are hard boundaries, even within the same turn. */
internal fun groupTranscript(messages: List<Message>): List<TranscriptGroup> {
    val groups = mutableListOf<TranscriptGroup>()
    for (message in messages) {
        if (message.role == "tool" && groups.lastOrNull()?.tools == true) {
            val previous = groups.removeAt(groups.lastIndex)
            groups += previous.copy(messages = previous.messages + message)
        } else groups += TranscriptGroup(listOf(message), tools = message.role == "tool")
    }
    return groups
}
