package org.praxisog.hermes

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MossVoiceOutputTest {
    @Test fun shippedManifestMatchesAllSixteenVerifiedAssets() {
        val manifest = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "docs/MOSS-NANO-MANIFEST.txt") }.first { it.isFile }
        val expected = manifest.readLines().filter { it.isNotBlank() && !it.startsWith('#') }.map { line ->
            val (name, bytes, sha256) = line.trim().split(Regex("\\s+"))
            VoiceModelFile(name, bytes.toLong(), sha256)
        }
        assertEquals(16, expected.size)
        assertEquals(expected, MossModel.model.files)
        assertEquals("moss-nano-onnx", MossModel.model.id)
        assertEquals(expected.sumOf { it.bytes }, MossModel.model.bytes)
        assertEquals(16, MossModel.model.files.map { it.name }.distinct().size)
    }

    @Test fun downloadUrlsUseTheCorrectRepositoryAndMainBranch() {
        val codec = setOf("codec_browser_onnx_meta.json", "moss_audio_tokenizer_decode_full.onnx",
            "moss_audio_tokenizer_decode_shared.data", "moss_audio_tokenizer_decode_step.onnx",
            "moss_audio_tokenizer_encode.data", "moss_audio_tokenizer_encode.onnx")
        for (file in MossModel.model.files) {
            val repository = if (file.name in codec) "OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX"
                else "OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX"
            val uri = voiceModelUri(file, MossModel.model)
            assertEquals("https://hf.co/$repository/resolve/main/${file.name}", uri.toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            MossModel.uri(VoiceModelFile("unknown.onnx", 1, "0".repeat(64)))
        }
    }

    @Test fun mossReplacesPocketInSelectableEnginesAndDecodesLegacyIds() {
        assertEquals(listOf("system", "kokoro", "qwen", "moss", "server-moss"), VoiceOutputEngine.entries.map { it.id })
        assertEquals("MOSS-TTS-Nano · on device", VoiceOutputEngine.MOSS.label)
        for (engine in VoiceOutputEngine.entries) assertEquals(engine, VoiceOutputEngine.decode(engine.id))
        for (id in listOf("pocket", "POCKET", "moss", "MOSS"))
            assertEquals(VoiceOutputEngine.MOSS, VoiceOutputEngine.decode(id))
        for (id in listOf(null, "", "unknown-engine"))
            assertEquals(VoiceOutputEngine.SYSTEM, VoiceOutputEngine.decode(id))
    }

    @Test fun mossSelectionReachesSynthesisAndAnnouncesJunhao() {
        val ready = CountDownLatch(1)
        val done = CountDownLatch(1)
        val config = VoiceOutputConfiguration(engine = VoiceOutputEngine.MOSS, seed = Long.MAX_VALUE)
        var announced: String? = null
        var received: VoiceOutputConfiguration? = null
        var phrase: String? = null
        val runtime = LocalTtsRuntime({}) { object : LocalTtsEngine {
            override fun synthesize(text: String, config: VoiceOutputConfiguration, sink: LocalTtsSink): Int {
                received = config; phrase = text
                return if (sink.onChunk(floatArrayOf(.1f))) 0 else -5
            }
            override fun close() {}
        } }
        val voice = LocalVoiceOutput(runtime, config, 1f, { false }, player = { _, _ -> object : VoicePcmPlayer {
            override fun write(samples: FloatArray) {}
            override suspend fun drain() {}
            override fun stop() {}
            override fun close() {}
        } }, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), callbacks = Dispatchers.Unconfined)
        try {
            voice.prepare({ announced = it; ready.countDown() }, { fail(it) })
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            assertEquals("MOSS-TTS-Nano · Junhao · CPU", announced)
            voice.speak("A MOSS preview", {}, { done.countDown() })
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertEquals(config, received)
            assertEquals("A MOSS preview", phrase)
        } finally { voice.close(); runtime.unload() }
    }

    @Test fun growingPrefixesStreamBeforeFinalAndNeverReplayTheHeldTail() {
        val chunks = mutableListOf<FloatArray>()
        val sink = object : LocalTtsSink {
            override fun isCancelled() = false
            override fun onChunk(samples: FloatArray): Boolean { chunks += samples; return true }
        }
        val pcm = MossPcmStream(24000, sink)
        assertTrue(pcm.emit(FloatArray(26400) { it.toFloat() }, final = false))
        assertEquals(2400, chunks.sumOf { it.size })
        assertTrue(pcm.emit(FloatArray(28800) { it.toFloat() }, final = false))
        assertEquals(4800, chunks.sumOf { it.size })
        assertTrue(pcm.emit(FloatArray(30000) { it.toFloat() }, final = true))
        assertEquals((0 until 30000).map { it.toFloat() }, chunks.flatMap { it.toList() })
        assertTrue(chunks.all { it.size <= 2400 })
    }

    @Test fun cancellationStopsPrefixDeliveryBetweenChunks() {
        var cancelled = false
        var calls = 0
        val pcm = MossPcmStream(0, object : LocalTtsSink {
            override fun isCancelled() = cancelled
            override fun onChunk(samples: FloatArray): Boolean { calls++; cancelled = true; return true }
        })
        assertFalse(pcm.emit(FloatArray(7200), final = false))
        assertEquals(1, calls)
        assertFalse(pcm.emit(FloatArray(9600), final = true))
        assertEquals(1, calls)
    }

    @Test fun stoppedSinkAndNonFinitePcmFailInsteadOfContinuingPlayback() {
        val stopped = MossPcmStream(0, object : LocalTtsSink {
            override fun isCancelled() = false
            override fun onChunk(samples: FloatArray) = false
        })
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            stopped.emit(FloatArray(4800), final = true)
        }
        val invalid = MossPcmStream(0, object : LocalTtsSink {
            override fun isCancelled() = false
            override fun onChunk(samples: FloatArray): Boolean { fail("Invalid PCM reached sink"); return true }
        })
        assertThrows(IllegalStateException::class.java) { invalid.emit(floatArrayOf(Float.NaN), final = true) }
    }
}
