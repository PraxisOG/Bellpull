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
class MossVoiceSettingsTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun mossRoundTripsUsingStableLowercaseIdAndPreservesOtherSettings() {
        val prefs = app.getSharedPreferences("moss-roundtrip", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val settings = VoiceSettings(prefs)
        val saved = VoiceConfiguration(rate = .9f, pauseMs = 2400,
            output = VoiceOutputConfiguration(engine = VoiceOutputEngine.MOSS, seed = Long.MAX_VALUE, volume = .6f))
        settings.save(saved)
        assertEquals("moss", prefs.getString("output", null))
        assertEquals(saved, settings.read())
        settings.save(settings.read().copy(pauseMs = 1800))
        assertEquals(saved.output, settings.read().output)
    }

    @Test fun legacyPocketSettingsMigrateAndUnknownIdsUseSystem() {
        val prefs = app.getSharedPreferences("moss-migration", Context.MODE_PRIVATE)
        val settings = VoiceSettings(prefs)
        for (id in listOf("pocket", "POCKET")) {
            prefs.edit().clear().putString("output", id).putFloat("rate", 1.3f)
                .putInt("pocket_steps", 7).putLong("seed", 42).commit()
            val migrated = settings.read()
            assertEquals(VoiceOutputEngine.MOSS, migrated.output.engine)
            assertEquals(7, migrated.output.pocketSteps)
            assertEquals(42L, migrated.output.seed)
            assertEquals(1.3f, migrated.rate)
            settings.save(migrated)
            assertEquals("moss", prefs.getString("output", null))
        }
        for (id in listOf(null, "unknown-engine")) {
            prefs.edit().clear().putString("output", id).commit()
            assertEquals(VoiceOutputEngine.SYSTEM, settings.read().output.engine)
        }
    }

    @Test fun mossSelectionWithoutWeightsFailsVisiblyAndNeverFallsBackToQwen() {
        val lab = VoiceOutputLab(app) { error("Qwen must not load for MOSS") }
        val config = VoiceConfiguration(output = VoiceOutputConfiguration(engine = VoiceOutputEngine.MOSS))
        assertFalse(lab.isInstalled(VoiceOutputEngine.MOSS))
        assertFalse(lab.mossInstalled)
        assertFalse(lab.mossLoaded)
        assertNotEquals(lab.files.directory, lab.mossFiles.directory)
        assertEquals("Download MOSS-TTS-Nano in Settings → Voice output first.", lab.startProblem(config))
        val error = assertThrows(IllegalStateException::class.java) { lab.output(config) }
        assertTrue(error.message.orEmpty().contains("MOSS-TTS-Nano"))
        assertFalse(lab.runtime.loaded)
        assertFalse(lab.mossRuntime.loaded)
        assertTrue(lab.isInstalled(VoiceOutputEngine.SYSTEM))
        assertNull(lab.startProblem(VoiceConfiguration()))
    }
}
