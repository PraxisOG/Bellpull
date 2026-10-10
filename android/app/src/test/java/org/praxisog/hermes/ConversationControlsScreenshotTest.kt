package org.praxisog.hermes

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.model.Message
import org.praxisog.hermes.model.ToolCall
import org.praxisog.hermes.model.ToolCallFunction
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class ConversationControlsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun app(branching: Boolean = false, branchError: org.praxisog.hermes.model.HermesError? = null) {
        val history = listOf(
            Message(id = 1, role = "user", content = "Find some inspiration for a quiet reading nook."),
            Message(id = 2, role = "assistant", content = "I'll look for a few ideas and check your saved room notes.", toolCalls = listOf(
                ToolCall(id = "search_1", function = ToolCallFunction("web_search", """{"query":"small reading nook natural light"}""")),
                ToolCall(id = "read_1", function = ToolCallFunction("read_file", """{"path":"notes/reading-room.txt"}""")),
            )),
            Message(id = 3, role = "tool", toolName = "web_search", toolCallId = "search_1",
                content = """{"results":[{"title":"A window-side retreat","snippet":"Pair a comfortable chair with warm light and a small shelf.","url":"https://example.com/reading-nook"}],"count":1}"""),
            Message(id = 4, role = "tool", toolName = "read_file", toolCallId = "read_1",
                content = """{"output":"Room notes: south-facing window, oak floor, room for one armchair and a low shelf."}"""),
            Message(id = 5, role = "assistant", content = "A soft armchair beside the window, a low oak shelf, and a warm reading lamp would suit the room beautifully."),
            Message(id = 6, role = "user", content = "Keep it simple, with a little greenery."),
            Message(id = 7, role = "assistant", content = "One trailing plant on the shelf would add life without making the corner feel crowded."),
        )
        val session = UiFixtures.sessions.first().copy(title = "A corner for reading",
            preview = history.last().content, messageCount = history.size, toolCallCount = 2)
        compose.setContent {
            HermesApp(sessions = UiState.Ready(listOf(session) + UiFixtures.sessions.drop(1)), history = UiState.Ready(history),
                turn = TurnState.Idle, openSessionId = "api_1_alpha",
                openSession = session, now = UiFixtures.NOW,
                onOpenSession = {}, onCloseSession = {}, onSend = {}, onStop = {},
                onRetrySessions = {}, onRetryHistory = {}, onNewSession = {},
                branchingSession = branching, branchError = branchError)
        }
    }

    private fun captureOverlay() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        captureScreenRoboImage()
    }

    private fun menu(label: String) {
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText(label).performClick()
    }

    @Test fun compactToolsCover() { app(); compose.onRoot().captureRoboImage() }
    @Test @Config(qualifiers = "w393dp-h850dp-night") fun compactToolsDark() { app(); compose.onRoot().captureRoboImage() }
    @Test @Config(qualifiers = "w840dp-h900dp") fun compactToolsUnfolded() { app(); compose.onRoot().captureRoboImage() }
    @Test @Config(qualifiers = "w1280dp-h900dp-night") fun compactToolsWideDark() { app(); compose.onRoot().captureRoboImage() }
    @Test fun overflowMenuCover() {
        app()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Coming soon").assertIsDisplayed()
        captureOverlay()
    }
    @Test fun toolDataCover() {
        app()
        compose.onNodeWithText("Web search").performClick()
        captureOverlay()
    }
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun toolDataUnfoldedDark() {
        app()
        compose.onNodeWithText("Read file").performClick()
        captureOverlay()
    }
    @Test fun promptHistoryCover() {
        app(); menu("Prompt history")
        compose.onNodeWithText("2 prompts · tap to jump").assertIsDisplayed()
        captureOverlay()
    }
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun promptHistoryUnfoldedDark() {
        app(); menu("Prompt history")
        compose.onNodeWithText("2 prompts · tap to jump").assertIsDisplayed()
        captureOverlay()
    }
    @Test fun searchCover() {
        app(); menu("Search in chat")
        compose.onNodeWithTag("chat-search").performTextInput("window")
        compose.onNodeWithText("3 matches · tap to jump").assertIsDisplayed()
        captureOverlay()
    }
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun searchUnfoldedDark() {
        app(); menu("Search in chat")
        compose.onNodeWithTag("chat-search").performTextInput("window")
        compose.onNodeWithText("3 matches · tap to jump").assertIsDisplayed()
        captureOverlay()
    }
    @Test fun messageActionsCover() {
        compose.setContent {
            HermesTheme {
                SessionDetailScreen(UiState.Ready(listOf(
                    Message(101, role = "user", content = "Tell me about this quiet corner."),
                    Message(102, role = "assistant", content = "A comfortable chair, soft light, and a small plant make a good start."),
                )), TurnState.Idle, {}, {}, {}, readingMessageId = 102, branchingEnabled = true)
            }
        }
        compose.onNodeWithContentDescription("Stop reading").assertIsDisplayed()
        compose.onRoot().captureRoboImage()
    }
    @Test @Config(qualifiers = "w755dp-h783dp-night") fun contextAtDeviceWidth() {
        app(); menu("Session info")
        compose.onNodeWithContentDescription("Close context").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().captureRoboImage()
    }
    @Test fun infoSheetCover() {
        app(); menu("Session info")
        compose.onNodeWithContentDescription("Close context").assertIsDisplayed()
        captureOverlay()
    }
    @Test fun branchFailureCover() {
        app(branchError = org.praxisog.hermes.model.HermesError.BadRequest(
            "Choose an assistant message from the current saved transcript.", "invalid_branch_message"))
        compose.onNodeWithText("Could not create branch").assertIsDisplayed()
        captureOverlay()
    }
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun creatingBranchUnfolded() {
        app(branching = true)
        compose.onNodeWithText("Creating branch…").assertIsDisplayed()
        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.onRoot().captureRoboImage()
    }
}
