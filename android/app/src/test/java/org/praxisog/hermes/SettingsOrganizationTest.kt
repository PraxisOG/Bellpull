package org.praxisog.hermes

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
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
                voiceSettings = { Text("Private transcription settings") }, speechLab = { SpeechExperiments(lab, {}) })
        }
    }
    @Test fun categoriesCover() { compose.setContent { HermesTheme { Settings() } }; compose.onRoot().captureRoboImage() }
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun categoriesUnfoldedDark() {
        compose.setContent { HermesTheme { Settings() } }; compose.onRoot().captureRoboImage()
    }
    private fun experiments() {
        compose.setContent { HermesTheme { Settings() } }
        compose.onNodeWithText("Voice").performScrollTo().performClick()
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
        compose.onNodeWithText("Voice").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Experimental").assertExists()
        compose.onNodeWithText("Private transcription settings").assertExists()
    }
}
