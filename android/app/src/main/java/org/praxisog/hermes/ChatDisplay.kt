package org.praxisog.hermes

import androidx.compose.runtime.staticCompositionLocalOf
import org.praxisog.hermes.model.ToolResult
import java.util.Locale

internal data class ChatDisplayOptions(
    val hideTerminalCalls: Boolean = false,
    val hideThinking: Boolean = false,
    val thinkingPreview: Boolean = false,
    val activityCounts: Boolean = true,
) {
    fun showsTool(name: String?, raw: String? = null): Boolean {
        if (!hideTerminalCalls || activityKind(name) != ActivityKind.TERMINAL) return true
        val result = ToolResult.parse(raw) ?: return false
        return result.isBlocked || result.wasFlaggedAndAllowed || result.status in setOf("error", "pending_approval") ||
            !result.error.isNullOrBlank() || (result.exitCode != null && result.exitCode != 0)
    }
}
internal val LocalChatDisplay = staticCompositionLocalOf { ChatDisplayOptions() }
internal val LocalTranscriptInteraction = staticCompositionLocalOf<() -> Unit> { {} }
internal val LocalResponseActivity = staticCompositionLocalOf { ResponseActivity() }

internal enum class ActivityKind { TERMINAL, SEARCH, OTHER }
internal fun activityKind(name: String?) = when (name?.lowercase(Locale.ROOT)) {
    "terminal" -> ActivityKind.TERMINAL
    "web_search" -> ActivityKind.SEARCH
    else -> ActivityKind.OTHER
}
internal data class ResponseActivity(val thinking: Int = 0, val terminal: Int = 0, val searches: Int = 0, val other: Int = 0) {
    val label get() = listOfNotNull(
        thinking.takeIf { it > 0 }?.let { "$it thinking ${if (it == 1) "block" else "blocks"}" },
        terminal.takeIf { it > 0 }?.let { "$it terminal" },
        searches.takeIf { it > 0 }?.let { "$it ${if (it == 1) "search" else "searches"}" },
        other.takeIf { it > 0 }?.let { "$it other ${if (it == 1) "tool" else "tools"}" },
    ).joinToString(" · ")
}
internal fun responseActivity(turn: TurnState): ResponseActivity {
    if (turn !is TurnState.Streaming) return ResponseActivity()
    val thoughts = turn.parts.filterIsInstance<LivePart.Prose>().filter { it.thinking && it.text.isNotBlank() }.distinctBy { it.id }.size
    val tools = turn.parts.filterIsInstance<LivePart.Tools>().flatMap { it.rows }.distinctBy { it.id }.map { activityKind(it.name) }
    val fallback = if (tools.isEmpty() && turn.parts.isEmpty() && turn.runningTool != null) listOf(activityKind(turn.runningToolName)) else emptyList()
    val all = tools + fallback
    return ResponseActivity(if (thoughts == 0 && turn.parts.isEmpty() && turn.reasoning.isNotBlank()) 1 else thoughts,
        all.count { it == ActivityKind.TERMINAL }, all.count { it == ActivityKind.SEARCH }, all.count { it == ActivityKind.OTHER })
}

/** Bounded scan even for megabyte reasoning; keep Unicode code points intact. */
internal fun thinkingPreview(text: String, limit: Int = 140): String {
    val out = StringBuilder()
    var at = 0; var count = 0; var space = false
    while (at < text.length && at < 4096 && count < limit) {
        val point = text.codePointAt(at); at += Character.charCount(point)
        if (Character.isWhitespace(point) || Character.isSpaceChar(point)) {
            space = out.isNotEmpty()
        } else {
            if (space && count < limit) { out.append(' '); count++ }
            if (count >= limit) { at -= Character.charCount(point); break }
            out.appendCodePoint(point); count++; space = false
        }
    }
    return out.toString().trimEnd() + if (at < text.length && out.isNotEmpty()) "…" else ""
}
