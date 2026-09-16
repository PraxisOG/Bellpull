package org.praxisog.hermes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    private fun setUp(openSessionId: String? = "api_1_alpha") {
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
                onSend = {},
                onStop = {},
                onRetrySessions = {},
                onRetryHistory = {},
                onNewSession = {},
            )
        }
    }

    @Test fun bothPanesStartVisibleAtUnfoldedWidth() {
        setUp()
        compose.onNodeWithText(listOnlyText).assertIsDisplayed()
        compose.onNodeWithText(contextOnlyText).assertIsDisplayed()
    }

    @Test fun sessionsPaneCollapsesAndRestores() {
        setUp()
        compose.onNodeWithContentDescription("Hide sessions").performClick()
        compose.onNodeWithText(listOnlyText).assertDoesNotExist()
        compose.onNodeWithContentDescription("Show sessions").performClick()
        compose.onNodeWithText(listOnlyText).assertIsDisplayed()
    }

    @Test fun statsPaneCollapsesAndRestores() {
        setUp()
        compose.onNodeWithContentDescription("Hide stats").performClick()
        compose.onNodeWithText(contextOnlyText).assertDoesNotExist()
        compose.onNodeWithContentDescription("Show stats").performClick()
        compose.onNodeWithText(contextOnlyText).assertIsDisplayed()
    }

    @Test fun bothPanesCanBeHiddenAtOnceAndTheToggleBarSurvives() {
        // The failure mode worth guarding: hide both and the bar goes with them, leaving no
        // way back. The bar lives in the transcript column precisely so that cannot happen.
        setUp()
        compose.onNodeWithContentDescription("Hide sessions").performClick()
        compose.onNodeWithContentDescription("Hide stats").performClick()
        compose.onNodeWithText(listOnlyText).assertDoesNotExist()
        compose.onNodeWithText(contextOnlyText).assertDoesNotExist()
        compose.onNodeWithContentDescription("Show sessions").assertIsDisplayed()
        compose.onNodeWithContentDescription("Show stats").assertIsDisplayed()
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
