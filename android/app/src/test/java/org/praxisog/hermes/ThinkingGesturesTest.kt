package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
    @Test fun longPressStillOpensMessageActions() {
        show()
        compose.onNodeWithText("Ordinary prose", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithTag("message-action-popup").assertExists()
        compose.onNodeWithContentDescription("Copy message").performClick()
        compose.onNodeWithTag("message-action-popup").assertDoesNotExist()
    }
}
