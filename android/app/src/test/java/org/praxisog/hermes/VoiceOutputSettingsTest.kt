package org.praxisog.hermes

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class VoiceOutputSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    @Test fun outputConfigurationSurvivesInputEditsAndProfilesStaySeparate() {
        val first = VoiceSettings(app.getSharedPreferences("output-first", Context.MODE_PRIVATE))
        val second = VoiceSettings(app.getSharedPreferences("output-second", Context.MODE_PRIVATE))
        first.clear(); second.clear()
        val saved = VoiceConfiguration(rate = .9f, output = VoiceOutputConfiguration(VoiceOutputEngine.QWEN, "aiden", seed = 42))
        first.save(saved); first.save(first.read().copy(pauseMs = 2400))
        assertEquals(saved.output, first.read().output); assertEquals(.9f, first.read().rate)
        assertEquals(VoiceOutputEngine.SYSTEM, second.read().output.engine)
    }
    @Test fun legacyRateIsPreservedAndQwenIsNeverAutomaticallySelected() {
        val prefs = app.getSharedPreferences("output-legacy", Context.MODE_PRIVATE)
        prefs.edit().clear().putFloat("rate", 1.3f).commit()
        val saved = VoiceSettings(prefs).read()
        assertEquals(1.3f, saved.rate); assertEquals(VoiceOutputEngine.SYSTEM, saved.output.engine)
    }
    @Test fun missingOutputWeightsBlockNativeStartWithoutChangingInputModel() {
        val lab = VoiceOutputLab(app) { error("Native load must not run") }
        assertNotNull(lab.startProblem(VoiceConfiguration(output = VoiceOutputConfiguration(VoiceOutputEngine.QWEN))))
        assertNull(lab.startProblem(VoiceConfiguration()))
    }
    private fun screen() {
        compose.setContent { HermesTheme { Surface {
            val lab = remember { VoiceOutputLab(app) { error("No native code in screenshot tests") } }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Voice output", style = MaterialTheme.typography.titleLarge)
                VoiceOutputSettings(VoiceConfiguration(output = VoiceOutputConfiguration(VoiceOutputEngine.QWEN)), lab, true, onSave = {})
            }
        } } }
        compose.onNodeWithText("Save voice output").assertIsNotEnabled()
        compose.onNodeWithText("Preview").assertIsNotEnabled()
        compose.onNodeWithText("Speaker: ryan").assertExists()
        compose.onRoot().captureRoboImage()
    }
    @Test fun outputCover() = screen()
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun outputUnfoldedDark() = screen()
    @Test fun downloadSectionExposesSizeStatusAndNoUnsupportedBackends() {
        screen()
        compose.onNodeWithText("Qwen model").performScrollTo().performClick()
        compose.onNodeWithText("Download Qwen").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("859.85 MB · Not installed").assertExists()
        compose.onNodeWithText("Backend: CPU · GGML").assertExists()
        compose.onNodeWithText("Emotion").assertDoesNotExist()
    }
}
