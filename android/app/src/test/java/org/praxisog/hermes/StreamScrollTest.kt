package org.praxisog.hermes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToIndex
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
                onSend = {},
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

        compose.onNodeWithText("THE NEWEST LINE", substring = true).assertExists()
    }

    @Test fun scrollingUpToReadIsNotYankedBackByTheNextToken() {
        // The second half of the bug: following ran on every delta regardless of where the
        // user was, so reading earlier messages mid-stream was impossible.
        var turn by mutableStateOf<TurnState>(TurnState.Streaming("start", canStop = true))
        compose.setContent {
            SessionDetailScreen(
                history = UiState.Ready(longHistory),
                turn = turn,
                onSend = {},
                onStop = {},
                onRetry = {},
            )
        }
        compose.waitForIdle()

        // Deliberately scroll away from the tail. Scrolled by index, because a LazyColumn
        // only composes what is visible — the early messages are not in the tree to target
        // by text until the list has been scrolled to them.
        compose.onNode(hasScrollAction()).performScrollToIndex(0)
        compose.waitForIdle()

        // A further delta arrives.
        turn = TurnState.Streaming("start and then considerably more text arrives", canStop = true)
        compose.waitForIdle()

        // Still where the user put it.
        compose.onNodeWithText("Message number 1").assertExists()
    }
}
