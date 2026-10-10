package org.praxisog.hermes

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class CutoutHeaderTest {
    private fun place(width: Int, rect: Rect, leading: Int = 48, trailing: Int = 96) =
        placeHeader(width, 64, 4, 6, leading, trailing, listOf(rect))

    @Test fun centeredCameraKeepsActionsAtBothEdgesAndTitleOnOneSide() {
        val hole = Rect(177f, 0f, 218f, 58f)
        val result = place(393, hole)
        assertEquals(4, result.leadingX)
        assertEquals(293, result.trailingX)
        assertEquals(0, result.rowY)
        assertTrue(result.title.end <= 171)
        assertEquals(64, result.height)
    }

    @Test fun cornerCameraMovesContextCloseAndChatActionsLeft() {
        val hole = Rect(150f, 0f, 200f, 58f)
        val context = place(200, hole, leading = 0, trailing = 48)
        assertEquals(96, context.trailingX)
        assertEquals(0, context.rowY)
        assertTrue(context.title.end <= 90)
        val chat = place(755, Rect(705f, 0f, 755f, 58f))
        assertEquals(603, chat.trailingX)
    }

    @Test fun cameraOutsideThisPaneDoesNotReserveItsWidth() {
        val result = place(240, Rect(705f, 0f, 755f, 58f), leading = 0, trailing = 48)
        assertEquals(188, result.trailingX)
        assertEquals(64, result.height)
    }

    @Test fun wideNotchFallsBelowCameraWithoutShrinkingTargets() {
        val result = place(320, Rect(30f, 0f, 290f, 70f))
        assertEquals(76, result.rowY)
        assertEquals(140, result.height)
        assertEquals(4, result.leadingX)
        assertEquals(220, result.trailingX)
    }

    @Test fun controlsAndTitlesAvoidCutoutsAcrossPaneWidthsAndCameraPositions() {
        for (width in listOf(200, 320, 393, 515, 755, 840, 1280)) {
            for (x in -60..width + 60 step 13) {
                val hole = Rect(x.toFloat(), 0f, x + 44f, 58f)
                val result = place(width, hole)
                val regions = listOf(
                    Rect(result.leadingX.toFloat(), result.rowY + 8f, result.leadingX + 48f, result.rowY + 56f),
                    Rect(result.trailingX.toFloat(), result.rowY + 8f, result.trailingX + 96f, result.rowY + 56f),
                    Rect(result.title.start.toFloat(), result.rowY + 20f, result.title.end.toFloat(), result.rowY + 44f),
                )
                regions.filter { it.width > 0 }.forEach {
                    assertFalse("width=$width camera=$hole target=$it", it.overlaps(hole))
                    assertTrue(it.left >= 0 && it.right <= width)
                }
                assertTrue(regions[0].right <= regions[1].left)
            }
        }
    }
}
