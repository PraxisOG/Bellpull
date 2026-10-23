package org.praxisog.hermes

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.os.SystemClock
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.HermesResult

/** Real Telecom, foreground service, notification actions, and screen-off capture.
 * Only synthetic chat/transcription endpoints in the isolated fixture sandbox.
 */
@RunWith(AndroidJUnit4::class)
class TelecomVoiceDeviceTest {
    @Test fun systemCallSurvivesScreenOffHoldsResumesAndDisconnects() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "org.praxisog.hermes.fixture")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun await(predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 25000
            while (!predicate()) {
                if (SystemClock.elapsedRealtime() >= end) fail("Telecom condition timed out")
                Thread.sleep(50)
            }
        }
        val runtime = BellpullRuntime.get(context)
        val settings = VoiceSettings(context, "telecom-fixture")
        val telecom = context.getSystemService(TelecomManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        fun awaitSpeaker() {
            await { audio.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        }
        val client = HermesClient("http://127.0.0.1:18646", { "voice-device-hermes-key" })
        val capabilities = (client.capabilities() as HermesResult.Ok).value
        settings.save(VoiceConfiguration("http://127.0.0.1:18647/transcriptions", "separate-speech-key", "fixture-whisper",
            output = if (InstrumentationRegistry.getArguments().getString("kokoro") == "true")
                VoiceOutputConfiguration(engine = VoiceOutputEngine.KOKORO) else VoiceOutputConfiguration()))
        instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.READ_PHONE_STATE)
        ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
            try {
                assertFalse("Run only while no other call is active", telecom.isInCall)
                assertFalse("Disconnect car/headsets for the phone-speaker check",
                    audio.availableCommunicationDevices.any { it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET) })
                scene.onActivity {
                    runtime.prepare("telecom-fixture")
                    runtime.connect(client, capabilities).openSession("fixture-id-1")
                }
                await { var ready = false; main { ready = runtime.store?.history is UiState.Ready }; ready }
                scene.onActivity {
                    runtime.voiceRequestedSession = "fixture-id-1"
                    runtime.voiceRequestedMode = VoiceMode.CALL
                    it.startForegroundService(Intent(it, VoiceService::class.java))
                }
                await { var listening = false; main { listening = runtime.voice?.state == VoiceState.LISTENING }; listening }
                assertTrue(telecom.isInCall)
                assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.mode)
                awaitSpeaker()
                instrumentation.uiAutomation.executeShellCommand("input keyevent 223").close()
                Thread.sleep(10000)
                assertTrue(telecom.isInCall)
                awaitSpeaker()
                main { assertNotNull(runtime.voice); assertNotEquals(VoiceState.PAUSED, runtime.voice?.state) }
                context.startService(Intent(context, VoiceService::class.java).setAction(VoiceService.PAUSE))
                await { var paused = false; main { paused = runtime.voice?.state == VoiceState.PAUSED }; paused }
                assertTrue("Hold retains the system call", telecom.isInCall)
                context.startService(Intent(context, VoiceService::class.java).setAction(VoiceService.RESUME))
                await { var listening = false; main { listening = runtime.voice?.state == VoiceState.LISTENING }; listening }
                assertTrue(telecom.isInCall)
                awaitSpeaker()
                context.startService(Intent(context, VoiceService::class.java).setAction(VoiceService.STOP))
                await { !telecom.isInCall }
                await { var ended = false; main { ended = runtime.voice == null }; ended }
                assertEquals(AudioManager.MODE_NORMAL, audio.mode)
                // The reported crash is the in-app close path, after STOPPED posts a
                // Compose emission. Exercise it repeatedly while the activity survives.
                instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close()
                repeat(3) {
                    scene.onActivity {
                        runtime.voiceRequestedSession = "fixture-id-1"
                        runtime.voiceRequestedMode = VoiceMode.CALL
                        it.startForegroundService(Intent(it, VoiceService::class.java))
                    }
                    await { var listening = false; main { listening = runtime.voice?.state == VoiceState.LISTENING }; listening }
                    awaitSpeaker()
                    main { runtime.voice?.close() }
                    await { var ended = false; main { ended = runtime.voice == null }; ended }
                    await { !telecom.isInCall }
                    main { assertTrue(context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.none { it.id == 2405 }) }
                    assertEquals(AudioManager.MODE_NORMAL, audio.mode)
                }

            } finally {
                instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close()
                context.startService(Intent(context, VoiceService::class.java).setAction(VoiceService.STOP))
                main { runtime.disconnect() }
                settings.clear(); client.close()
                instrumentation.uiAutomation.dropShellPermissionIdentity()
            }
        }
    }
}
