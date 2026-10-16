package org.praxisog.hermes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.model.Message
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scroll behaviour during a stream. Reported from the phone, not caught here: the transcript
 * jumped back to the top of the reply roughly every second while a turn streamed.
 *
 * Screenshot tests could never have found it — they capture one frame, and this was a bug in
 * how the view moved between frames.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class StreamScrollTest {

    @get:Rule val compose = createComposeRule()

    /** Enough history to make the list genuinely scrollable. */
    private val longHistory = (1..25).map {
        Message(id = it, role = if (it % 2 == 0) "assistant" else "user", content = "Message number $it")
    }

    @Test fun growingReplyKeepsItsNewestTextOnScreen() {
        // The bug: scrollToItem(last) pins the *top* of the reply, so as it grew the newest
        // text marched off the bottom. The end of the reply must stay visible.
        var turn by mutableStateOf<TurnState>(TurnState.Streaming("start", canStop = true))
        compose.setContent {
            SessionDetailScreen(
                history = UiState.Ready(longHistory),
                turn = turn,
                onSend = { false },
                onStop = {},
                onRetry = {},
            )
        }
        compose.waitForIdle()

        // Grow the reply well past a screenful.
        turn = TurnState.Streaming(
            (1..60).joinToString("\n") { "line $it" } + "\nTHE NEWEST LINE",
            canStop = true,
        )
        compose.waitForIdle()

        compose.onNodeWithTag("stream-tail").assertIsDisplayed()
    }

    @Test fun toolAndThinkingGrowthAlsoFollowWithoutAnyAssistantText() {
        var turn by mutableStateOf<TurnState>(TurnState.Streaming("", canStop = true))
        compose.setContent {
            HermesTheme {
                SessionDetailScreen(UiState.Ready(longHistory), turn, { false }, {}, {}, collapseThinking = false, reduceMotion = true)
            }
        }
        compose.waitForIdle()
        turn = TurnState.Streaming("", canStop = true, parts = (1..12).flatMap { n -> listOf(
            LivePart.Prose("thinking-$n", "Thinking through source $n\n".repeat(6), thinking = true),
            LivePart.Tools("tools-$n", listOf(LiveTool("tool-$n", "web_search", "Source $n", true)))) })
        compose.waitForIdle()
        compose.onNodeWithTag("stream-tail").assertIsDisplayed()
    }

    @Test fun scrollingUpToReadIsNotYankedBackByTheNextToken() {
        // The second half of the bug: following ran on every delta regardless of where the
        // user was, so reading earlier messages mid-stream was impossible.
        var turn by mutableStateOf<TurnState>(TurnState.Streaming("start", canStop = true))
        compose.setContent {
            SessionDetailScreen(
                history = UiState.Ready(longHistory),
                turn = turn,
                onSend = { false },
                onStop = {},
                onRetry = {},
            )
        }
        compose.waitForIdle()

        // Exercise the actual reader gesture, so the test distinguishes input from layout growth.
        compose.onNodeWithTag("transcript").performTouchInput { swipeDown() }
        compose.waitForIdle()

        // A further delta arrives.
        turn = TurnState.Streaming("start and then considerably more text arrives", canStop = true)
        compose.waitForIdle()

        // Still where the user put it.
        compose.onNodeWithText("Jump to latest ↓").assertIsDisplayed()
        compose.onNodeWithTag("stream-tail").assertIsNotDisplayed()
        compose.onNodeWithText("Jump to latest ↓").performClick()
        compose.onNodeWithTag("stream-tail").assertIsDisplayed()
        turn = TurnState.Streaming((1..90).joinToString("\n") { "More content $it" }, canStop = true)
        compose.waitForIdle()
        compose.onNodeWithTag("stream-tail").assertIsDisplayed()
    }

    @Test fun manuallyReturningToBottomAndDraggingPastItResumesFollowing() {
        var turn by mutableStateOf<TurnState>(TurnState.Streaming("start", canStop = true))
        compose.setContent {
            SessionDetailScreen(UiState.Ready(longHistory), turn, { false }, {}, {})
        }
        compose.waitForIdle()
        compose.onNodeWithTag("transcript").performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.onNodeWithTag("stream-tail").assertIsNotDisplayed()
        // Reaching the end often leaves part of the gesture unconsumed (overscroll).
        repeat(4) {
            compose.onNodeWithTag("transcript").performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        compose.onNodeWithTag("stream-tail").assertIsDisplayed()
        compose.runOnIdle {
            turn = TurnState.Streaming((1..75).joinToString("\n") { "New line $it" }, canStop = true)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("stream-tail").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w840dp-h900dp") fun shrinkingPaneKeepsLatestReplyVisibleWithoutNewTokens() {
        var width by mutableStateOf(754)
        val history = (1..12).map { Message(it, role = if (it % 2 == 0) "assistant" else "user",
            content = "A saved paragraph that rewraps when the context panel slides into the window. ".repeat(3)) }
        compose.setContent {
            HermesTheme {
                SessionDetailScreen(UiState.Ready(history), TurnState.Idle, { false }, {}, {}, modifier = Modifier.width(width.dp))
            }
        }
        for (next in listOf(680, 570, 450, 380, 313)) {
            compose.runOnIdle { width = next }
            compose.waitForIdle()
        }
        compose.onNodeWithTag("message-12").assertIsDisplayed()
        compose.onNodeWithText("Jump to latest ↓").assertDoesNotExist()
    }
}
