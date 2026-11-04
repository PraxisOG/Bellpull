package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.model.Message
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class ThinkingGesturesTest {
    @get:Rule val compose = createComposeRule()
    private fun show() {
        compose.setContent { HermesTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                MessageBubble(Message(20, role = "assistant", content = "Saved answer"), true, {}) {
                    Column {
                        Text("Ordinary prose")
                        ThinkingBlock("Reasoning body available for selection", true)
                        Spacer(Modifier.height(1600.dp))
                        ThinkingBlock("Second reasoning block", true)
                    }
                }
            }
        } }
    }
    @Test fun tallBubbleDisclosuresToggleByTouchAndBodyTapCollapses() {
        show()
        compose.onAllNodesWithText("Thinking ▾")[0].performTouchInput { click() }
        compose.onNodeWithText("Reasoning body available for selection", useUnmergedTree = true)
            .performTouchInput { click() }
        compose.onNodeWithText("Reasoning body available for selection").assertDoesNotExist()
        compose.onAllNodesWithText("Thinking ▾")[1].performScrollTo().performTouchInput { click() }
        compose.onNodeWithText("Second reasoning block", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }
    @Test fun expandingThinkingAtTailOfLongResponseKeepsHeaderReachableDuringUpdates() {
        val longAnswer = "A long response still needs working controls. ".repeat(1000)
        val thought = "Review the current result.\n".repeat(80)
        var streaming by androidx.compose.runtime.mutableStateOf(TurnState.Streaming(longAnswer, parts = listOf(
            LivePart.Prose("answer", longAnswer), LivePart.Prose("thought", thought, true))))
        compose.setContent { HermesTheme {
            SessionDetailScreen(UiState.Ready(listOf(Message(1, role = "user", content = "Review this"))), streaming,
                { false }, {}, {}, denseChatWindow = true, reduceMotion = true)
        } }
        compose.onNodeWithText("Thinking ▾").assertIsDisplayed().performTouchInput { click() }
        compose.onNodeWithText("Thinking ▴").assertIsDisplayed()
        compose.runOnIdle { streaming = streaming.copy(parts = streaming.parts + LivePart.Prose("more", "More output. ".repeat(100))) }
        compose.onNodeWithText("Thinking ▴").assertIsDisplayed().performTouchInput { click() }
        compose.onNodeWithText("Thinking ▾").assertIsDisplayed()
    }
    @Test fun longPressStillOpensMessageActions() {
        show()
        compose.onNodeWithText("Ordinary prose", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithTag("message-action-popup").assertExists()
        compose.onNodeWithContentDescription("Copy message").performClick()
        compose.onNodeWithTag("message-action-popup").assertDoesNotExist()
    }
}
