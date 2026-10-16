package org.praxisog.hermes

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise real startup manifests without models, inference or a saved account. */
@RunWith(AndroidJUnit4::class)
class VoiceStartupDeviceTest {
    @Test fun shippedVoiceModelsDoNotCrashStartupAndMissingWeightsStayBlocked() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        check(app.packageName == "org.praxisog.hermes.fixture")
        lateinit var lab: VoiceOutputLab
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            lab = VoiceOutputLab(app)
            assertNull(lab.startProblem(VoiceConfiguration()))
            for (engine in listOf(VoiceOutputEngine.QWEN, VoiceOutputEngine.MOSS)) {
                val config = VoiceConfiguration(output = VoiceOutputConfiguration(engine = engine))
                assertFalse(lab.isInstalled(engine))
                assertTrue(lab.startProblem(config).orEmpty().contains("Download"))
                assertThrows(IllegalStateException::class.java) { lab.output(config) }
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
