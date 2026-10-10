package org.praxisog.hermes

import org.praxisog.hermes.model.StreamEvent

internal sealed interface LivePart {
    val id: String
    data class Prose(override val id: String, val text: String, val thinking: Boolean = false) : LivePart
    data class Tools(override val id: String, val rows: List<LiveTool>) : LivePart
}
internal data class LiveTool(val id: String, val name: String?, val preview: String?, val running: Boolean = true)

/** The legacy stream has sequence numbers but no tool-call IDs. Never guess between parallel names. */
internal class LiveTranscript {
    private var runId = "pending"
    private var fallbackId = 0
    private val seenSequences = mutableSetOf<Int>()
    var parts: List<LivePart> = emptyList()
        private set

    fun accept(event: StreamEvent) {
        if (event.seq?.let { !seenSequences.add(it) } == true) return
        if (event is StreamEvent.RunStarted) runId = event.runId ?: runId
        val id = "$runId:${event.seq ?: ++fallbackId}"
        when (event) {
            is StreamEvent.AssistantDelta -> prose(id, event.delta, false)
            is StreamEvent.ToolProgress -> if (event.toolName == "_thinking") prose(id, event.delta.orEmpty(), true)
            is StreamEvent.ToolStarted -> {
                val row = LiveTool(id, event.toolName, event.preview)
                val last = parts.lastOrNull()
                parts = if (last is LivePart.Tools) parts.dropLast(1) + last.copy(rows = last.rows + row)
                    else parts + LivePart.Tools(id, listOf(row))
            }
            is StreamEvent.ToolCompleted -> {
                val matching = parts.filterIsInstance<LivePart.Tools>().flatMap { it.rows }
                    .filter { it.running && it.name == event.toolName }
                if (matching.size == 1) parts = parts.map { part ->
                    if (part is LivePart.Tools) part.copy(rows = part.rows.map {
                        if (it.id == matching.single().id) it.copy(running = false) else it
                    }) else part
                }
            }
            else -> Unit
        }
    }

    private fun prose(id: String, delta: String, thinking: Boolean) {
        if (delta.isEmpty()) return
        val last = parts.lastOrNull()
        parts = if (last is LivePart.Prose && last.thinking == thinking)
            parts.dropLast(1) + last.copy(text = last.text + delta)
        else parts + LivePart.Prose(id, delta, thinking)
    }
}
