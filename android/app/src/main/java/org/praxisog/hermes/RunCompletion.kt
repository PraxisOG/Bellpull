package org.praxisog.hermes

import org.praxisog.hermes.model.Message
import org.praxisog.hermes.model.RunSubmission

/** Match server output to a new saved final row, not merely a successful history GET.
 * Compaction may remove the prompt or clone old rows under fresh IDs. Server timestamps
 * fence those clones without comparing the phone's clock to the server's clock. */
internal fun matchedCompletion(record: RunSubmission, messages: List<Message>): Message? {
    val output = record.completionOutput?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val observed = record.requestId.startsWith("observed:")
    val fresh = if (observed) messages else messages.filter { it.id > (record.previousIds.maxOrNull() ?: 0) }
    val prompts = fresh.withIndex().filter { (_, row) -> row.role == "user" && samePrompt(row.content, record.text) }
    if (!observed && prompts.size > 1) return null // Concurrent/repeated identical prompts are ambiguous.
    val prompt = prompts.singleOrNull()?.index
    val segment = if (prompt != null) fresh.drop(prompt + 1).takeWhile { it.role != "user" } else fresh
    val candidates = segment.filter { row ->
        val timestamp = row.timestamp
        val started = record.serverCreatedAt
        row.role == "assistant" && row.toolCalls.isNullOrEmpty() && row.content?.trim() == output &&
            (started == null || timestamp == null || timestamp >= started)
    }
    val reply = candidates.singleOrNull() ?: return null
    // Missing prompt needs positive post-admission evidence. Old copied rows and
    // timestamp-less legacy histories cannot prove a compacted turn's completion.
    if (!observed && prompt == null && (record.serverCreatedAt == null || reply.timestamp == null)) return null
    return reply
}
