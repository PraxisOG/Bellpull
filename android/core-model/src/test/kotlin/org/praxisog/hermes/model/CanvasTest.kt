package org.praxisog.hermes.model

import org.junit.Assert.*
import org.junit.Test

class CanvasTest {
    private val node = CanvasNode("one", "rectangle", "One", 0.0, 0.0, 180.0, 100.0, "rose")
    private fun snapshot(nodes: List<CanvasNode> = listOf(node), edges: List<CanvasEdge> = emptyList()) = CanvasSnapshot(1, "a".repeat(64), "root", listOf("root", "tip"), 4,
        listOf(CanvasSummary("doc", "Shared", 2, 1)), CanvasDocument("doc", "Shared", 2, 1, CanvasContent("Shared", nodes, edges), true, false))
    @Test fun validatesExistingSessionHandoffAndRejectsForeignOrInvalidScenes() {
        assertEquals("doc", snapshot().validated("tip").document!!.id)
        assertThrows(IllegalArgumentException::class.java) { snapshot().validated("other") }
        assertThrows(IllegalArgumentException::class.java) { snapshot(listOf(node.copy(width = 0.0))).validated("root") }
        assertThrows(IllegalArgumentException::class.java) { snapshot(listOf(node, node)).validated("root") }
        assertThrows(IllegalArgumentException::class.java) { snapshot(edges = listOf(CanvasEdge("edge", "one", "missing", "", true))).validated("root") }
    }
    @Test fun historicalDrawingJsonAndNewContextAreHiddenWithoutExecutingAnything() {
        val raw = """{"format":"bellpull.canvas.patch","operations":[{"type":"rename","name":"Quoted } text"}]}"""
        val text = "Here is the diagram.\n\n$raw\n\nDone."
        assertEquals(listOf(false, true, false), canvasMessageParts(text).map { it.drawing })
        assertEquals("Here is the diagram.\n\n\n\nDone.", canvasVisibleText(text))
        assertEquals("Draw a map.", canvasVisibleText(canvasPrompt("Draw a map.")))
        assertEquals("Done.", canvasVisibleText("```bellpull-canvas\n$raw\n```\nDone."))
        for (i in 1..raw.length) assertEquals("Intro.", canvasVisibleText("Intro.\n" + raw.take(i)))
    }
}
