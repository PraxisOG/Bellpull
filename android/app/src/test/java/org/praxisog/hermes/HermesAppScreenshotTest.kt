package org.praxisog.hermes

import com.github.takahirom.roborazzi.captureRoboImage
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
}
