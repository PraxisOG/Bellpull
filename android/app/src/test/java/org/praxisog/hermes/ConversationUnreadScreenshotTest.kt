package org.praxisog.hermes

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ConversationUnreadScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private fun capture(open: String?) {
        val sessions = UiFixtures.sessions.map { if (it.id == "api_2_beta") it.copy(messageCount = 128, toolCallCount = 17) else it }
        compose.setContent {
            HermesApp(UiState.Ready(sessions), UiState.Ready(UiFixtures.history), TurnState.Idle,
                open, sessions.firstOrNull { it.id == open }, UiFixtures.NOW, {}, {}, { false }, {}, {}, {}, {},
                unreadIds = setOf("api_2_beta"), preferences = BellpullPreferences(palette = Palette.MAROON))
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage()
    }
    @Test @Config(qualifiers = "w393dp-h850dp") fun unreadCover() = capture(null)
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun unreadUnfoldedDark() = capture("api_1_alpha")
}
