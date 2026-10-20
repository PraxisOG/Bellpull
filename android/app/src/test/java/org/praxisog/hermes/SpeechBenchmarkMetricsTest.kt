package org.praxisog.hermes

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpeechBenchmarkMetricsTest {
    @Test fun lexicalWerPreservesDecimalsAndHandlesInsertionsDeletionsAndApostrophes() {
        assertEquals(0, wordErrors("It’s 3.5 inches!", "IT'S 3.5 inches").edits)
        assertEquals(1, wordErrors("cut 3.5 inches", "cut 35 inches").edits)
        assertEquals(3, wordErrors("cut two inches", "please cut inches now").edits)
        assertEquals(3, wordErrors("one two three", "").edits)
        assertNull(wordErrors("", "noise").rate)
        assertEquals(2.0, wordErrors("one", "one two three").rate!!, 0.0)
    }
    @Test fun wavDecodesSignedSamplesAndSkipsUnknownOddLengthChunks() {
        val original = wav(ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(Short.MIN_VALUE).putShort(0).putShort(Short.MAX_VALUE).array())
        val withJunk = original.take(12).toByteArray() + "JUNK".toByteArray() +
            byteArrayOf(1, 0, 0, 0, 42, 0) + original.drop(12).toByteArray()
        ByteBuffer.wrap(withJunk).order(ByteOrder.LITTLE_ENDIAN).putInt(4, withJunk.size - 8)
        val audio = SpeechAudio.readWav(withJunk)
        assertEquals(3, audio.samples.size)
        assertEquals(-1f, audio.samples[0], 0f)
        assertEquals(0f, audio.samples[1], 0f)
        assertEquals(32767f / 32768, audio.samples[2], 0f)
    }
    @Test fun malformedAndWrongRateAudioNeverReachesRecognizer() {
        val original = wav(ByteArray(3200))
        assertThrows(IllegalArgumentException::class.java) { SpeechAudio.readWav(original.copyOf(80)) }
        val wrongRate = original.copyOf()
        ByteBuffer.wrap(wrongRate).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 48000)
        assertThrows(IllegalArgumentException::class.java) { SpeechAudio.readWav(wrongRate) }
        val overflow = original.copyOf()
        ByteBuffer.wrap(overflow).order(ByteOrder.LITTLE_ENDIAN).putInt(40, -1)
        assertThrows(IllegalArgumentException::class.java) { SpeechAudio.readWav(overflow) }
    }
    @Test fun switchingClosesPreviousModelBeforeConstructingNextAndWarmRunsReuseIt() {
        val events = mutableListOf<String>()
        val runtime = SpeechModelRuntime({}, { model ->
            events += "load ${model.id}"
            object : LocalSpeechEngine {
                override val actualBackend = "CPU"
                override val runtimeVersion = "Test runtime"
                override fun start(onUpdate: (SpeechUpdate) -> Unit) {}
                override fun accept(samples: FloatArray) {}
                override fun finish() = ""
                override fun transcribe(audio: SpeechAudio) = ""
                override fun close() { events += "close ${model.id}" }
            }
        })
        val tiny = SpeechModel("tiny", "Tiny", 2, "checkpoint", "INT8", emptyList())
        val medium = tiny.copy(id = "medium")
        runtime.withModel(tiny) { _, _ -> }
        runtime.withModel(tiny) { _, loading -> assertEquals(0.0, loading, 0.0) }
        runtime.withModel(medium) { _, _ -> }
        runtime.close()
        assertEquals(listOf("load tiny", "close tiny", "load medium", "close medium"), events)
    }
    @Test fun failedSwitchLeavesNoOldModelAsSilentFallback() {
        var closed = false
        val runtime = SpeechModelRuntime({ model -> check(model.id != "missing") }, {
            object : LocalSpeechEngine {
                override val actualBackend = "CPU"
                override val runtimeVersion = "Test runtime"
                override fun start(onUpdate: (SpeechUpdate) -> Unit) {}
                override fun accept(samples: FloatArray) {}
                override fun finish() = ""
                override fun transcribe(audio: SpeechAudio) = ""
                override fun close() { closed = true }
            }
        })
        val tiny = SpeechModel("tiny", "Tiny", 2, "checkpoint", "INT8", emptyList())
        runtime.withModel(tiny) { _, _ -> }
        assertThrows(IllegalStateException::class.java) { runtime.withModel(tiny.copy(id = "missing")) { _, _ -> } }
        assertTrue(closed)
        assertNull(runtime.loadedModel)
    }
    @Test fun interruptedInferenceUnloadsStreamBeforeRetry() {
        var closes = 0
        var loads = 0
        val runtime = SpeechModelRuntime({}, {
            loads++
            object : LocalSpeechEngine {
                override val actualBackend = "CPU"
                override val runtimeVersion = "Test runtime"
                override fun start(onUpdate: (SpeechUpdate) -> Unit) {}
                override fun accept(samples: FloatArray) {}
                override fun finish() = ""
                override fun transcribe(audio: SpeechAudio) = ""
                override fun close() { closes++ }
            }
        })
        val tiny = SpeechModel("tiny", "Tiny", 2, "checkpoint", "INT8", emptyList())
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runtime.withModel(tiny) { _, _ -> throw kotlinx.coroutines.CancellationException() }
        }
        assertNull(runtime.loadedModel)
        assertThrows(IllegalStateException::class.java) { runtime.withModel(tiny) { _, _ -> } }
        runtime.allowRetry() // Explicit Resume / Retry bypasses the automatic load cooldown.
        runtime.withModel(tiny) { _, _ -> }
        assertEquals(2, loads); assertEquals(1, closes)
        runtime.close()
    }
}
