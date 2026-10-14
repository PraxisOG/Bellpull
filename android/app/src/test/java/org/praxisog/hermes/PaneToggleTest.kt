package org.praxisog.hermes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behaviour of the pane toggles. The screenshots prove the collapsed layouts render; these
 * prove the toggles actually collapse and restore, and that the panes can't get stranded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w840dp-h900dp")
class PaneToggleTest {

    @get:Rule val compose = createComposeRule()

    /** Appears only in the session list, so it is a clean probe for the list pane. */
    private val listOnlyText = "Widget cold-start timings"

    /** Appears only in the context pane. */
    private val contextOnlyText = "Cache read"

    private fun setUp(openSessionId: String? = "api_1_alpha", reduceMotion: Boolean = false) {
        compose.setContent {
            HermesApp(
                sessions = UiState.Ready(UiFixtures.sessions),
                history = UiState.Ready(UiFixtures.history),
                turn = TurnState.Idle,
                openSessionId = openSessionId,
                openSession = UiFixtures.sessions.firstOrNull { it.id == openSessionId },
                now = UiFixtures.NOW,
                onOpenSession = {},
                onCloseSession = {},
                onSend = { false },
                onStop = {},
                onRetrySessions = {},
                onRetryHistory = {},
                onNewSession = {},
                preferences = BellpullPreferences(reduceMotion = reduceMotion),
            )
        }
    }

    private fun toggleInfo(label: String) {
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText(label).performClick()
    }

    @Test fun bothPanesStartVisibleAtUnfoldedWidth() {
        setUp()
        compose.onNodeWithText(listOnlyText).assertIsDisplayed()
        compose.onNodeWithText(contextOnlyText).assertIsDisplayed()
        compose.onAllNodesWithContentDescription("New chat").assertCountEquals(1)
    }

    @Test fun sessionsPaneCollapsesAndRestores() {
        setUp()
        compose.onNodeWithContentDescription("Hide sessions").performClick()
        compose.onNodeWithText(listOnlyText).assertDoesNotExist()
        compose.onNodeWithContentDescription("Show sessions").performClick()
        compose.onNodeWithText(listOnlyText).assertIsDisplayed()
    }

    @Test fun sessionsPaneMovesThroughAnIntermediatePosition() {
        setUp()
        val start = compose.onNodeWithContentDescription("Hide sessions").fetchSemanticsNode().boundsInRoot.left
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Hide sessions").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(96)
        val middle = compose.onNodeWithContentDescription("Show sessions").fetchSemanticsNode().boundsInRoot.left
        org.junit.Assert.assertTrue("Panel should be partway through its slide: $middle / $start", middle > 0 && middle < start)
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithText(listOnlyText).assertDoesNotExist()
    }

    @Test fun reduceMotionClosesPanelsWithoutAnIntermediateFrame() {
        setUp(reduceMotion = true)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Hide sessions").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText(listOnlyText).assertDoesNotExist()
        compose.onNodeWithContentDescription("Close context").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText(contextOnlyText).assertDoesNotExist()
    }

    @Test fun statsPaneCollapsesAndRestores() {
        setUp()
        compose.onNodeWithContentDescription("Close context").performClick()
        compose.onNodeWithText(contextOnlyText).assertDoesNotExist()
        toggleInfo("Session info")
        compose.onNodeWithText(contextOnlyText).assertIsDisplayed()
    }

    @Test fun bothPanesCanBeHiddenAtOnceAndTheToggleBarSurvives() {
        // The failure mode worth guarding: hide both and the bar goes with them, leaving no
        // way back. The bar lives in the transcript column precisely so that cannot happen.
        setUp()
        compose.onNodeWithContentDescription("Hide sessions").performClick()
        toggleInfo("Hide session info")
        compose.onNodeWithText(listOnlyText).assertDoesNotExist()
        compose.onNodeWithText(contextOnlyText).assertDoesNotExist()
        compose.onNodeWithContentDescription("Show sessions").assertIsDisplayed()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Session info").assertIsDisplayed()
    }

    @Test fun theBarIsReachableWithNoSessionOpen() {
        // Hiding the list with nothing open must not strand the user: the list is the only
        // route to opening a session, so its toggle has to stay available.
        setUp(openSessionId = null)
        compose.onNodeWithContentDescription("Hide sessions").performClick()
        compose.onNodeWithText(listOnlyText).assertDoesNotExist()
        compose.onNodeWithContentDescription("Show sessions").performClick()
        compose.onNodeWithText(listOnlyText).assertIsDisplayed()
    }
}
