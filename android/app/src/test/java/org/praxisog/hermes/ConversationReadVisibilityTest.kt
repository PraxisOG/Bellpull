package org.praxisog.hermes

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.model.Message
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class ConversationReadVisibilityTest {
    @get:Rule val compose = createComposeRule()
    private val receipts = ConversationReadState()
    private var foreground by mutableStateOf(true)
    private var messages by mutableStateOf((1..24).map { Message(it,
        role = if (it % 2 == 0) "assistant" else "user", content = "Message number $it") })
    private fun show() {
        compose.setContent {
            HermesTheme {
                SessionDetailScreen(UiState.Ready(messages), TurnState.Idle, { false }, {}, {},
                    readEnabled = foreground, onReadThrough = { receipts.readThrough("chat", it) })
            }
        }
        compose.waitForIdle()
    }
    @Test fun readingOldMessagesDoesNotConsumeNewReplyUntilJumpToLatest() {
        show()
        compose.onNodeWithTag("transcript").performTouchInput { swipeDown() }
        compose.runOnIdle {
            receipts.completed("chat", 25)
            messages = messages + Message(25, role = "assistant", content = "The unseen new reply")
        }
        compose.waitForIdle()
        assertEquals(setOf("chat"), receipts.unreadIds)
        compose.onNodeWithText("Jump to latest ↓").performClick()
        compose.waitForIdle()
        assertTrue(receipts.unreadIds.isEmpty())
    }
    @Test fun backgroundWindowCannotConsumeReadReceipt() {
        foreground = false
        receipts.completed("chat", 24)
        show()
        assertEquals(setOf("chat"), receipts.unreadIds)
        compose.runOnIdle { foreground = true }
        compose.waitForIdle()
        assertTrue(receipts.unreadIds.isEmpty())
    }
}
