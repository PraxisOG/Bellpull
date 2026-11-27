package org.praxisog.hermes

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.model.CanvasNode

class CanvasPropertyEditsTest {
    private val note = CanvasNode("note", "note", "Original", 0.0, 0.0, 180.75, 100.5, "sand")

    @Test fun competingEditSurvivesRefreshAndCanBeKeptOrDiscarded() {
        val local = CanvasPropertyEdits().change(CanvasProperty.LABEL, note, "My draft")
            .change(CanvasProperty.WIDTH, note, "220")
        val remote = note.copy(label = "Saved elsewhere", height = 135.25)
        val form = local.reconcile(remote)
        assertEquals(setOf(CanvasProperty.LABEL), form.conflicts(remote))
        assertEquals("My draft", form.text(CanvasProperty.LABEL, remote))
        assertEquals("135.25", form.text(CanvasProperty.HEIGHT, remote))
        assertEquals(buildJsonObject { put("label", "My draft"); put("width", 220.0) }, form.changes(remote))
        val keep = form.discard(form.conflicts(remote))
        assertEquals("Saved elsewhere", keep.text(CanvasProperty.LABEL, remote))
        assertEquals(buildJsonObject { put("width", 220.0) }, keep.changes(remote))
    }

    @Test fun matchingAcknowledgementClearsOnlyConfirmedFields() {
        val local = CanvasPropertyEdits().change(CanvasProperty.LABEL, note, "First")
            .change(CanvasProperty.HEIGHT, note, "150.50")
        val partial = local.reconcile(note.copy(height = 150.5))
        val next = note.copy(label = "Next", height = 165.75)
        assertEquals("165.75", partial.text(CanvasProperty.HEIGHT, next))
        assertEquals(setOf(CanvasProperty.LABEL), partial.conflicts(next))
        assertTrue(partial.reconcile(note.copy(label = "First", height = 150.5)).changes(next)!!.isEmpty())
    }

    @Test fun untouchedFractionalDimensionsAndInvalidInputProduceNoSizeWrite() {
        val empty = CanvasPropertyEdits()
        assertEquals("180.75", empty.text(CanvasProperty.WIDTH, note))
        assertTrue(empty.changes(note)!!.isEmpty())
        assertTrue(empty.change(CanvasProperty.WIDTH, note, "180.750").changes(note)!!.isEmpty())
        val textOnly = empty.change(CanvasProperty.LABEL, note, "")
        assertEquals(buildJsonObject { put("label", "") }, textOnly.changes(note))
        for (invalid in listOf("", "39", "2001", "NaN", "Infinity")) {
            assertNull(textOnly.change(CanvasProperty.HEIGHT, note, invalid).changes(note))
        }
    }
}
