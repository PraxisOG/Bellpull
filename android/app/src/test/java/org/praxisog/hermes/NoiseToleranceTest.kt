package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class NoiseToleranceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun thresholdRequiresSaveAndSwitchingModelsDropsUnsavedEdits() {
        var model by mutableStateOf("tiny")
        val saved = mutableStateMapOf("tiny" to .5f, "small" to .7f)
        compose.setContent { HermesTheme {
            Column { NoiseToleranceSettings(model, "Moonshine $model", saved.getValue(model), true) { saved[model] = it } }
        } }
        compose.onNodeWithContentDescription("Noise tolerance").performSemanticsAction(SemanticsActions.SetProgress) { it(.9f) }
        compose.runOnIdle { assertEquals(.5f, saved["tiny"]!!, .001f) }
        compose.onNodeWithText("Save noise tolerance").performClick()
        compose.runOnIdle { assertEquals(.9f, saved["tiny"]!!, .001f); model = "small" }
        compose.onNodeWithText("Moonshine small · 0.70").assertExists()
        compose.onNodeWithText("Reset").performClick()
        compose.runOnIdle { assertEquals(.7f, saved["small"]!!, .001f) }
        compose.onNodeWithText("Save noise tolerance").performClick()
        compose.runOnIdle { assertEquals(.5f, saved["small"]!!, .001f); assertEquals(.9f, saved["tiny"]!!, .001f) }
    }
    private fun screenshot() {
        compose.setContent { HermesTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Voice input", style = MaterialTheme.typography.titleLarge)
                NoiseToleranceSettings("tiny", "Moonshine Tiny Streaming", .5f, true) {}
            }
        } } }
        compose.onNodeWithText("Noise tolerance").assertExists()
        compose.onRoot().captureRoboImage()
    }
    @Test fun noiseToleranceCover() = screenshot()
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun noiseToleranceUnfoldedDark() = screenshot()
}
