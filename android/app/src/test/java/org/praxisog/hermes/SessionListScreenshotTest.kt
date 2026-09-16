package org.praxisog.hermes

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Folded (cover, 393dp) and unfolded (840dp) for every state. targetSdk 37 removed the
 * large-screen orientation opt-out, so both widths are a requirement, not polish.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SessionListScreenshotTest {

    private fun capture(state: UiState<List<org.praxisog.hermes.model.Session>>, selected: String? = null) =
        captureRoboImage {
            SessionListScreen(
                state = state,
                now = UiFixtures.NOW,
                selectedId = selected,
                onOpen = {},
                onRetry = {},
            )
        }

    @Test @Config(qualifiers = "w393dp-h850dp") fun readyCover() =
        capture(UiState.Ready(UiFixtures.sessions))

    @Test @Config(qualifiers = "w840dp-h900dp") fun readyUnfolded() =
        capture(UiState.Ready(UiFixtures.sessions), selected = "api_2_beta")

    @Test @Config(qualifiers = "w393dp-h850dp") fun emptyCover() =
        capture(UiState.Ready(emptyList()))

    @Test @Config(qualifiers = "w840dp-h900dp") fun emptyUnfolded() =
        capture(UiState.Ready(emptyList()))

    @Test @Config(qualifiers = "w393dp-h850dp") fun failedCover() =
        capture(UiState.Failed(UiFixtures.authFailure))

    @Test @Config(qualifiers = "w840dp-h900dp") fun failedUnfolded() =
        capture(UiState.Failed(UiFixtures.authFailure))
}
