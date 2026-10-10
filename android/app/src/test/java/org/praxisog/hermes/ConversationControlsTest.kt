package org.praxisog.hermes

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.praxisog.hermes.model.Message
import org.praxisog.hermes.model.ToolCall
import org.praxisog.hermes.model.ToolCallFunction

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class ConversationControlsTest {
    @get:Rule val compose = createComposeRule()

    private val longHistory = (1..35).map {
        Message(id = it, role = if (it % 2 == 1) "user" else "assistant",
            content = if (it == 3) "Find the blueprints for the studio." else "Message $it. " + "Some conversation text. ".repeat(5))
    }

    private fun app(
        creating: Boolean = false,
        history: UiState<List<Message>> = UiState.Ready(longHistory),
        onNew: () -> Unit = {},
        openId: () -> String = { "api_1_alpha" },
        turn: () -> TurnState = { TurnState.Idle },
        createError: org.praxisog.hermes.model.HermesError? = null,
    ) {
        compose.setContent {
            HermesApp(sessions = UiState.Ready(UiFixtures.sessions), history = history,
                turn = turn(), openSessionId = openId(), openSession = UiFixtures.sessions.first(), now = UiFixtures.NOW,
                onOpenSession = {}, onCloseSession = {}, onSend = {}, onStop = {}, onRetrySessions = {},
                onRetryHistory = {}, onNewSession = onNew, creatingSession = creating, createError = createError)
        }
    }

    private fun menu(label: String) {
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText(label).performClick()
    }

    @Test fun newlyOpenedBranchIsVisibleWhenItArrivesAboveTheOldListAnchor() {
        val sessions = mutableStateOf(UiFixtures.sessions)
        val selected = mutableStateOf(UiFixtures.sessions.first().id)
        val branch = UiFixtures.sessions.first().copy(id = "fresh-branch", title = "Newly created branch")
        compose.setContent {
            HermesTheme {
                SessionListScreen(UiState.Ready(sessions.value), UiFixtures.NOW, selected.value,
                    onOpen = {}, onRetry = {}, onNewSession = {}, showNewChatButton = false)
            }
        }
        compose.waitForIdle()
        // Opening the response and receiving the refreshed sidebar are separate requests.
        compose.runOnIdle { selected.value = branch.id }
        compose.waitForIdle()
        compose.runOnIdle { sessions.value = listOf(branch) + sessions.value }
        compose.onNodeWithText("Newly created branch").assertIsDisplayed()
    }

    @Test fun newChatButtonWorksAndShareOpensAndroidChooser() {
        var starts = 0
        app(onNew = { starts++ })
        compose.onNodeWithTag("toolbar-new-chat").performClick()
        assertEquals(1, starts)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Share conversation").assertIsEnabled().performClick()
        val started = org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_CHOOSER, started.action)
        @Suppress("DEPRECATION") val shared = started.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
        assertEquals(android.content.Intent.ACTION_SEND, shared.action)
        org.junit.Assert.assertTrue(shared.getStringExtra(android.content.Intent.EXTRA_TEXT)!!.contains("Message 1."))
    }

    @Test fun newChatIsDisabledWhileCreatingAndFailureIsVisible() {
        app(creating = true, createError = UiFixtures.authFailure)
        compose.onNodeWithTag("toolbar-new-chat").assertIsNotEnabled()
        compose.onNodeWithText("Could not start a new chat.", substring = true).assertIsDisplayed()
    }

    @Test fun promptJumpSurvivesStreamingAndLatestResumesFollowing() {
        val turn = mutableStateOf<TurnState>(TurnState.Streaming("Working", canStop = true))
        app(turn = { turn.value })
        menu("Prompt history")
        compose.onNodeWithTag("jump-to-1").performClick()
        compose.onNodeWithTag("message-1").assertIsDisplayed()
        compose.runOnIdle { turn.value = TurnState.Streaming("Working on a much longer answer.", canStop = true) }
        compose.onNodeWithTag("message-1").assertIsDisplayed()
        compose.onNodeWithText("Jump to latest ↓").performClick()
        compose.onNodeWithText("Working on a much longer answer.").assertIsDisplayed()
        compose.onNodeWithText("Jump to latest ↓").assertDoesNotExist()
    }

    @Test fun searchIsCaseInsensitiveAndJumpsToTheMatchingMessage() {
        app()
        menu("Search in chat")
        compose.onNodeWithTag("chat-search").performTextInput("BLUEPRINTS")
        compose.onNodeWithText("1 match · tap to jump").assertIsDisplayed()
        compose.onNodeWithTag("chat-search").performTextReplacement("no such message")
        compose.onNodeWithText("No matching messages.").assertIsDisplayed()
        compose.onNodeWithTag("chat-search").performTextReplacement("BLUEPRINTS")
        compose.onNodeWithTag("jump-to-3").performClick()
        compose.onNodeWithTag("message-3").assertIsDisplayed()
    }

    @Test fun searchAndPromptHistoryAreUnavailableUntilHistoryLoads() {
        app(history = UiState.Loading)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Search in chat").assertIsNotEnabled()
        compose.onNodeWithText("Prompt history").assertIsNotEnabled()
    }

    @Test fun openingInfoPreservesDraftAndChangingSessionClearsIt() {
        val id = mutableStateOf("api_1_alpha")
        app(openId = { id.value })
        compose.onNodeWithContentDescription("Message Hermes").performTextInput("Keep this draft")
        menu("Session info")
        compose.onNodeWithText("Context").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close context").performClick()
        compose.onNodeWithText("Keep this draft").assertIsDisplayed()
        compose.runOnIdle { id.value = "api_2_beta" }
        compose.onNodeWithText("Keep this draft").assertDoesNotExist()
    }

    @Test fun toolPopupPreservesUnknownOutputAndMatchesInputByCallId() {
        val input = """{"query":"tiny houses"}"""
        val output = """{"results":[{"title":"A small home","url":"https://example.com/home"}],"extra":{"count":1}}"""
        val history = listOf(
            Message(id = 1, role = "assistant", toolCalls = listOf(
                ToolCall(callId = "call_web", function = ToolCallFunction("web_search", input)))),
            Message(id = 2, role = "tool", toolCallId = "call_web", content = output),
        )
        app(history = UiState.Ready(history))
        compose.onNodeWithTag("message-1").assertDoesNotExist()
        compose.onNodeWithText(output).assertDoesNotExist()
        compose.onNodeWithText("Web search").performClick()
        compose.onNodeWithText(input).assertIsDisplayed()
        compose.onNodeWithText(output).assertIsDisplayed()
        compose.onNodeWithText("Copy data").performClick()
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("Input\n$input\n\nOutput\n$output", clipboard.primaryClip!!.getItemAt(0).text.toString())
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText(output).assertDoesNotExist()
    }

    @Test fun callWithoutAResultStillShowsItsInput() {
        val input = """{"path":"notes.txt"}"""
        app(history = UiState.Ready(listOf(Message(id = 1, role = "assistant", toolCalls = listOf(
            ToolCall(id = "missing", function = ToolCallFunction("read_file", input)),
        )))))
        compose.onNodeWithText("Read file").performClick()
        compose.onNodeWithText(input).assertIsDisplayed()
        compose.onNodeWithText("No output was returned.").assertIsDisplayed()
    }

    @Test fun refusalAndFlaggedStatusStayVisibleWhilePayloadIsCollapsed() {
        app(history = UiState.Ready(UiFixtures.history))
        compose.onNodeWithText("Refused").assertIsDisplayed()
        compose.onNodeWithText("Flagged · allowed").assertIsDisplayed()
        compose.onNodeWithText(UiFixtures.history[4].content!!).assertDoesNotExist()
    }

    @Test fun liveToolHasFriendlyNameAndPreviewOnlyInPopup() {
        app(history = UiState.Ready(emptyList()), turn = {
            TurnState.Streaming("Reading the plan.", runningTool = "/workspace/plan.txt", canStop = true, runningToolName = "read_file")
        })
        compose.onNodeWithText("Read file").assertIsDisplayed()
        compose.onNodeWithText("Running…").assertIsDisplayed()
        compose.onNodeWithText("/workspace/plan.txt").assertDoesNotExist()
        compose.onNodeWithText("Read file").performClick()
        compose.onNodeWithText("Live preview").assertIsDisplayed()
        compose.onNodeWithText("/workspace/plan.txt").assertIsDisplayed()
    }

    @Test fun longPressCopiesTheWholeUserMessage() {
        app(history = UiState.Ready(listOf(Message(101, role = "user", content = "Copy this whole message 🌳"))))
        compose.onNodeWithTag("message-101").performTouchInput { longClick() }
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("Copy this whole message 🌳", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun responseToolbarCopiesReadsAndBranchesTheSelectedMessage() {
        val reply = Message(102, role = "assistant", content = "A complete answer.")
        var read: Message? = null
        var branch: Message? = null
        compose.setContent {
            HermesTheme {
                SessionDetailScreen(UiState.Ready(listOf(reply)), TurnState.Idle, {}, {}, {},
                    onReadMessage = { read = it }, onBranchMessage = { branch = it }, branchingEnabled = true)
            }
        }
        compose.onNodeWithContentDescription("Copy message").performClick()
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(reply.content, clipboard.primaryClip!!.getItemAt(0).text.toString())
        compose.onNodeWithContentDescription("Read message").performClick()
        assertEquals(reply, read)
        compose.onNodeWithContentDescription("Branch into new chat").performClick()
        assertEquals(reply, branch)
    }
}
