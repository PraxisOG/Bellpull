package org.praxisog.hermes

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AssistantSpeechTest {
    private class Capture : VoiceCapture {
        var frames: ((ShortArray, Int) -> Unit)? = null
        var closed = false
        override fun start(frame: (ShortArray, Int) -> Unit, error: (String) -> Unit): Boolean { frames = frame; return true }
        override fun close() { closed = true }
        fun utterance() { repeat(30) { frames!!(ShortArray(320) { 8000 }, 320) } }
    }
    @Test fun cancelClosesCaptureAndRejectsLateTranscription() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val capture = Capture()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var closed = false
        val results = mutableListOf<String>()
        val speech = AssistantSpeech(scope, capture, { object : VoiceTranscriber {
            override fun transcribe(wav: ByteArray): Result<String> {
                started.countDown(); release.await(5, TimeUnit.SECONDS)
                finished.countDown(); return Result.success("timer for 10 minutes")
            }
            override fun close() { closed = true }
        } }, results::add)
        try {
            speech.start(); capture.utterance(); speech.finish()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            speech.cancel(); release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            delay(100)
            assertTrue(capture.closed); assertTrue(closed)
            assertFalse(speech.listening); assertFalse(speech.busy); assertTrue(results.isEmpty())
        } finally { release.countDown(); scope.cancel() }
    }
    @Test fun silenceDoesNotStartTranscriptionAndMissingModelDoesNotOpenMicrophone() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val capture = Capture()
        var transcriptions = 0
        val speech = AssistantSpeech(scope, capture, { object : VoiceTranscriber {
            override fun transcribe(wav: ByteArray): Result<String> { transcriptions++; return Result.success("wrong") }
            override fun close() = Unit
        } }, { fail("Silence produced a result") })
        try {
            speech.start(); speech.finish()
            assertEquals(0, transcriptions); assertNotNull(speech.problem); assertTrue(capture.closed)
            val unavailable = Capture()
            val failed = AssistantSpeech(scope, unavailable, { error("Download a model") }, {})
            failed.start()
            assertNull(unavailable.frames); assertEquals("Download a model", failed.problem)
        } finally { speech.cancel(); scope.cancel() }
    }
}
