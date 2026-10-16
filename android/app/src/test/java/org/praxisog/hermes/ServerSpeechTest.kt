package org.praxisog.hermes

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServerSpeechTest {
    private class Transport : ServerSpeechTransport {
        var result: () -> String = { "server result" }
        var audio = byteArrayOf(0, 0, -1, 127)
        var closed = false
        override fun tts(text: String, cancelled: () -> Boolean): InputStream = ByteArrayInputStream(audio)
        override fun stt(wav: ByteArray) = result()
        override fun cancel() { closed = true }
    }
    private class Sink : LocalTtsSink {
        val chunks = mutableListOf<Float>()
        var cancelled = false
        override fun isCancelled() = cancelled
        override fun onChunk(samples: FloatArray): Boolean { chunks.addAll(samples.toList()); return !cancelled }
    }

    @Test fun profileSettingsPersistServerEngineAndExplicitInputFlag() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("server-speech", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val settings = VoiceSettings(prefs)
        assertFalse(settings.read().transcribeViaServer)
        val value = VoiceConfiguration(transcribeViaServer = true,
            output = VoiceOutputConfiguration(engine = VoiceOutputEngine.SERVER_MOSS))
        settings.save(value)
        assertEquals("server-moss", prefs.getString("output", null))
        assertTrue(prefs.getBoolean("stt_server", false))
        assertEquals(value, VoiceSettings(prefs).read())
        settings.save(value.copy(transcribeViaServer = false))
        assertFalse(settings.read().transcribeViaServer)
        val other = VoiceSettings(app.getSharedPreferences("server-speech-other", Context.MODE_PRIVATE))
        other.clear()
        assertFalse(other.read().transcribeViaServer)
        assertEquals(VoiceOutputEngine.SYSTEM, other.read().output.engine)
        assertNull(VoiceOutputLab(app).startProblem(value))
    }

    @Test fun pcmDecodesSignedLittleEndianAcrossSingleByteReads() {
        val bytes = byteArrayOf(0, -128, -1, 127, 0, 0, -1, -1)
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(1, len))
        }
        val sink = Sink()
        var first = 0
        assertEquals(4L, decodeServerPcm(input, sink) { first++ })
        assertEquals(1, first)
        assertArrayEquals(floatArrayOf(-1f, 32767f / 32768, 0f, -1f / 32768), sink.chunks.toFloatArray(), 0f)
    }

    @Test fun truncatedAndEmptyPcmFailAndCancellationDoesNotEmitAudio() {
        assertThrows(IllegalStateException::class.java) { decodeServerPcm(ByteArrayInputStream(byteArrayOf(1)), Sink()) }
        assertThrows(IllegalStateException::class.java) { decodeServerPcm(ByteArrayInputStream(byteArrayOf()), Sink()) }
        val sink = Sink().also { it.cancelled = true }
        assertThrows(CancellationException::class.java) { decodeServerPcm(ByteArrayInputStream(byteArrayOf(0, 0)), sink) }
        assertTrue(sink.chunks.isEmpty())
    }

    @Test fun successfulPhraseReportsFirstPcmTotalAnd48KhzDuration() {
        val transport = Transport()
        transport.audio = ByteArray(96000)
        var timing: ServerSpeechTiming? = null
        val engine = ServerMossEngine(transport) { timing = it }
        assertEquals(0, engine.synthesize("hello", VoiceOutputConfiguration(engine = VoiceOutputEngine.SERVER_MOSS), Sink()))
        val measured = checkNotNull(timing)
        assertNotNull(measured.firstPcmMs)
        assertTrue(measured.totalMs >= checkNotNull(measured.firstPcmMs))
        assertEquals(1.0, measured.audioSeconds, 0.0)
        engine.close()
        assertTrue(transport.closed)
    }

    @Test fun serverSuccessSkipsLocalAndFailureFallsBackUsingIdenticalWav() {
        val transport = Transport()
        val wav = byteArrayOf(1, 2, 3)
        var attempts = 0
        var localClosed = false
        val transcriber = ServerVoiceTranscriber(transport) {
            attempts++
            object : VoiceTranscriber {
                override fun transcribe(wavReceived: ByteArray): Result<String> {
                    assertSame(wav, wavReceived)
                    return Result.success("local result")
                }
                override fun close() { localClosed = true }
            }
        }
        assertEquals("server result", transcriber.transcribe(wav).getOrThrow())
        assertEquals(0, attempts)
        transport.result = { throw IOException("helper unavailable") }
        assertEquals("local result", transcriber.transcribe(wav).getOrThrow())
        assertEquals(1, attempts)
        transcriber.close()
        assertTrue(localClosed)
        assertTrue(transport.closed)
    }

    @Test fun noInstalledModelPreservesServerErrorAndCancellationNeverFallsBack() {
        val transport = Transport()
        val failure = IOException("helper unavailable")
        transport.result = { throw failure }
        assertSame(failure, ServerVoiceTranscriber(transport) { null }.transcribe(byteArrayOf()).exceptionOrNull())
        transport.result = { throw CancellationException("cancelled") }
        val transcriber = ServerVoiceTranscriber(transport) { error("Cancellation must not load a model") }
        assertTrue(transcriber.transcribe(byteArrayOf()).exceptionOrNull() is CancellationException)
        transcriber.close()
        assertTrue(transcriber.transcribe(byteArrayOf()).exceptionOrNull() is CancellationException)
    }

    @Test fun legacyStandaloneSelectionRequiresExplicitChoiceInsteadOfContactingOldHost() {
        assertNotNull(VoiceConfiguration(endpoint = "https://speech.invalid/transcribe").startProblem { null })
        assertNull(VoiceConfiguration(endpoint = "https://speech.invalid/transcribe", transcribeViaServer = true).startProblem {
            error("Server input does not need weights")
        })
    }

    @Test fun stoppingPlaybackCancelsBlockedTransportAndClosesItsStreamWithoutCompletion() {
        val reading = CountDownLatch(1)
        val cancellation = CountDownLatch(1)
        val streamClosed = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val transport = object : ServerSpeechTransport {
            override fun tts(text: String, cancelled: () -> Boolean): InputStream = object : InputStream() {
                override fun read(): Int = error("Use bulk read")
                override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                    reading.countDown()
                    check(cancellation.await(3, TimeUnit.SECONDS))
                    throw IOException("Cancelled stream")
                }
                override fun close() { streamClosed.countDown() }
            }
            override fun stt(wav: ByteArray) = error("Not an STT test")
            override fun cancel() { if (reading.count == 0L) cancellation.countDown() }
        }
        val runtime = LocalTtsRuntime({}) { ServerMossEngine(transport) {} }
        val voice = LocalVoiceOutput(runtime, VoiceOutputConfiguration(engine = VoiceOutputEngine.SERVER_MOSS), 1f,
            { false }, player = { _, _ -> object : VoicePcmPlayer {
                override fun write(samples: FloatArray) {}
                override suspend fun drain() {}
                override fun stop() {}
                override fun close() {}
            } }, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), callbacks = Dispatchers.Unconfined,
            cancelSynthesis = transport::cancel)
        try {
            voice.speak("hello", {}, { completed.set(true) })
            assertTrue(reading.await(3, TimeUnit.SECONDS))
            voice.stop()
            assertTrue(streamClosed.await(3, TimeUnit.SECONDS))
            assertFalse(completed.get())
        } finally { voice.close(); runtime.unload() }
    }
}
