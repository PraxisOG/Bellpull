package org.praxisog.hermes

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.toPixelMap
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.model.Message
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class WorkingRowScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private fun show() {
        var parts by mutableStateOf(listOf<LivePart>(LivePart.Prose("thinking", "Checking the room notes.", true)))
        compose.setContent { HermesTheme {
            SessionDetailScreen(UiState.Ready(listOf(Message(1, role = "user", content = "Check my reading nook plan."))),
                TurnState.Streaming("", parts = parts, canStop = true, statusLabel = "Working…"), { false }, {}, {},
                collapseThinking = false, denseChatWindow = true, reduceMotion = true)
        } }
        // The same selected chat grows as events arrive; no navigation or reload.
        compose.runOnIdle {
            parts = listOf(LivePart.Prose("thinking", "Checking the room notes. The window has space for a chair.", true),
                LivePart.Tools("tools", listOf(LiveTool("file", "read_file", "room-notes.md"))),
                LivePart.Prose("answer", "A window seat would fit nicely."))
        }
        compose.onNodeWithText("Checking the room notes. The window has space for a chair.").assertIsDisplayed()
        compose.onNodeWithText("Read file").assertIsDisplayed()
        compose.onNodeWithText("A window seat would fit nicely.").assertIsDisplayed()
        val bubble = compose.onNodeWithTag("bubble-${Int.MIN_VALUE}").fetchSemanticsNode().boundsInRoot
        val status = compose.onNodeWithTag("stream-tail").fetchSemanticsNode().boundsInRoot
        assertEquals(bubble.left, status.left, .5f)
        assertEquals(bubble.right, status.right, .5f)
        assertEquals(bubble.bottom, status.bottom, .5f)
        compose.onNodeWithTag("working-indicator", useUnmergedTree = true).assertIsDisplayed()
        captureScreenRoboImage()
    }
    @Test fun workingInsideResponseCover() = show()
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun workingInsideResponseUnfolded() = show()

    @Test fun workingIndicatorAnimatesWithoutChangingRowSize() {
        compose.mainClock.autoAdvance = false
        compose.setContent { HermesTheme { WorkingRow("Working…", reduceMotion = false) } }
        compose.mainClock.advanceTimeByFrame()
        val before = compose.onNodeWithTag("stream-tail").fetchSemanticsNode().boundsInRoot
        fun indicatorColor(): androidx.compose.ui.graphics.Color {
            val pixels = compose.onNodeWithTag("working-indicator", useUnmergedTree = true).captureToImage().toPixelMap()
            return pixels[pixels.width / 2, pixels.height / 2]
        }
        val firstColor = indicatorColor()
        compose.mainClock.advanceTimeBy(800)
        assertNotEquals(firstColor, indicatorColor())
        assertEquals(before, compose.onNodeWithTag("stream-tail").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("Working…").assertIsDisplayed()
    }
}
