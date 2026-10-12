package org.praxisog.hermes

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.model.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RunRecoveryScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private fun capture(approval: Boolean = false, settings: Boolean = false, review: Boolean = false) {
        val session = UiFixtures.sessions.first()
        val submission = RunSubmission("fixture", session.id, "Review the build output", 1.0, 1L, emptySet(), "run_fixture", "running")
        val state = RunPresentation(if (review) submission.copy(status = "completed", completionOutput = "Saved answer",
            historyNeedsReview = true, historyChecks = 12) else submission,
            if (review) TurnState.Finished("Saved answer") else TurnState.Streaming("", canStop = true, statusLabel = "Working…"),
            approval = if (approval) RunApproval("exact-request", "terminal: remove the temporary build directory /tmp/bellpull-build", "Remove temporary build output", listOf("once", "deny")) else null,
            notice = if (review) "Hermes finished, but its saved reply could not be matched. Check status to retry, or review this chat and stop tracking. This run will not be submitted again."
                else if (approval) null else "Connection lost. Hermes may still be working. Reconnecting…")
        compose.setContent {
            HermesApp(UiState.Ready(UiFixtures.sessions), UiState.Ready(UiFixtures.history), state.turn,
                session.id, session, UiFixtures.NOW, {}, {}, {}, {}, {}, {}, {},
                runPresentation = state, runLabels = mapOf(session.id to if (approval) "Needs approval" else "Reconnecting"),
                notificationsAvailable = true, preferences = BellpullPreferences(palette = Palette.MAROON, notifications = true))
        }
        compose.waitForIdle()
        if (settings) {
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Notifications").performClick()
            compose.waitForIdle()
        }
        if (review) { compose.onNodeWithText("Review saved request").performClick(); compose.waitForIdle() }
        compose.onRoot().captureRoboImage()
    }
    @Test @Config(qualifiers = "w393dp-h850dp") fun approvalCover() = capture(approval = true)
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun approvalUnfoldedDark() = capture(approval = true)
    @Test @Config(qualifiers = "w393dp-h850dp-night") fun recoveringCoverDark() = capture()
    @Test @Config(qualifiers = "w840dp-h900dp") fun recoveringUnfolded() = capture()
    @Test @Config(qualifiers = "w840dp-h900dp") fun notificationsSettings() = capture(settings = true)
    @Test @Config(qualifiers = "w393dp-h850dp") fun completedReviewCover() = capture(review = true)
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun completedReviewUnfoldedDark() = capture(review = true)
}
