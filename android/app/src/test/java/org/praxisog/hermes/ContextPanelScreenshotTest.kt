package org.praxisog.hermes

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ContextPanelScreenshotTest {

    @Test @Config(qualifiers = "w393dp-h850dp") fun populatedCover() = captureRoboImage {
        HermesTheme {
            ContextPanel(session = UiFixtures.sessions.first(), now = UiFixtures.NOW, onClose = {})
        }
    }

    @Test @Config(qualifiers = "w393dp-h850dp") fun noSessionCover() = captureRoboImage {
        HermesTheme {
            ContextPanel(session = null, now = UiFixtures.NOW, onClose = {})
        }
    }
}
