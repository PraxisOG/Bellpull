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

    private fun capture(
        state: UiState<List<org.praxisog.hermes.model.Session>>,
        selected: String? = null,
        creating: Boolean = false,
        createError: org.praxisog.hermes.model.HermesError? = null,
        offline: OfflineState? = null,
    ) = captureRoboImage {
        SessionListScreen(
            state = state,
            now = UiFixtures.NOW,
            selectedId = selected,
            onOpen = {},
            onRetry = {},
            onNewSession = {},
            creating = creating,
            createError = createError,
            offline = offline,
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

    @Test @Config(qualifiers = "w393dp-h850dp") fun creatingCover() =
        capture(UiState.Ready(UiFixtures.sessions), creating = true)

    /** A failed create must not look like a failed list load; the list stays usable. */
    @Test @Config(qualifiers = "w393dp-h850dp") fun createFailedCover() =
        capture(UiState.Ready(UiFixtures.sessions), createError = UiFixtures.authFailure)

    /** The empty case is the one that made the missing entry point unrecoverable. */
    @Test @Config(qualifiers = "w393dp-h850dp") fun emptyWithEntryPointCover() =
        capture(UiState.Ready(emptyList()))

    /** Search with matches: apps ranked, sessions matched, Hermes always offered. */
    @Test @Config(qualifiers = "w393dp-h850dp") fun searchWithMatchesCover() = captureSearch("w")

    /** Search with nothing matching — the case the Ask Hermes row exists for. */
    @Test @Config(qualifiers = "w393dp-h850dp") fun searchNoMatchesCover() =
        captureSearch("what is the tailnet IP of the fold")

    private fun captureSearch(query: String) = captureRoboImage {
        SessionListScreen(
            state = UiState.Ready(UiFixtures.sessions),
            now = UiFixtures.NOW,
            selectedId = null,
            onOpen = {},
            onRetry = {},
            onNewSession = {},
            query = query,
            onQueryChange = {},
            apps = UiFixtures.apps,
            onLaunchApp = {},
            onAskHermes = {},
        )
    }

    /**
     * Offline: cached sessions with a banner naming both the staleness and which layer
     * failed. "No silent fallback anywhere" is a project non-negotiable, and this is the
     * image that proves the fallback is not silent.
     */
    @Test @Config(qualifiers = "w393dp-h850dp") fun offlineCachedCover() = capture(
        UiState.Ready(UiFixtures.sessions),
        offline = OfflineState(
            cachedAtEpochSeconds = UiFixtures.NOW - 5_400,
            error = org.praxisog.hermes.model.HermesError.Unreachable(
                org.praxisog.hermes.model.HermesError.Unreachable.Kind.DNS,
                "powermacg3.tail0b3e3a.ts.net",
            ),
        ),
    )
}
