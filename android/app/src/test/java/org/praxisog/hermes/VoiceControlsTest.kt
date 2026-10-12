package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class VoiceControlsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun tapTogglesConversationAndHoldNeverDispatchesATap() {
        var active by mutableStateOf(false)
        var taps = 0; var holds = 0; var releases = 0
        compose.setContent { HermesTheme {
            VoiceButton(active, true, true, { active = !active; taps++ }, { active = true; holds++ }, { assertFalse(it); releases++ })
        } }
        compose.onNodeWithContentDescription("Voice conversation").performTouchInput { click() }
        compose.onNodeWithContentDescription("End voice conversation").performTouchInput { click() }
        compose.runOnIdle { assertEquals(2, taps); assertFalse(active) }
        compose.onNodeWithContentDescription("Voice conversation").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(1, holds); assertEquals(1, releases); assertEquals(2, taps) }
    }
    @Test fun disabledControlNeverOpensCapture() {
        compose.setContent { HermesTheme { VoiceButton(false, false, true, { fail() }, { fail() }, { fail() }) } }
        compose.onNodeWithContentDescription("Voice conversation").assertIsNotEnabled().performTouchInput { longClick() }
    }
    @Test fun canceledHoldDiscardsDictationWithoutDispatchingATap() {
        var holds = 0; var releases = 0
        compose.setContent { HermesTheme {
            VoiceButton(false, true, true, { fail("Canceled hold must never become a tap") }, { holds++ },
                { assertTrue(it); releases++ })
        } }
        compose.onNodeWithContentDescription("Voice conversation").performTouchInput {
            down(center); advanceEventTime(700); cancel()
        }
        compose.runOnIdle { assertEquals(1, holds); assertEquals(1, releases) }
    }
    @Test fun dictationInsertsAtTheCursorAndReplacesOnlyTheSelection() {
        val result = insertVoiceDictation(TextFieldValue("Before after", TextRange(7)), "new words")
        assertEquals("Before new words after", result.text)
        assertEquals(TextRange(17), result.selection)
        assertEquals("Before new after", insertVoiceDictation(TextFieldValue("Before OLD after", TextRange(7, 10)), "new").text)
    }
    @Test fun debuggingInterfaceDefaultsOffAndSurvivesSettingsReload() {
        val context = RuntimeEnvironment.getApplication()
        val settings = BellpullSettings(context)
        assertFalse(settings.value.debuggingVoiceInterface)
        settings.update(settings.value.copy(debuggingVoiceInterface = true))
        assertTrue(BellpullSettings(context).value.debuggingVoiceInterface)
    }
    private fun controls() {
        compose.setContent { HermesTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Tap to talk · hold to dictate")
                Row {
                    VoiceButton(false, true, true, {}, {}, {})
                    VoiceButton(true, true, true, {}, {}, {})
                }
                Text("Voice on · Listening", style = MaterialTheme.typography.labelMedium)
                SettingsWorkspace(BellpullPreferences(), {}, voiceSettings = { Text("Speech recognition") })
            }
        } } }
        compose.onNodeWithText("Voice").performClick()
        compose.onNodeWithText("Using voice").performClick()
        compose.onNodeWithText("Debugging voice interface").assertExists()
        compose.onRoot().captureRoboImage()
    }
    @Test fun usingVoiceCover() = controls()
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun usingVoiceUnfoldedDark() = controls()
}
