package org.praxisog.hermes

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The adaptive shell. Unfolded puts list, detail and context side by side; folded shows one
 * at a time. Both are required, not a preference: targetSdk 37 removed the large-screen
 * orientation opt-out, so the Fold's cover and unfolded widths are both first-class.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HermesAppScreenshotTest {

    @get:Rule val compose = createComposeRule()

    /** Drive the toggles, then capture — the collapsed layouts only exist after a tap. */
    private fun captureAfter(
        tapContentDescription: String,
        thenTapContentDescription: String? = null,
    ) {
        compose.setContent { app(openSessionId = "api_1_alpha") }
        fun tap(description: String) {
            if (description == "Hide stats") {
                compose.onNodeWithContentDescription("More options").performClick()
                compose.onNodeWithText("Hide session info").performClick()
            } else compose.onNodeWithContentDescription(description).performClick()
        }
        tap(tapContentDescription)
        thenTapContentDescription?.let { tap(it) }
        // Menu dismissal and pane relayout must reach a rendered frame before capture.
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage()
    }

    @Composable
    private fun app(openSessionId: String?, turn: TurnState = TurnState.Idle) =
        HermesApp(
            sessions = UiState.Ready(UiFixtures.sessions),
            history = UiState.Ready(UiFixtures.history),
            turn = turn,
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

    /**
     * Captured through the rule, not via a bare `captureRoboImage { }`.
     *
     * A ComposeTestRule owns the composition host, so a direct capture in the same class
     * renders nothing and writes a blank PNG — silently, and `verify` then happily compares
     * blank against blank. Six screenshots in this class were blank for two commits because
     * of exactly that.
     */
    private fun capture(openSessionId: String?, turn: TurnState = TurnState.Idle) {
        compose.setContent { app(openSessionId = openSessionId, turn = turn) }
        compose.onRoot().captureRoboImage()
    }

    @Test @Config(qualifiers = "w393dp-h850dp") fun listOnlyCover() = capture(openSessionId = null)

    @Test @Config(qualifiers = "w393dp-h850dp") fun detailWithBackCover() =
        capture(openSessionId = "api_1_alpha")

    @Test @Config(qualifiers = "w840dp-h900dp") fun threePaneUnfolded() =
        capture(openSessionId = "api_1_alpha")

    @Test @Config(qualifiers = "w840dp-h900dp") fun noSessionPickedUnfolded() =
        capture(openSessionId = null)

    @Test @Config(qualifiers = "w840dp-h900dp") fun streamingUnfolded() = capture(
        openSessionId = "api_1_alpha",
        turn = TurnState.Streaming("Checking the serve configuration", canStop = true),
    )

    /** Wide enough for all three panes, which is where context is shown by default. */
    @Test @Config(qualifiers = "w1280dp-h900dp") fun threePaneWide() =
        capture(openSessionId = "api_1_alpha")

    @Test @Config(qualifiers = "w393dp-h850dp-night") fun detailCoverDark() =
        capture(openSessionId = "api_1_alpha")

    @Test @Config(qualifiers = "w840dp-h900dp-night") fun threePaneUnfoldedDark() =
        capture(openSessionId = "api_1_alpha")

    @Test @Config(qualifiers = "w393dp-h850dp-night") fun listCoverDark() = capture(openSessionId = null)

    // The collapse states. Roborazzi can capture after an interaction, which is what makes
    // these reachable: tap a toggle, then shoot.
    @Test @Config(qualifiers = "w840dp-h900dp") fun unfoldedListHidden() = captureAfter(
        tapContentDescription = "Hide sessions",
    )

    @Test @Config(qualifiers = "w840dp-h900dp") fun unfoldedStatsHidden() = captureAfter(
        tapContentDescription = "Hide stats",
    )

    @Test @Config(qualifiers = "w840dp-h900dp") fun unfoldedBothPanesHidden() = captureAfter(
        tapContentDescription = "Hide sessions",
        thenTapContentDescription = "Hide stats",
    )
}
