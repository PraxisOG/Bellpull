package org.praxisog.hermes

import org.praxisog.hermes.model.Message
import org.praxisog.hermes.model.RunSubmission

internal data class LiveHistoryProjection(val turn: TurnState, val appendTo: Int? = null)

/** Presentation only: retain the raw stream for speech/recovery. Match within this
 * admitted turn, never deduplicate identical answers across separate user prompts. */
internal fun projectLiveHistory(turn: TurnState, messages: List<Message>, record: RunSubmission?): LiveHistoryProjection {
    if (turn !is TurnState.Streaming || record == null) return LiveHistoryProjection(turn)
    val observed = record.requestId.startsWith("observed:")
    val segment = if (observed) {
        val admitted = record.serverCreatedAt ?: return LiveHistoryProjection(turn)
        val fresh = messages.filter { it.id > (record.previousIds.maxOrNull() ?: 0) &&
            it.timestamp?.let { timestamp -> timestamp >= admitted } == true }
        // Notification recovery has no submitted prompt. Require server-time
        // evidence and a matching replay prefix, and reject multiple new turns.
        if (fresh.count { it.role == "user" } > 1) return LiveHistoryProjection(turn)
        val start = fresh.indexOfFirst { it.role == "user" }
        if (start >= 0) fresh.drop(start + 1) else fresh
    } else {
        val fresh = messages.filter { it.id !in record.previousIds }
        val prompts = fresh.indices.filter { fresh[it].role == "user" && samePrompt(fresh[it].content, record.text) }
        val start = prompts.singleOrNull() ?: return LiveHistoryProjection(turn)
        fresh.drop(start + 1).also { if (it.any { row -> row.role == "user" }) return LiveHistoryProjection(turn) }
    }
    val replies = segment.filter { it.role == "assistant" }
    if (replies.isEmpty()) return LiveHistoryProjection(turn)
    val saved = replies.mapNotNull { it.content }.joinToString("\n\n")
    if (observed && (turn.text.isBlank() || removeSavedPrefix(turn.text, saved).first == turn.text)) return LiveHistoryProjection(turn)
    var remaining = saved
    var reasoning = replies.mapNotNull { it.reasoningContent ?: it.reasoning }.joinToString("\n\n")
    val parts = turn.parts.mapNotNull { part ->
        if (part !is LivePart.Prose) part else {
            val (text, rest) = removeSavedPrefix(part.text, if (part.thinking) reasoning else remaining)
            if (part.thinking) reasoning = rest else remaining = rest
            part.copy(text = text).takeIf { text.isNotBlank() }
        }
    }
    return LiveHistoryProjection(turn.copy(text = removeSavedPrefix(turn.text, saved).first,
        reasoning = removeSavedPrefix(turn.reasoning, replies.mapNotNull { it.reasoningContent ?: it.reasoning }.joinToString("\n\n")).first,
        parts = parts), replies.last().id)
}

/** Token-prefix comparison tolerates transport whitespace while preserving Markdown
 * in the remaining live suffix. Mismatches leave the stream intact. */
private fun removeSavedPrefix(live: String, saved: String): Pair<String, String> {
    val a = Regex("\\S+").findAll(live).toList()
    val b = Regex("\\S+").findAll(saved).toList()
    if (a.isEmpty() || b.isEmpty()) return live to saved
    val count = minOf(a.size, b.size)
    if ((0 until count).any { a[it].value != b[it].value }) return live to ""
    return (if (a.size <= b.size) "" else live.substring(a[count - 1].range.last + 1).trimStart()) to
        (if (b.size <= a.size) "" else saved.substring(b[count - 1].range.last + 1).trimStart())
}
