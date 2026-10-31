package org.praxisog.hermes.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class CanvasNode(val id: String, val shape: String, val label: String, val x: Double, val y: Double,
    val width: Double, val height: Double, val color: String)
@Serializable data class CanvasEdge(val id: String, val from: String, val to: String, val label: String, val arrow: Boolean)
@Serializable data class CanvasContent(val name: String, val nodes: List<CanvasNode> = emptyList(), val edges: List<CanvasEdge> = emptyList())
@Serializable data class CanvasSummary(val id: String, val name: String, val revision: Long, val updatedAt: Long)
@Serializable data class CanvasFrame(val revision: Long, val at: Long, val summary: String)
@Serializable data class CanvasDocument(val id: String, val name: String, val revision: Long, val updatedAt: Long,
    val content: CanvasContent, val canUndo: Boolean, val canRedo: Boolean, val history: List<CanvasFrame> = emptyList())
@Serializable data class CanvasSnapshot(val version: Int, val scope: String, @SerialName("session_id") val sessionId: String,
    @SerialName("session_ids") val sessionIds: List<String>, val cursor: Long,
    val documents: List<CanvasSummary> = emptyList(), val document: CanvasDocument? = null) {
    fun validated(session: String): CanvasSnapshot {
        require(version == 1 && scope.matches(Regex("[a-f0-9]{64}")) && cursor >= 0)
        require(sessionIds.size in 1..1000 && session in sessionIds && sessionId in sessionIds)
        require(documents.size <= 100 && documents.map { it.id }.toSet().size == documents.size)
        fun id(value: String) = value.matches(Regex("[a-zA-Z0-9_-]{1,80}")) && value !in setOf("__proto__", "prototype", "constructor")
        documents.forEach { require(id(it.id) && it.name.length <= 200 && it.revision >= 0 && it.updatedAt >= 0) }
        document?.let { doc ->
            require(documents.any { it.id == doc.id && it.revision == doc.revision })
            require(doc.history.size <= 21 && doc.history.all { it.revision >= 0 && it.at >= 0 && it.summary.length <= 120 })
            val scene = doc.content
            require(scene.name.length <= 200 && scene.nodes.size <= 200 && scene.edges.size <= 400)
            val ids = mutableSetOf<String>()
            scene.nodes.forEach { n ->
                require(id(n.id) && ids.add(n.id) && n.label.length <= 500)
                require(n.shape in setOf("rectangle", "ellipse", "note", "text") && n.color in setOf("sand", "rose", "sage", "blue", "white"))
                require(n.x.isFinite() && n.y.isFinite() && n.x in -20000.0..20000.0 && n.y in -20000.0..20000.0)
                require(n.width in 40.0..2000.0 && n.height in 40.0..2000.0)
            }
            val nodes = ids.toSet(); val pairs = mutableSetOf<Pair<String, String>>()
            scene.edges.forEach { e -> require(id(e.id) && ids.add(e.id) && e.from in nodes && e.to in nodes && e.from != e.to &&
                pairs.add(e.from to e.to) && e.label.length <= 200) }
        }
        return this
    }
}

const val CANVAS_CONTEXT_OPEN = "<bellpull-canvas-context>"
const val CANVAS_CONTEXT_CLOSE = "</bellpull-canvas-context>"
fun canvasPrompt(text: String): String = text + "\n\n" + CANVAS_CONTEXT_OPEN + "\n" +
    "Canvas is enabled for this existing conversation on desktop and mobile. Use the registered bellpull_canvas tool, version 1. " +
    "Read first, create only if needed, and edit in separate tool calls for visible steps. Preserve manual edits; stop on conflicts. " +
    "Use the tool even if older messages describe bellpull.canvas.patch text. Do not print drawing JSON. Explain normally.\n" + CANVAS_CONTEXT_CLOSE

/** Existing desktop context is display-only; it never executes a historical edit. */
fun splitCanvasContext(text: String): Pair<String, String?> {
    val start = text.indexOf(CANVAS_CONTEXT_OPEN)
    if (start < 0) return text to null
    val end = text.indexOf(CANVAS_CONTEXT_CLOSE, start)
    return (text.take(start) + if (end >= 0) text.substring(end + CANVAS_CONTEXT_CLOSE.length) else "").trim() to
        text.substring(start + CANVAS_CONTEXT_OPEN.length, if (end >= 0) end else text.length).trim()
}

data class CanvasMessagePart(val drawing: Boolean, val text: String)
fun canvasMessageParts(text: String): List<CanvasMessagePart> {
    val parts = mutableListOf<CanvasMessagePart>(); var cursor = 0
    val starts = Regex("(^|\\s)\\{")
    for (match in starts.findAll(text)) {
        val at = match.range.last
        if (at < cursor) continue
        val raw = text.substring(at)
        val prefix = "{\"format\":\"bellpull.canvas.patch\""
        if (!Regex("^\\{\\s*\"format\"\\s*:\\s*\"bellpull\\.canvas\\.patch\"").containsMatchIn(raw) && !prefix.startsWith(raw.replace(Regex("\\s"), ""))) continue
        var depth = 0; var quoted = false; var escaped = false; var end = text.length
        for (i in at until text.length) {
            val c = text[i]
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false; continue }
            if (c == '"') quoted = true
            else if (c == '{' || c == '[') depth++
            else if (c == '}' || c == ']') { depth--; if (depth == 0) { end = i + 1; break } }
        }
        var before = text.substring(cursor, at)
        before = before.replace(Regex("```(?:bellpull-canvas|json)\\s*$"), "")
        if (before.isNotBlank()) parts += CanvasMessagePart(false, before)
        parts += CanvasMessagePart(true, text.substring(at, end))
        cursor = end
        Regex("^\\s*```(?:\\r?\\n|$)").find(text.substring(cursor))?.let { cursor += it.value.length }
    }
    if (cursor < text.length) parts += CanvasMessagePart(false, text.substring(cursor))
    return parts
}
fun canvasVisibleText(text: String): String = canvasMessageParts(splitCanvasContext(text).first).filterNot { it.drawing }.joinToString("") { it.text }.trim()
