package org.praxisog.hermes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ImmersiveHeaderScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var camera: Rect

    private fun setUp(width: Int, centered: Boolean = false, dark: Boolean = false, session: String? = "api_1_alpha") {
        compose.setContent {
            val density = LocalDensity.current.density
            val left = if (centered) width / 2f - 21f else width - 50f
            camera = Rect(left * density, 0f, (if (centered) left + 42f else width.toFloat()) * density, 58f * density)
            CompositionLocalProvider(LocalWindowCutouts provides listOf(camera)) {
                Box(Modifier.fillMaxSize()) {
                    HermesApp(UiState.Ready(UiFixtures.sessions), UiState.Ready(UiFixtures.history), TurnState.Idle,
                        session, UiFixtures.sessions.firstOrNull { it.id == session }, UiFixtures.NOW,
                        {}, {}, { false }, {}, {}, {}, {},
                        preferences = BellpullPreferences(appearance = if (dark) Appearance.DARK else Appearance.LIGHT,
                            palette = Palette.MAROON, immersiveMode = true))
                    // A visible camera marker makes the reserved area reviewable in the fixture.
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(Color.Black, 14f * density, Offset(camera.center.x, 29f * density))
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun safeControls(vararg labels: String) {
        labels.forEach { label ->
            val node = compose.onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode()
            assertFalse("$label intersects camera: ${node.boundsInRoot} / $camera", node.boundsInRoot.overlaps(camera))
        }
    }

    @Test @Config(qualifiers = "w840dp-h900dp") fun cornerCameraWithContext() {
        setUp(840)
        safeControls("Search conversations", "Hide sessions", "New chat", "More options", "Close context")
        compose.onRoot().captureRoboImage()
    }

    @Test @Config(qualifiers = "w840dp-h900dp-night") fun cornerCameraAfterBothPanesClose() {
        setUp(840, dark = true)
        compose.onNodeWithContentDescription("Close context").performClick()
        compose.onNodeWithContentDescription("Hide sessions").performClick()
        compose.mainClock.advanceTimeBy(500)
        safeControls("Show sessions", "New chat", "More options")
        compose.onRoot().captureRoboImage()
        compose.onNodeWithContentDescription("Show sessions").performClick()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Session info").performClick()
        safeControls("Search conversations", "Hide sessions", "New chat", "More options", "Close context")
    }

    @Test @Config(qualifiers = "w393dp-h850dp") fun centeredCameraChat() {
        setUp(393, centered = true)
        safeControls("Back to sessions", "New chat", "More options")
        compose.onRoot().captureRoboImage()
    }

    @Test @Config(qualifiers = "w393dp-h850dp-night") fun centeredCameraConversations() {
        setUp(393, centered = true, dark = true, session = null)
        safeControls("Search conversations", "New chat")
        compose.onRoot().captureRoboImage()
    }
}
