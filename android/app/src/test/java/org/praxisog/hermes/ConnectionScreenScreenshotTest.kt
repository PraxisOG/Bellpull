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
class ConnectionScreenScreenshotTest {
    @Test @Config(qualifiers = "w393dp-h850dp-night") fun coverDark() {
        captureRoboImage {
            ConnectionScreen("", "", "Enter a server URL and API key.", false) { _, _ -> }
        }
    }

    @Test @Config(qualifiers = "w840dp-h900dp-night") fun unfoldedDark() {
        captureRoboImage {
            ConnectionScreen("", "", "Enter a server URL and API key.", false) { _, _ -> }
        }
    }

    @Test
    @Config(qualifiers = "w393dp-h850dp")
    fun cover() {
        captureRoboImage {
            ConnectionScreen("", "", "Enter a server URL and API key.", false) { _, _ -> }
        }
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun unfolded() {
        captureRoboImage {
            ConnectionScreen("", "", "Enter a server URL and API key.", false) { _, _ -> }
        }
    }
}
