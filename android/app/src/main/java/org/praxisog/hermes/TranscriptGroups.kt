package org.praxisog.hermes

import org.praxisog.hermes.model.Message
import org.praxisog.hermes.model.Artifact

internal data class TranscriptGroup(val messages: List<Message>, val tools: Boolean) {
    val key: String get() = (if (tools) "tools-" else "message-") + messages.first().id
}

internal data class TranscriptEntry(val key: String, val group: TranscriptGroup? = null, val artifact: Artifact? = null)

/** One assistant turn may contain many server messages and intervening tool results. */
internal data class ResponseBlock(val entries: List<TranscriptEntry>, val assistant: Boolean) {
    val key: String get() = entries.first().key
    val messages: List<Message> get() = entries.flatMap { it.group?.messages.orEmpty() }
    // Branch at the last canonical assistant message, and copy/read all visible prose.
    val actionMessage: Message get() = (messages.lastOrNull { it.role == "assistant" } ?: messages.last())
        .copy(role = "assistant", content = messages.filter { it.role == "assistant" }
            .mapNotNull { it.content?.takeIf(String::isNotBlank) }.joinToString("\n\n"))
}

internal fun responseBlocks(entries: List<TranscriptEntry>): List<ResponseBlock> {
    val blocks = mutableListOf<ResponseBlock>()
    entries.forEach { entry ->
        val response = entry.group?.messages?.all { it.role == "assistant" || it.role == "tool" }
            ?: (entry.artifact?.source == "agent" && blocks.lastOrNull()?.assistant == true)
        if (response && blocks.lastOrNull()?.assistant == true) {
            val previous = blocks.removeAt(blocks.lastIndex)
            blocks += previous.copy(entries = previous.entries + entry)
        } else blocks += ResponseBlock(listOf(entry), response)
    }
    return blocks
}

/** Explicit message links win; older servers provide creation times instead. Unplaced files
 * remain accessible at the end of the transcript, never in a fixed composer shelf. */
internal fun artifactTranscript(groups: List<TranscriptGroup>, artifacts: List<Artifact>): List<TranscriptEntry> {
    val buckets = artifacts.distinctBy { it.id }.sortedWith(compareBy<Artifact> { it.createdAt }.thenBy { it.id })
        .groupBy { file ->
            val linked = groups.indexOfFirst { group -> group.messages.any { it.id == file.messageId } }
            if (linked >= 0) linked else {
                val timed = groups.withIndex().mapNotNull { (index, group) ->
                    group.messages.mapNotNull { it.timestamp }.maxOrNull()?.let { index to it }
                }
                if (timed.isEmpty()) groups.lastIndex
                else timed.lastOrNull { it.second <= file.createdAt }?.first ?: -1
            }
        }
    return buildList {
        fun files(index: Int) { buckets[index].orEmpty().forEach { add(TranscriptEntry("artifact-${it.id}", artifact = it)) } }
        files(-1)
        groups.forEachIndexed { index, group -> add(TranscriptEntry(group.key, group = group)); files(index) }
    }
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
