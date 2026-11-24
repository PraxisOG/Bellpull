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
                main { output.chime() }; Thread.sleep(200)
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
                    putString("ready_chime", "90ms native ToneGenerator, 12 percent voice-call volume")
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
    /** Feed the two named utterances through the disposable emulator's virtual microphone. */
    @Test fun localSpeechInterruptsASilentRunAndDeliversTheSecondUtterance() {
        guard()
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.containsKey("bargeSession"))
        val chat = requireNotNull(args.getString("bargeSession"))
        val firstWord = requireNotNull(args.getString("firstWord"))
        val nextWord = requireNotNull(args.getString("nextWord"))
        val runtime = BellpullRuntime.get(context)
        val client = HermesClient("http://127.0.0.1:18657", { "native-attachment-fixture-key" })
        val caps = (client.capabilities() as HermesResult.Ok).value
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val profile = "barge-in-" + java.util.UUID.randomUUID()
        val saved = VoiceSettings(context, profile)
        var voice: VoiceSession? = null
        val ui = DeviceFixtureUi
        val noPlayback = object : VoiceOutput {
            override fun prepare(onReady: (String) -> Unit, onError: (String) -> Unit) = onReady("silent fixture")
            override fun speak(text: String, started: () -> Unit, complete: () -> Unit) { started(); complete() }
            override fun stop() {}
            override fun close() {}
        }
        ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
            try {
                lateinit var store: HermesStore
                scene.onActivity { activity ->
                    runtime.prepare(profile)
                    store = runtime.connect(client, caps)
                    store.openSession(chat)
                    activity.setContent { HermesTheme { Text(store.runPresentation?.label ?: "Voice fixture ready") } }
                }
                ui.awaitMain("Fixture chat did not load") { store.history is UiState.Ready }
                main {
                    assertNull(runtime.speechLab.voiceStartProblem())
                    voice = VoiceSession(chat, profile, scope, StoreVoiceConversation(store, chat),
                        VoiceConfiguration(concise = false, announcements = VoiceAnnouncements(working = false)),
                        noPlayback, AndroidVoiceCapture(scope), transcriber = runtime.speechLab::voiceTranscriber,
                        onTranscript = { saved.saveTranscript(chat, it) }, onSubmission = { saved.saveRequest(chat, it) })
                    voice!!.start(VoiceMode.CONVERSATION)
                }
                ui.awaitMain("First microphone capture did not start") { voice?.state == VoiceState.LISTENING }
                instrumentation.sendStatus(0, Bundle().apply { putString("microphone", "first") })
                var original = ""
                ui.awaitMain("First recognized turn was not admitted") {
                    val record = store.runs?.states?.get(chat)?.submission
                    original = record?.runId.orEmpty()
                    original.isNotEmpty() && record!!.text.contains(firstWord, ignoreCase = true)
                }
                instrumentation.sendStatus(0, Bundle().apply { putString("microphone", "replacement") })
                ui.awaitMain("Replacement was not acknowledged in the original chat") {
                    val record = store.runs?.states?.get(chat)?.submission
                    record?.runId != null && record.runId != original && record.historySynced &&
                        record.text.contains(nextWord, ignoreCase = true) &&
                        record.completionOutput == "Replacement received on Hermes."
                }
                val history = (client.completeHistory(chat) as HermesResult.Ok).value.data
                val requests = history.filter { it.role == "user" }
                assertEquals(2, requests.size)
                assertTrue(requests.first().content.orEmpty().contains(firstWord, ignoreCase = true))
                assertTrue(requests.last().content.orEmpty().contains(nextWord, ignoreCase = true))
                assertEquals(1, history.count { it.role == "assistant" && it.content == "Replacement received on Hermes." })
                assertEquals("completed", (client.runStatus(original) as HermesResult.Ok).value.status)
                val journal = SessionCache(context, profile).readRuns().filter { it.sessionId == chat }
                assertEquals(2, journal.size)
                assertTrue(journal.single { it.runId == original }.let { it.terminal && it.stopRequested })
                main {
                    assertEquals("", saved.pending(chat))
                    assertNull(saved.pendingRequest(chat))
                }
            } catch (failure: AssertionError) {
                main {
                    val record = runtime.store?.runs?.states?.get(chat)
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("voice", voice?.state?.name)
                        putString("notice", voice?.notice)
                        putString("run", record?.submission?.status)
                        putBoolean("busy", record?.busy == true)
                        putBoolean("replacement_saved", saved.pending(chat).contains(nextWord, ignoreCase = true))
                    })
                }
                throw failure
            } finally {
                main { voice?.close(); runtime.disconnect() }
                scope.cancel(); saved.clear(); client.close()
            }
        }
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
                main { assertNotNull(runtime.voice); assertEquals(VoiceState.LISTENING, runtime.voice?.state) }
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
