package org.praxisog.hermes

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.praxisog.hermes.model.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class ChatDisplayUiTest {
    @get:Rule val compose = createComposeRule()
    private val parts = listOf(
        LivePart.Prose("thought1", "The agent is running and waiting for the checks.\nKeep the explanation short.", true),
        LivePart.Tools("tools1", listOf(LiveTool("cmd1", "terminal", "./check.sh", false), LiveTool("search1", "web_search", "release notes", false))),
        LivePart.Prose("thought2", "Reviewing the search result before responding.", true),
        LivePart.Tools("tools2", listOf(LiveTool("cmd2", "terminal", "git status", false))),
        LivePart.Prose("answer", "The checks passed. I’m reviewing the final changes."),
    )
    private val history = listOf(Message(1, role = "user", content = "Check the recent changes and keep me updated."))
    private fun render(options: ChatDisplayOptions = ChatDisplayOptions(thinkingPreview = true)) {
        compose.setContent { HermesTheme(palette = Palette.MAROON) {
            SessionDetailScreen(UiState.Ready(history), TurnState.Streaming("", parts = parts, statusLabel = "Thinking…"),
                { false }, {}, {}, denseChatWindow = true, reduceMotion = true, display = options)
        } }
    }
    @Test fun previewAndActivityCover() { render(); compose.onRoot().captureRoboImage() }
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun previewAndActivityUnfolded() { render(); compose.onRoot().captureRoboImage() }
    @Test fun hiddenActivityCover() { render(ChatDisplayOptions(hideTerminalCalls = true, hideThinking = true)); compose.onRoot().captureRoboImage() }
    @Test fun liveFiltersKeepProseSearchAndCountsAndCanBeReversed() {
        var options by mutableStateOf(ChatDisplayOptions(hideTerminalCalls = true, hideThinking = true))
        compose.setContent { HermesTheme {
            SessionDetailScreen(UiState.Ready(history), TurnState.Streaming("", parts = parts), { false }, {}, {},
                denseChatWindow = true, reduceMotion = true, display = options)
        } }
        compose.onNodeWithText("Terminal").assertDoesNotExist()
        compose.onNodeWithText("Thinking ▾").assertDoesNotExist()
        compose.onNodeWithText("Web search").assertIsDisplayed()
        compose.onNodeWithText("2 thinking blocks · 2 terminal · 1 search").assertIsDisplayed()
        compose.onNodeWithText("The checks passed. I’m reviewing the final changes.").assertIsDisplayed()
        compose.runOnIdle { options = options.copy(hideThinking = false, hideTerminalCalls = false, activityCounts = false) }
        compose.onAllNodesWithText("Terminal").assertCountEquals(2)
        compose.onAllNodesWithText("Thinking ▾").assertCountEquals(2)
        compose.onNodeWithTag("response-activity-counts", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(5, parts.size)
    }
    @Test fun previewIsOnlyCollapsedAndFullThinkingRemainsAvailable() {
        render()
        compose.onAllNodesWithTag("thinking-preview", useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithText("Thinking ▾")[0].performTouchInput { click() }
        compose.onNodeWithText((parts[0] as LivePart.Prose).text, useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithTag("thinking-preview", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("Thinking ▴").performTouchInput { click() }
        compose.onAllNodesWithTag("thinking-preview", useUnmergedTree = true).assertCountEquals(2)
    }
    @Test fun historyFiltersRespectAliasesAndKeepFailureAndOriginalBranchTarget() {
        val messages = listOf(Message(1, role = "user", content = "Test"),
            Message(2, role = "assistant", content = "Original prose", toolCalls = listOf(ToolCall(id = "cmd", function = ToolCallFunction("terminal")))),
            Message(3, role = "tool", toolCallId = "cmd", content = """{"exit_code":0}"""),
            Message(4, role = "tool", toolName = "terminal", content = """{"exit_code":1,"error":"failed"}"""),
            Message(5, role = "assistant", reasoningContent = "Historical thought"))
        var branched: Message? = null
        compose.setContent { HermesTheme {
            SessionDetailScreen(UiState.Ready(messages), TurnState.Idle, { false }, {}, {}, denseChatWindow = true,
                branchingEnabled = true, onBranchMessage = { branched = it }, display = ChatDisplayOptions(true, true))
        } }
        compose.onAllNodesWithText("Terminal").assertCountEquals(1)
        compose.onNodeWithText("Failed").assertIsDisplayed()
        compose.onNodeWithText("Thinking ▾").assertDoesNotExist()
        compose.onNodeWithText("Original prose", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Branch into new chat").performClick()
        assertEquals(5, branched!!.id); assertEquals("Original prose", branched!!.content)
        assertEquals("Historical thought", messages.last().reasoningContent)
    }
    @Test fun hidingTerminalDoesNotCollapseAGroupOfRefusals() {
        val messages = listOf(Message(1, role = "user", content = "Check")) + (2..6).map {
            Message(it, role = "tool", toolName = "terminal", content = """{"status":"blocked"}""")
        }
        compose.setContent { HermesTheme {
            SessionDetailScreen(UiState.Ready(messages), TurnState.Idle, { false }, {}, {},
                display = ChatDisplayOptions(hideTerminalCalls = true))
        } }
        compose.onAllNodesWithText("Refused").assertCountEquals(5)
    }
    @Test fun historyProjectionDoesNotLoseCountsForSavedThinking() {
        val messages = listOf(Message(1, role = "user", content = "Check"),
            Message(2, role = "assistant", content = "Saved answer", reasoningContent = "Saved thought"))
        val turn = TurnState.Streaming("Saved answer", reasoning = "Saved thought\nNew thought", parts = listOf(
            LivePart.Prose("thought1", "Saved thought", true), LivePart.Prose("answer", "Saved answer"),
            LivePart.Tools("tool", listOf(LiveTool("terminal", "terminal", "check"))), LivePart.Prose("thought2", "New thought", true)))
        compose.setContent { HermesTheme {
            SessionDetailScreen(UiState.Ready(messages), turn, { false }, {}, {}, denseChatWindow = true, reduceMotion = true,
                runSubmission = RunSubmission("request", "s", "Check", 1.0, 1, emptySet(), runId = "run"),
                display = ChatDisplayOptions(hideTerminalCalls = true, hideThinking = true))
        } }
        compose.onNodeWithText("2 thinking blocks · 1 terminal").assertIsDisplayed()
        compose.onAllNodesWithText("Saved answer").assertCountEquals(1)
        compose.onNodeWithText("Thinking ▾").assertDoesNotExist()
        assertEquals(4, turn.parts.size)
    }
    @Test fun settingsPersistIndependentlyAndKeepPreviewChoiceWhenThinkingIsHidden() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("bellpull_presentation", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = BellpullSettings(context)
        compose.setContent { HermesTheme { SettingsWorkspace(settings.value, settings::update) } }
        compose.onNodeWithText("Display").performClick()
        for (title in listOf("Hide terminal calls", "Thinking previews", "Hide thinking", "Activity counts"))
            compose.onNodeWithContentDescription(title).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Thinking previews").performScrollTo().assertIsNotEnabled().assertIsOn()
        val restored = BellpullSettings(context).value
        assertTrue(restored.hideTerminalCalls); assertTrue(restored.hideThinking); assertTrue(restored.thinkingPreview)
        assertFalse(restored.activityCounts); assertTrue(restored.collapseThinking)
        assertEquals(Palette.NEUTRAL, restored.palette)
        compose.onRoot().captureRoboImage()
    }
}
