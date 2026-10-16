package org.praxisog.hermes

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CallModeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val conversation = object : VoiceConversation {
        var current by mutableStateOf(VoiceRun())
        var sends = 0
        override val selected = true
        override val ready = true
        override fun run() = current
        override fun send(text: String): Boolean {
            sends++
            current = VoiceRun("turn-$sends", busy = true)
            return true
        }
        override fun cancel() {}
    }
    private val capture = object : VoiceCapture {
        var active = false
        var starts = 0
        var stops = 0
        override fun start(frame: (ShortArray, Int) -> Unit, error: (String) -> Unit): Boolean {
            check(!active)
            active = true; starts++
            return true
        }
        override fun close() { if (active) stops++; active = false }
    }
    private val output = object : VoiceOutput {
        var chimes = 0
        var completion: (() -> Unit)? = null
        override fun chime() { chimes++ }
        override fun prepare(onReady: (String) -> Unit, onError: (String) -> Unit) = onReady("test")
        override fun speak(text: String, started: () -> Unit, complete: () -> Unit) {
            completion = complete; started()
        }
        override fun stop() { completion = null }
        override fun close() = stop()
    }
    @Volatile private var holdRecognition: CountDownLatch? = null
    @Volatile private var failRecognition = false
    private val voice = VoiceSession("chat", "profile", scope, conversation,
        VoiceConfiguration(concise = false, announcements = VoiceAnnouncements(working = false)), output, capture,
        transcriber = { object : VoiceTranscriber {
            override fun transcribe(wav: ByteArray): Result<String> {
                holdRecognition?.await(3, TimeUnit.SECONDS)
                return if (failRecognition) Result.failure(IllegalStateException("Offline"))
                    else Result.success("Tell me something about mountains")
            }
            override fun close() {}
        } })
    private fun await(condition: () -> Boolean) {
        repeat(300) {
            Snapshot.sendApplyNotifications()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out: ${voice.state}")
    }
    private fun record() {
        repeat(25) { voice.frame(ShortArray(320) { if (it % 2 == 0) 2500 else -2500 }, 320) }
        repeat(90) { voice.frame(ShortArray(320), 320) }
    }
    @After fun close() { holdRecognition?.countDown(); voice.close(); scope.cancel() }

    @Test fun oneCaptureAndNoBeepsAcrossOneHundredConversationTurns() {
        voice.start(VoiceMode.CALL)
        repeat(100) { turn ->
            assertEquals(VoiceState.LISTENING, voice.state)
            assertTrue(capture.active)
            record()
            await { conversation.sends == turn + 1 && voice.state == VoiceState.WORKING }
            conversation.current = VoiceRun("turn-${turn + 1}", text = "The answer is ready.", final = true)
            await { output.completion != null }
            assertTrue(capture.active)
            val done = output.completion!!; output.completion = null; done()
            await { voice.state == VoiceState.LISTENING }
        }
        assertEquals(100, conversation.sends)
        assertEquals(1, capture.starts)
        assertEquals(0, capture.stops)
        assertEquals(0, output.chimes)
        voice.close()
        assertFalse(capture.active)
        assertEquals(1, capture.stops)
    }

    @Test fun recognitionKeepsCaptureOpenAndDiscardsFramesUntilItCompletes() {
        holdRecognition = CountDownLatch(1)
        voice.start(VoiceMode.CALL)
        record()
        assertEquals(VoiceState.TRANSCRIBING, voice.state)
        assertTrue(capture.active)
        repeat(3000) { voice.frame(ShortArray(320) { 2500 }, 320) }
        assertEquals(VoiceState.TRANSCRIBING, voice.state)
        holdRecognition!!.countDown()
        await { conversation.sends == 1 }
        assertEquals(1, capture.starts)
        assertEquals(0, capture.stops)
    }

    @Test fun pauseAndRealAudioInterruptionReleaseCaptureAndResumeOnlyOnce() {
        voice.start(VoiceMode.CALL)
        voice.pause()
        assertFalse(capture.active)
        voice.resume()
        assertTrue(capture.active)
        assertEquals(2, capture.starts)
        voice.suspendForAudio()
        assertFalse(capture.active)
        voice.recoverAudio()
        voice.recoverAudio()
        assertTrue(capture.active)
        assertEquals(3, capture.starts)
        assertEquals(0, output.chimes)
    }

    @Test fun failedTranscriptionReleasesMicAndRetrySendsOnlyOnce() {
        failRecognition = true
        voice.start(VoiceMode.CALL)
        record()
        await { voice.state == VoiceState.PAUSED }
        assertFalse(capture.active)
        assertTrue(voice.canRetryTranscription)
        failRecognition = false
        voice.retryTranscription()
        await { conversation.sends == 1 }
        assertTrue(capture.active)
        assertEquals(2, capture.starts)
    }

    @Test fun switchingToPushToTalkReleasesContinuousCapture() {
        voice.start(VoiceMode.CALL)
        voice.chooseMode(VoiceMode.PUSH_TO_TALK)
        assertFalse(capture.active)
        assertEquals(VoiceState.READY, voice.state)
        voice.chooseMode(VoiceMode.CALL)
        assertTrue(capture.active)
        assertEquals(VoiceState.LISTENING, voice.state)
        assertEquals(0, output.chimes)
    }

    @Test fun softHeldSpeechReachesRecognitionWithoutHandsFreeVolumeThreshold() {
        voice.start(VoiceMode.PUSH_TO_TALK)
        voice.beginPushToTalk()
        repeat(25) { voice.frame(ShortArray(320) { if (it % 2 == 0) 100 else -100 }, 320) }
        voice.endPushToTalk()
        await { conversation.sends == 1 }
    }
}
