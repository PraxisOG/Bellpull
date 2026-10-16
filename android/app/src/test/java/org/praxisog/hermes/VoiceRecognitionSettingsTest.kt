package org.praxisog.hermes

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceRecognitionSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Test fun newSetupSavesLocalModeWithoutAnyServerFields() {
        val prefs = context.getSharedPreferences("local-voice-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val settings = VoiceSettings(prefs)
        assertEquals(VoiceRecognition.LOCAL, settings.read().recognition)
        settings.save(VoiceConfiguration(pauseMs = 2200))
        val restored = VoiceSettings(prefs).read()
        assertEquals(VoiceRecognition.LOCAL, restored.recognition)
        assertEquals("", restored.endpoint)
        assertEquals(2200, restored.pauseMs)
    }
    @Test fun legacyPrivateSetupKeepsItsChoiceAndCredentialsUntilExplicitlySwitched() {
        val prefs = context.getSharedPreferences("legacy-voice-test", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("endpoint", "https://speech.invalid/v1/audio/transcriptions")
            .putString("key", "speech-only-key").putString("model", "private-model").commit()
        val settings = VoiceSettings(prefs)
        val legacy = settings.read()
        assertEquals(VoiceRecognition.PRIVATE_SERVER, legacy.recognition)
        settings.save(VoiceConfiguration(legacy.endpoint, legacy.key, legacy.model, recognition = VoiceRecognition.LOCAL))
        val restored = VoiceSettings(prefs).read()
        assertEquals(VoiceRecognition.LOCAL, restored.recognition)
        assertEquals(legacy.endpoint, restored.endpoint)
        assertEquals(legacy.key, restored.key)
        assertEquals(legacy.model, restored.model)
        assertFalse(restored.toString().contains("speech-only-key"))
    }
    @Test fun privateSelectionStillRejectsUnsafeUrlsAndProfilesRemainSeparate() {
        val one = VoiceSettings(context.getSharedPreferences("voice-test-one", Context.MODE_PRIVATE))
        val two = VoiceSettings(context.getSharedPreferences("voice-test-two", Context.MODE_PRIVATE))
        one.clear(); two.clear()
        assertThrows(IllegalArgumentException::class.java) {
            one.save(VoiceConfiguration("http://ordinary-host/v1/audio/transcriptions"))
        }
        one.save(VoiceConfiguration("https://speech.invalid/v1/audio/transcriptions", "one-key", "one-model"))
        two.save(VoiceConfiguration(recognition = VoiceRecognition.LOCAL))
        assertEquals(VoiceRecognition.LOCAL, two.read().recognition)
        assertEquals("", two.read().key)
        assertEquals("one-key", one.read().key)
    }
    @Test fun announcementFlagsPersistIndependentlyAndAbsentValuesStayOn() {
        val one = VoiceSettings(context.getSharedPreferences("announcements-one", Context.MODE_PRIVATE))
        val two = VoiceSettings(context.getSharedPreferences("announcements-two", Context.MODE_PRIVATE))
        one.clear(); two.clear()
        assertEquals(VoiceAnnouncements(), one.read().announcements)
        for (mask in 0..15) {
            val flags = VoiceAnnouncements(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0, mask and 8 != 0)
            one.save(VoiceConfiguration(announcements = flags))
            assertEquals(flags, VoiceSettings(context.getSharedPreferences("announcements-one", Context.MODE_PRIVATE)).read().announcements)
            assertEquals(VoiceAnnouncements(), two.read().announcements)
        }
    }
    @Test fun modelChoiceAndTuningSurviveRestartWithoutLeakingBetweenModels() {
        context.getSharedPreferences("speech-experiments", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = SpeechExperimentSettings(context)
        settings.select("moonshine-small")
        settings.tune("moonshine-small", SpeechTuning(800, .7f))
        val restored = SpeechExperimentSettings(context)
        assertEquals("moonshine-small", restored.selectedModel)
        assertEquals(SpeechTuning(800, .7f), restored.tuning("moonshine-small"))
        assertEquals(SpeechTuning(), restored.tuning("moonshine-tiny"))
    }
}
