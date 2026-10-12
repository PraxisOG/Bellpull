package org.praxisog.hermes

import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
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
class SettingsOrganizationTest {
    @get:Rule val compose = createComposeRule()
    @Composable private fun Settings() {
        val context = LocalContext.current
        val lab = remember { SpeechLab(context) { _, _ -> error("No native runtime in UI tests") } }
        androidx.compose.material3.Surface {
            SettingsWorkspace(BellpullPreferences(), {}, serverProfiles = { Text("Server profiles") },
                voiceSettings = { Text("Private transcription settings") }, speechLab = { SpeechExperiments(lab, {}) }, voiceOutputSettings = { Text("Output models") })
        }
    }
    @Test fun categoriesCover() { compose.setContent { HermesTheme { Settings() } }; compose.onRoot().captureRoboImage() }
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun categoriesUnfoldedDark() {
        compose.setContent { HermesTheme { Settings() } }; compose.onRoot().captureRoboImage()
    }
    private fun experiments() {
        compose.setContent { HermesTheme { Settings() } }
        compose.onNodeWithText("Voice input").performScrollTo().performClick()
        compose.onNodeWithText("Experimental").performScrollTo().performClick()
        compose.onNodeWithText("Local speech models").performScrollTo()
        compose.onNodeWithText("Moonshine Tiny Streaming").assertExists()
        compose.onRoot().captureRoboImage()
    }
    @Test fun experimentsCover() = experiments()
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun experimentsUnfoldedDark() = experiments()
    @Test fun displayTogglesStaySeparateFromAppearanceAndPreserveOtherPreferences() {
        var result = BellpullPreferences(palette = Palette.BROWN)
        compose.setContent { HermesTheme { SettingsWorkspace(result, { result = it }) } }
        compose.onNodeWithText("Dense chat window").assertDoesNotExist()
        compose.onNodeWithText("Display").performClick()
        compose.onNodeWithContentDescription("Dense chat window").performClick()
        compose.runOnIdle { assertFalse(result.denseChatWindow); assertEquals(Palette.BROWN, result.palette) }
    }
    @Test fun categoryExpansionSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { HermesTheme { Settings() } }
        compose.onNodeWithText("Voice input").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Experimental").assertExists()
        compose.onNodeWithText("Private transcription settings").assertExists()
    }
    @Test fun localModeDoesNotAskForRemoteCredentialsAndPrivateModeIsExplicit() {
        var saved: VoiceConfiguration? = null
        compose.setContent { HermesTheme {
            androidx.compose.foundation.layout.Column {
                VoiceSettingsEditor(VoiceConfiguration(), true, { saved = it })
            }
        } }
        compose.onNodeWithText("Full transcription URL").assertDoesNotExist()
        compose.onNodeWithText("Speech service key (if required)").assertDoesNotExist()
        compose.onNodeWithText("Save voice settings").performClick()
        compose.runOnIdle { assertEquals(VoiceRecognition.LOCAL, saved!!.recognition); assertEquals("", saved!!.endpoint) }
        compose.onNodeWithText("Private server").performClick()
        compose.onNodeWithText("Full transcription URL").assertExists()
        compose.onNodeWithText("Speech service key (if required)").assertExists()
        compose.onNodeWithText("On device").performClick()
        compose.onNodeWithText("Full transcription URL").assertDoesNotExist()
    }
    @Test fun missingModelDisablesEveryVoiceModeAndPrivateModeStillStarts() {
        var started = 0
        var config by mutableStateOf(VoiceConfiguration())
        compose.setContent { HermesTheme {
            val context = LocalContext.current
            val lab = remember { SpeechLab(context) { _, _ -> error("Must not load native code") } }
            VoicePanel(null, config, null, {}, { started++ }, {}, speechLab = lab)
        } }
        for (mode in listOf("Hands-free", "Review before sending", "Push to talk")) {
            compose.onNodeWithText(mode).performClick()
            compose.onNodeWithText("Start voice").assertIsNotEnabled().performTouchInput { click() }
        }
        compose.onNodeWithText("Download Moonshine Tiny Streaming in Settings → Voice input → Experimental first.").assertExists()
        compose.runOnIdle { assertEquals(0, started); config = VoiceConfiguration("https://speech.invalid/v1/audio/transcriptions") }
        compose.onNodeWithText("Start voice").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, started) }
    }
    private fun localSettings() {
        compose.setContent { HermesTheme {
            val context = LocalContext.current
            val lab = remember { SpeechLab(context) { _, _ -> error("No native runtime in UI tests") } }
            androidx.compose.material3.Surface {
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(20.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    VoiceSettingsEditor(VoiceConfiguration(), true, {}, lab)
                }
            }
        } }
        compose.onNodeWithText("Full transcription URL").assertDoesNotExist()
        compose.onNodeWithText("Moonshine Tiny Streaming").assertExists()
        compose.onRoot().captureRoboImage()
    }
    @Test fun localSettingsCover() = localSettings()
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun localSettingsUnfoldedDark() = localSettings()
    @Test fun modelPickerExposesAllThreeSizesAndSeparateCpuBackend() {
        compose.setContent { HermesTheme { Settings() } }
        compose.onNodeWithText("Voice input").performScrollTo().performClick()
        compose.onNodeWithText("Experimental").performScrollTo().performClick()
        compose.onNodeWithText("Moonshine Tiny Streaming").performScrollTo().performClick()
        compose.onNodeWithText("Moonshine Small Streaming · 142.30 MB").assertExists()
        compose.onNodeWithText("Moonshine Medium Streaming · 269.14 MB").assertExists()
        compose.onNodeWithText("Moonshine Small Streaming · 142.30 MB").performClick()
        compose.waitUntil { compose.onAllNodesWithText("Moonshine Small Streaming").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("CPU").assertExists()
        compose.onNodeWithText("142.30 MB · Not installed").assertExists()
        compose.onNodeWithText("Download model").assertExists()
    }
}
