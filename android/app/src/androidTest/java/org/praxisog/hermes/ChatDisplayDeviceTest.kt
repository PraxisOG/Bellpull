package org.praxisog.hermes

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.model.Message

/** Rendering and real touch only; no account, network or inference. */
@RunWith(AndroidJUnit4::class)
class ChatDisplayDeviceTest {
    private val ui = DeviceFixtureUi
    @Test fun veryTallResponseKeepsThinkingClickableAndDoesNotJumpDuringUpdates() = ui.withWindows {
        val answer = "A long response still needs working controls. ".repeat(1000)
        val reasoning = "Review the current result.\n".repeat(80)
        var turn by mutableStateOf(TurnState.Streaming(answer, parts = listOf(
            LivePart.Prose("answer", answer), LivePart.Prose("thought", reasoning, true))))
        ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
            scene.onActivity { activity -> activity.setContent { HermesTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    SessionDetailScreen(UiState.Ready(listOf(Message(1, role = "user", content = "Review this"))), turn,
                        { false }, {}, {}, denseChatWindow = true, reduceMotion = true)
                }
            } } }
            ui.tapText("Thinking ▾")
            ui.await("Long response swallowed the Thinking tap") { ui.text("Thinking ▴") != null }
            ui.main { turn = turn.copy(parts = turn.parts + LivePart.Prose("more", "More output. ".repeat(100))) }
            ui.screenshot("chat-long-response-thinking-open")
            ui.tapText("Thinking ▴")
            ui.await("Thinking did not collapse") { ui.text("Thinking ▾") != null }
            ui.screenshot("chat-long-response-thinking-closed")
        }
    }
    @Test fun hiddenCallsKeepActivityCountsAndCanBeRestored() = ui.withWindows {
        var display by mutableStateOf(ChatDisplayOptions(hideTerminalCalls = true, hideThinking = true, thinkingPreview = true))
        val parts = listOf(LivePart.Prose("thought", "The agent is running and waiting for the checks.", true),
            LivePart.Tools("tools", listOf(LiveTool("cmd", "terminal", "./check.sh"), LiveTool("search", "web_search", "release notes"))),
            LivePart.Prose("answer", "Your response stays visible."))
        ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
            scene.onActivity { activity -> activity.setContent { HermesTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    SessionDetailScreen(UiState.Ready(listOf(Message(1, role = "user", content = "Check the changes"))),
                        TurnState.Streaming("", parts = parts, statusLabel = "Working…"), { false }, {}, {},
                        denseChatWindow = true, reduceMotion = true, display = display)
                }
            } } }
            ui.await("Reply did not render") { ui.text("Your response stays visible.") != null }
            assertNull(ui.text("Terminal"))
            ui.await("Counts disappeared with hidden calls") { ui.find { it.text?.contains("1 thinking block · 1 terminal · 1 search") == true } != null }
            ui.screenshot("chat-hidden-activity")
            ui.main { display = display.copy(hideTerminalCalls = false, hideThinking = false) }
            ui.await("Terminal was not restored") { ui.find { it.text?.contains("Terminal") == true } != null }
            ui.await("Preview was not restored") { ui.find { it.text?.contains("— The agent is running") == true } != null }
            ui.screenshot("chat-thinking-previews")
        }
    }
}
