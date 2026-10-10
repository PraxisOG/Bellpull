package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
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
class GpuPanelScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private fun capture(missing: Boolean = false) {
        val readings = GpuTelemetry(1, UiFixtures.NOW, cards = if (missing) emptyList() else (0..3).map { index ->
            GpuCard("fixture-$index", "AMD", "$index", "AMD GPU $index", "0000:0$index:00.0", UiFixtures.NOW - 5,
                stale = index == 3, sensors = listOf(GpuSensor("edge", 42.0 + index * 8, 0.0, 110.0, 95.0),
                    GpuSensor("hotspot", if (index == 2) null else 68.0 + index * 6, 0.0, 120.0),
                    GpuSensor("memory", null, 0.0, 120.0)))
        }, errors = if (missing) listOf("Install amd-smi or nvidia-smi on the Hermes plugin host to read temperatures") else emptyList(),
            scaleNote = "Fixed display ranges in °C; color is not a hardware safety rating.")
        compose.setContent {
            HermesTheme(palette = Palette.MAROON) {
                Surface { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    GpuPanel(readings, null, UiFixtures.NOW)
                } }
            }
        }
        compose.waitForIdle()
        if (!missing) compose.onNodeWithText("AMD GPU 0").assertIsDisplayed()
        compose.onRoot().captureRoboImage()
    }
    @Test @Config(qualifiers = "w393dp-h850dp") fun fourCardsCover() = capture()
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun fourCardsUnfoldedDark() = capture()
    @Test @Config(qualifiers = "w393dp-h850dp-night") fun unavailableCover() = capture(missing = true)
}
