package org.praxisog.hermes

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.model.Session
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class ConversationRefreshTest {
    @get:Rule val compose = createComposeRule()
    private var refreshing by mutableStateOf(false)
    private var requests = 0
    private var opened: String? = null
    private val sessions = (1..40).map { Session("chat-$it", title = "Conversation $it", lastActive = 1000.0 - it) }
    private fun show(state: UiState<List<Session>> = UiState.Ready(sessions)) {
        compose.setContent { HermesTheme {
            SessionListScreen(state, 1000.0, null, { opened = it }, { requests++; refreshing = true }, {},
                onExpandedSearch = {}, refreshing = refreshing)
        } }
    }
    private fun pull() = compose.onNodeWithTag("conversations-refresh").performTouchInput {
        swipeDown(startY = height * .15f, endY = height * .85f, durationMillis = 500)
    }
    private fun captureRefreshing() {
        compose.mainClock.autoAdvance = false
        refreshing = true
        show()
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().captureRoboImage()
    }
    @Test fun refreshingCover() = captureRefreshing()
    @Test @Config(qualifiers = "w840dp-h900dp") fun refreshingUnfolded() = captureRefreshing()
    @Test fun pullRefreshesOnceKeepsRowsUsableAndAllowsAnotherRefreshAfterCompletion() {
        show()
        pull()
        assertEquals(1, requests)
        compose.onNodeWithText("Conversation 1").assertIsDisplayed()
        pull()
        assertEquals(1, requests)
        compose.onNodeWithText("Conversation 1").performClick()
        assertEquals("chat-1", opened)
        compose.runOnIdle { refreshing = false }
        pull()
        assertEquals(2, requests)
    }
    @Test fun scrollingBackThroughConversationsDoesNotRefreshUntilTheTop() {
        show()
        compose.onNodeWithTag("conversations-list").performScrollToIndex(25)
        pull()
        assertEquals(0, requests)
    }
    @Test fun emptyListCanBePulledToRefresh() {
        show(UiState.Ready(emptyList()))
        pull()
        assertEquals(1, requests)
        compose.onNodeWithText("Room for a new idea").assertIsDisplayed()
    }
    @Test fun failedListCanBePulledToRetry() {
        show(UiState.Failed(UiFixtures.authFailure))
        pull()
        assertEquals(1, requests)
        compose.onNodeWithText("Try again").assertIsNotEnabled()
    }
    @Test fun initialLoadingDoesNotStartAnotherRequest() {
        show(UiState.Loading)
        pull()
        assertEquals(0, requests)
    }
    @Test fun appRoutesPullToSessionRefreshAndKeepsTheSourceFilter() {
        compose.setContent { HermesApp(UiState.Ready(sessions), UiState.Ready(emptyList()), TurnState.Idle,
            null, null, 1000.0, {}, {}, { false }, {}, { requests++; refreshing = true }, {}, {},
            sessionSource = "cli", onSessionSourceChange = {}, refreshingSessions = refreshing, foreground = false) }
        pull()
        assertEquals(1, requests)
        compose.onNodeWithTag("conversation-filters").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "cli"))
    }
}
