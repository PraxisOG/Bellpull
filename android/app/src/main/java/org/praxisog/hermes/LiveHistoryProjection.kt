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
    val textProjection = removeSavedPrefix(turn.text, saved)
    val textSuffix = textProjection.first
    if (observed && (turn.text.isBlank() || textSuffix == turn.text)) return LiveHistoryProjection(turn)
    var remaining = saved
    val savedReasoning = replies.mapNotNull { it.reasoningContent ?: it.reasoning }.joinToString("\n\n")
    val reasoningProjection = removeSavedPrefix(turn.reasoning, savedReasoning)
    var reasoning = savedReasoning
    val parts = turn.parts.mapNotNull { part ->
        if (part !is LivePart.Prose) part else {
            // The aggregate stream commonly duplicates a single prose/reasoning
            // part. Reuse that scan only while its saved prefix is unchanged.
            val (text, rest) = when {
                part.thinking && part.text == turn.reasoning && reasoning == savedReasoning -> reasoningProjection
                !part.thinking && part.text == turn.text && remaining == saved -> textProjection
                else -> removeSavedPrefix(part.text, if (part.thinking) reasoning else remaining)
            }
            if (part.thinking) reasoning = rest else remaining = rest
            part.copy(text = text).takeIf { text.isNotBlank() }
        }
    }
    return LiveHistoryProjection(turn.copy(text = textSuffix,
        reasoning = reasoningProjection.first,
        parts = parts), replies.last().id)
}

/** Token-prefix comparison tolerates transport whitespace while preserving Markdown
 * in the remaining live suffix. Mismatches leave the stream intact. */
private fun removeSavedPrefix(live: String, saved: String): Pair<String, String> {
    // Kotlin Regex.findAll retains a separate Matcher for each result. Android's
    // ICU matchers can each retain a native copy of the entire input: a long
    // replay consumed gigabytes and blocked the main thread. Two character
    // buffers keep memory linear in input size, with no per-token objects. They
    // also avoid millions of native String.charAt calls in Android's interpreter.
    if (live.isEmpty() || saved.isEmpty()) return live to saved
    val liveChars = live.toCharArray()
    val savedChars = saved.toCharArray()
    fun skipWhitespace(text: CharArray, from: Int): Int {
        var at = from
        while (at < text.size && isReplayWhitespace(text[at])) at++
        return at
    }
    var a = skipWhitespace(liveChars, 0)
    var b = skipWhitespace(savedChars, 0)
    if (a == liveChars.size || b == savedChars.size) return live to saved
    while (a < liveChars.size && b < savedChars.size) {
        while (a < liveChars.size && !isReplayWhitespace(liveChars[a])) {
            if (b == savedChars.size || liveChars[a] != savedChars[b]) return live to ""
            a++
            b++
        }
        // A matching partial word is not a matching token.
        if (b < savedChars.size && !isReplayWhitespace(savedChars[b])) return live to ""
        a = skipWhitespace(liveChars, a)
        b = skipWhitespace(savedChars, b)
    }
    return live.substring(a) to saved.substring(b)
}

// Avoid an ICU Character call for every ASCII character on Android. Preserve
// Kotlin's whitespace classification, including Unicode separators, otherwise.
@Suppress("NOTHING_TO_INLINE") // Hot loop in interpreted/debug Android builds too.
private inline fun isReplayWhitespace(char: Char): Boolean = if (char <= '\u007f') {
    char == ' ' || char in '\t'..'\r' || char in '\u001c'..'\u001f'
} else char.isWhitespace()
