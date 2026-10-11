package org.praxisog.hermes

import android.content.Context
import android.content.Intent
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.core.PrivateTranscription
import org.praxisog.hermes.model.HermesResult
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Only uses the separate app sandbox and explicit loopback fixtures. No personal profiles. */
@RunWith(AndroidJUnit4::class)
class VoiceDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun guard() { check(context.packageName == "org.praxisog.hermes.fixture") }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(timeout: Long = 20000, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) { if (SystemClock.elapsedRealtime() >= until) fail("Voice device condition timed out"); Thread.sleep(50) }
    }
    @Test fun installedOfflineVoicePlaysAndNativeMicrophoneReleases() {
        guard()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val output = AndroidVoiceOutput(context, 1.1f)
        val capture = AndroidVoiceCapture(scope)
        val ready = AtomicReference<String>()
        val error = AtomicReference<String>()
        val done = AtomicInteger(); val started = AtomicInteger(); val samples = AtomicInteger()
        ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
            scene.onActivity { it.setContent { HermesTheme { Text("Bellpull isolated voice acceptance") } } }
            try {
                main { output.prepare(ready::set, error::set) }
                await { ready.get() != null || error.get() != null }; assertNull(error.get()); assertNotNull(ready.get())
                main { output.speak("Bellpull voice test.", { started.incrementAndGet() }, { done.incrementAndGet() }) }
                await { done.get() == 1 || error.get() != null }; assertNull(error.get()); assertEquals(1, started.get())
                main { assertTrue(capture.start({ _, count -> samples.addAndGet(count) }, error::set)) }
                await { samples.get() >= 16000 || error.get() != null }; assertNull(error.get())
                main { capture.close() }
                val stopped = samples.get(); Thread.sleep(300); assertEquals(stopped, samples.get())
                repeat(3) {
                    val before = samples.get()
                    main { assertTrue(capture.start({ _, count -> samples.addAndGet(count) }, error::set)) }
                    await { samples.get() >= before + 1600 || error.get() != null }; assertNull(error.get())
                    main { capture.close() }
                }
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("offline_voice", ready.get()); putString("aec_available", AcousticEchoCanceler.isAvailable().toString())
                    putString("aec_enabled", capture.echoCancellation.toString())
                    putString("on_device_recognition", (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)).toString())
                })
            } finally { main { capture.close(); output.close() }; scope.cancel() }
        }
    }
    @Test fun encryptedVoiceConfigurationAndPlaybackStayWithinProfileAndClear() {
        guard()
        val one = VoiceSettings(context, "voice-one"); val two = VoiceSettings(context, "voice-two")
        try {
            one.save(VoiceConfiguration("http://127.0.0.1:18647/transcriptions", "separate-speech-key", "fixture-whisper"))
            one.savePlayback("chat", "First sentence completed.", true)
            one.saveTranscript("chat", "Unsent words.")
            assertEquals("separate-speech-key", VoiceSettings(context, "voice-one").read().key)
            assertTrue(two.read().endpoint.isBlank()); assertNull(two.playback("chat")); assertNull(one.playback("other-chat"))
            assertEquals("Unsent words.", VoiceSettings(context, "voice-one").pending("chat")); assertTrue(two.pending("chat").isBlank())
            assertTrue(one.interrupted("chat")); assertEquals("First sentence completed.", one.playback("chat"))
            assertTrue(one.clear()); assertTrue(one.read().key.isBlank()); assertNull(one.playback("chat"))
        } finally { one.clear(); two.clear() }
    }
    @Test fun wavTranscriptionUsesExplicitLoopbackServiceAndIndependentKey() {
        guard()
        val client = PrivateTranscription("http://127.0.0.1:18647/transcriptions", "separate-speech-key", "fixture-whisper")
        try { assertEquals("Check the fixture.", client.transcribe(wav(ByteArray(3200))).getOrThrow()) }
        finally { client.close() }
    }
    @Test fun foregroundVoiceSurvivesAppBackgroundAndEndsWithoutRestartingMic() {
        guard()
        val runtime = BellpullRuntime.get(context)
        val id = "voice-service-fixture"
        val config = VoiceSettings(context, id)
        val client = HermesClient("http://127.0.0.1:18646", { "voice-device-hermes-key" })
        val capabilities = (client.capabilities() as HermesResult.Ok).value
        config.save(VoiceConfiguration("http://127.0.0.1:18647/transcriptions", "separate-speech-key", "fixture-whisper"))
        ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
            try {
                scene.onActivity { activity ->
                    runtime.prepare(id)
                    val store = runtime.connect(client, capabilities)
                    store.openSession("fixture-id-1")
                    activity.setContent { HermesTheme { VoicePanel(runtime.voice, config.read(), runtime.voiceStartError, {}, {}, {}) } }
                }
                await { var loaded = false; main { loaded = runtime.store?.history is UiState.Ready }; loaded }
                scene.onActivity { activity ->
                    runtime.voiceRequestedSession = "fixture-id-1"; runtime.voiceRequestedMode = VoiceMode.DICTATION
                    activity.startForegroundService(Intent(activity, VoiceService::class.java))
                }
                await { var live = false; main { live = runtime.voice?.state == VoiceState.LISTENING }; live }
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                Thread.sleep(1500)
                main { assertNotNull(runtime.voice); assertNotEquals(VoiceState.STOPPED, runtime.voice?.state) }
                context.startService(Intent(context, VoiceService::class.java).setAction(VoiceService.PAUSE))
                await { var paused = false; main { paused = runtime.voice?.state == VoiceState.PAUSED }; paused }
                context.startService(Intent(context, VoiceService::class.java).setAction(VoiceService.STOP))
                await { var ended = false; main { ended = runtime.voice == null }; ended }
                Thread.sleep(300); main { assertNull(runtime.voice) }
            } finally {
                main { runtime.disconnect() }; config.clear(); client.close()
            }
        }
    }
}
