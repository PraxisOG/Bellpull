package org.praxisog.hermes

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
        compose.onNodeWithContentDescription(tapContentDescription).performClick()
        thenTapContentDescription?.let {
            compose.onNodeWithContentDescription(it).performClick()
        }
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

    private fun capture(openSessionId: String?, turn: TurnState = TurnState.Idle) =
        captureRoboImage {
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
