package org.praxisog.hermes

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.model.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class ResponseBubbleScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val history = listOf(
        Message(1, role = "user", content = "Check my reading nook plan."),
        Message(2, role = "assistant", content = "I'll check the plan and find a few ideas."),
        Message(3, role = "tool", toolName = "terminal", content = """{"output":"notes found","exit_code":0}"""),
        Message(4, role = "tool", toolName = "web_search", content = """{"results":[{"title":"Reading nook ideas","url":"https://example.com/nook"}]}"""),
        Message(5, role = "assistant", content = "A window seat would fit. I'll check your room notes."),
        Message(6, role = "tool", toolName = "read_file", content = """{"output":"South-facing window; room for a chair."}"""),
        Message(7, role = "assistant", content = "Use a soft chair, a small lamp, and one trailing plant.")
    )
    private val artifact = Artifact("plan", "s", "reading-nook.png", "image/png", 64, "0".repeat(64), 1.0, "agent", messageId = 7)
    private fun edgeToEdge(bubbleId: Int, tag: String, bottom: Boolean = false) {
        val bubble = compose.onNodeWithTag("bubble-$bubbleId").fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertEquals(bubble.left, row.left, .5f)
        assertEquals(bubble.right, row.right, .5f)
        if (bottom) assertEquals(bubble.bottom, row.bottom, .5f)
    }
    private fun show() {
        compose.setContent { HermesTheme { SessionDetailScreen(UiState.Ready(history), TurnState.Idle, {}, {}, {},
            denseChatWindow = true, artifacts = listOf(artifact)) } }
        compose.onAllNodes(hasContentDescription("Hermes response")).assertCountEquals(1)
        listOf("Terminal", "Web search", "Read file", "reading-nook.png").forEach {
            compose.onNode(hasText(it) and hasAnyAncestor(hasTestTag("bubble-7"))).assertIsDisplayed()
        }
        compose.onNodeWithText(history[1].content!!).assertIsDisplayed()
        compose.onNodeWithText(history.last().content!!).assertIsDisplayed()
        edgeToEdge(7, "message-3")
        edgeToEdge(7, "message-6")
        edgeToEdge(7, "artifact-plan", bottom = true)
        captureScreenRoboImage()
    }
    @Test fun oneResponseCover() {
        show()
        compose.onNodeWithText(history.last().content!!).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Copy message").performClick()
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(history.filter { it.role == "assistant" }.joinToString("\n\n") { it.content!! },
            clipboard.primaryClip!!.getItemAt(0).text.toString())
        compose.onNodeWithText("Read file").performClick()
        compose.onNodeWithText("Copy data").assertIsDisplayed()
    }
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun oneResponseUnfoldedDark() = show()
    private fun middleArtifactAndFinalTool() {
        compose.setContent { HermesTheme { SessionDetailScreen(UiState.Ready(history.dropLast(1)), TurnState.Idle, {}, {}, {},
            denseChatWindow = true, artifacts = listOf(artifact.copy(messageId = 2))) } }
        edgeToEdge(5, "artifact-plan")
        edgeToEdge(5, "message-6", bottom = true)
        captureScreenRoboImage()
        compose.onNodeWithText("Read file").performClick()
        compose.onNodeWithText("Copy data").assertIsDisplayed()
    }
    @Test fun middleArtifactAndFinalToolCover() = middleArtifactAndFinalTool()
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun middleArtifactAndFinalToolUnfolded() = middleArtifactAndFinalTool()
    @Test fun searchCanRevealALateSegmentInsideATallResponse() {
        val long = history.map { if (it.id == 5) it.copy(content = "Room details.\n".repeat(100)) else it }
        var nav by androidx.compose.runtime.mutableStateOf<ChatNavigation?>(ChatNavigation.SEARCH)
        compose.setContent { HermesTheme { SessionDetailScreen(UiState.Ready(long), TurnState.Idle, {}, {}, {},
            navigation = nav, onDismissNavigation = { nav = null }, denseChatWindow = true) } }
        compose.onNodeWithTag("chat-search").performTextInput("trailing plant")
        compose.onNodeWithTag("jump-to-7").performClick()
        compose.onNodeWithText(history.last().content!!).assertIsDisplayed()
    }
}
