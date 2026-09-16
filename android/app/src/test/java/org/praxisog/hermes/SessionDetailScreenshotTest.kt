package org.praxisog.hermes

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The states worth pinning as images: a transcript containing both Gate 0c tool-result
 * states, a turn mid-stream, a turn the user stopped, and a turn whose stream dropped.
 *
 * The last two are the ones that matter most. Hermes reports a stopped turn as
 * `completed: true, interrupted: false` (Gate 0d), so "this reply is partial" is rendered
 * from client state — if that regresses, a cut-off answer starts looking complete, and these
 * images are what catches it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SessionDetailScreenshotTest {

    private fun capture(
        history: UiState<List<org.praxisog.hermes.model.Message>> = UiState.Ready(UiFixtures.history),
        turn: TurnState = TurnState.Idle,
    ) = captureRoboImage {
        SessionDetailScreen(history = history, turn = turn, onSend = {}, onStop = {}, onRetry = {})
    }

    @Test @Config(qualifiers = "w393dp-h850dp") fun transcriptCover() = capture()

    @Test @Config(qualifiers = "w840dp-h900dp") fun transcriptUnfolded() = capture()

    @Test @Config(qualifiers = "w393dp-h850dp") fun streamingCover() = capture(
        turn = TurnState.Streaming(
            text = "Checking the serve configuration",
            runningTool = "tailscale serve status",
            canStop = true,
        ),
    )

    @Test @Config(qualifiers = "w840dp-h900dp") fun streamingUnfolded() = capture(
        turn = TurnState.Streaming(text = "Checking the serve configuration", canStop = true),
    )

    @Test @Config(qualifiers = "w393dp-h850dp") fun stoppedByUserCover() = capture(
        turn = TurnState.StoppedByUser("1\n2\n3\n4\n5\n6"),
    )

    @Test @Config(qualifiers = "w840dp-h900dp") fun stoppedByUserUnfolded() = capture(
        turn = TurnState.StoppedByUser("1\n2\n3\n4\n5\n6"),
    )

    @Test @Config(qualifiers = "w393dp-h850dp") fun truncatedCover() = capture(
        turn = TurnState.Truncated("The congestion window grows expon", UiFixtures.truncation),
    )

    @Test @Config(qualifiers = "w840dp-h900dp") fun truncatedUnfolded() = capture(
        turn = TurnState.Truncated("The congestion window grows expon", UiFixtures.truncation),
    )

    @Test @Config(qualifiers = "w393dp-h850dp") fun historyFailedCover() =
        capture(history = UiState.Failed(UiFixtures.truncation))
}
