package org.praxisog.hermes

import java.io.ByteArrayOutputStream
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class StreamingVoiceSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val providers = mutableListOf<Provider>()
    private var sent = 0
    private var captureStarts = 0
    private class Provider : StreamingVoiceTranscriber {
        var partial: ((String) -> Unit)? = null
        val pcm = ByteArrayOutputStream()
        var closed = false
        var finished = false
        override fun begin(partial: (String) -> Unit) { this.partial = partial }
        override fun audio(pcm: ByteArray) { check(!closed); this.pcm.write(pcm) }
        override fun transcribe(wav: ByteArray): Result<String> = runCatching {
            check(!closed)
            assertArrayEquals(wav.copyOfRange(44, wav.size), pcm.toByteArray())
            finished = true
            "The final recognized words"
        }
        override fun close() { closed = true }
    }
    private val voice = VoiceSession("chat", "profile", scope, object : VoiceConversation {
        var run by mutableStateOf(VoiceRun())
        override val selected = true
        override val ready = true
        override fun run() = run
        override fun send(text: String): Boolean { sent++; run = VoiceRun("run", busy = true); return true }
        override fun cancel() {}
    }, VoiceConfiguration(transcribeViaServer = true, concise = false, announcements = VoiceAnnouncements(working = false)),
        object : VoiceOutput {
            override fun prepare(onReady: (String) -> Unit, onError: (String) -> Unit) = onReady("fixture")
            override fun speak(text: String, started: () -> Unit, complete: () -> Unit) { started(); complete() }
            override fun stop() {}
            override fun close() {}
        }, object : VoiceCapture {
            override fun start(frame: (ShortArray, Int) -> Unit, error: (String) -> Unit): Boolean { captureStarts++; return true }
            override fun close() {}
        }, transcriber = { Provider().also(providers::add) })

    private fun frames(n: Int, amplitude: Short) = repeat(n) { voice.frame(ShortArray(320) { amplitude }, 320) }
    private fun await(condition: () -> Boolean) {
        repeat(200) { Snapshot.sendApplyNotifications(); if (condition()) return; Thread.sleep(10) }
        fail("Timed out: ${voice.state}")
    }
    @After fun close() { voice.close(); scope.cancel() }

    @Test fun partialIsVisibleBeforeStopButOnlyFinalSendsAndPcmMatchesFullWav() {
        voice.start(VoiceMode.CALL)
        frames(12, 0); frames(25, 2500)
        assertEquals(1, providers.size)
        providers[0].partial!!("unfinished words")
        assertEquals("unfinished words", voice.partialTranscript)
        assertEquals("", voice.transcript)
        assertEquals(0, sent)
        frames(90, 0)
        await { sent == 1 }
        assertTrue(providers[0].finished)
        assertEquals("", voice.partialTranscript)
        assertEquals(1, captureStarts)
        providers[0].partial!!("late words")
        assertEquals("", voice.partialTranscript)
        assertEquals(1, sent)
    }

    @Test fun muteAndPauseCancelStreamsAndIgnoreLatePartials() {
        voice.start(VoiceMode.CALL)
        frames(25, 2500)
        val old = providers.single()
        voice.updateMicrophoneMute(true)
        assertTrue(old.closed)
        old.partial!!("discard me")
        assertEquals("", voice.partialTranscript)
        assertEquals(0, sent)
        voice.updateMicrophoneMute(false)
        frames(25, 2500)
        assertEquals(2, providers.size)
        voice.pause()
        assertTrue(providers[1].closed)
        providers[1].partial!!("discard this too")
        assertEquals("", voice.partialTranscript)
        assertEquals(0, sent)
    }

    @Test fun softPushToTalkStreamsFirstFrameAndFinalizesBeforeClose() {
        voice.start(VoiceMode.PUSH_TO_TALK)
        voice.beginPushToTalk()
        frames(25, 100)
        assertEquals(25 * 320 * 2, providers.single().pcm.size())
        voice.endPushToTalk()
        await { sent == 1 }
        assertTrue(providers.single().finished)
    }
}
