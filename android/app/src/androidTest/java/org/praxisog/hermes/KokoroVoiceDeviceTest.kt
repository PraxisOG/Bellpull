package org.praxisog.hermes

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class KokoroVoiceDeviceTest {
    /** Public fixture text only; compare the old 1.6s queue with sentence lookahead
     * using the same warmed Kokoro engine and the real phone AudioTrack.
     */
    @Test fun sentenceLookaheadStartsBeforePlaybackFinishes() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        check(app.packageName == "org.praxisog.hermes.fixture")
        val files = VoiceOutputFiles(app, KokoroModel.model)
        val phrases = listOf(
            "The little boat crossed the quiet lake before the sun went down.",
            "We watched the birds return to their nests in the trees.",
            "Then we walked home together and made a warm cup of tea.")
        val rows = JSONArray()
        var epoch = 0L
        val generationStarts = java.util.concurrent.CopyOnWriteArrayList<Double>()
        val generationEnds = java.util.concurrent.CopyOnWriteArrayList<Double>()
        val runtime = LocalTtsRuntime(files::verify) {
            val native = NativeKokoroEngine(files)
            object : LocalTtsEngine {
                override fun synthesize(text: String, config: VoiceOutputConfiguration, sink: LocalTtsSink): Int {
                    generationStarts += (System.nanoTime() - epoch) / 1e6
                    return native.synthesize(text, config, sink).also { generationEnds += (System.nanoTime() - epoch) / 1e6 }
                }
                override fun close() = native.close()
            }
        }
        try {
            withContext(Dispatchers.IO) { runtime.withModel { engine, _, _ ->
                engine.synthesize("A short warm up.", VoiceOutputConfiguration(engine = VoiceOutputEngine.KOKORO), object : LocalTtsSink {
                    override fun isCancelled() = false
                    override fun onChunk(samples: FloatArray) = true
                })
            } }
            for (sentenceBuffer in listOf(false, true)) {
                generationStarts.clear(); generationEnds.clear()
                val starts = mutableListOf<Double>(); val ends = mutableListOf<Double>()
                val ready = CompletableDeferred<Unit>(); val done = CompletableDeferred<Unit>()
                // QWEN selects the previous generic buffer only. The actual native
                // engine remains Kokoro, with the identical voice and sample rate.
                val output = LocalVoiceOutput(runtime, VoiceOutputConfiguration(
                    engine = if (sentenceBuffer) VoiceOutputEngine.KOKORO else VoiceOutputEngine.QWEN, volume = .10f), 1f, { false })
                try {
                    withContext(Dispatchers.Main) { output.prepare({ ready.complete(Unit) }, {
                        val error = IllegalStateException(it); ready.completeExceptionally(error); done.completeExceptionally(error)
                    }) }
                    withTimeout(30_000) { ready.await() }
                    epoch = System.nanoTime()
                    fun speak(index: Int) {
                        output.speak(phrases[index], { starts += (System.nanoTime() - epoch) / 1e6 }) {
                            ends += (System.nanoTime() - epoch) / 1e6
                            if (index == phrases.lastIndex) done.complete(Unit) else speak(index + 1)
                        }
                        phrases.getOrNull(index + 1)?.let(output::prefetch)
                    }
                    withContext(Dispatchers.Main) { speak(0) }
                    withTimeout(90_000) { done.await() }
                    assertEquals(3, starts.size); assertEquals(3, generationStarts.size)
                    if (sentenceBuffer) {
                        assertTrue("Second sentence generated while first plays", generationStarts[1] < ends[0])
                        assertTrue("Third sentence generated while second plays", generationStarts[2] < ends[1])
                    }
                    val row = JSONObject().put("buffer", if (sentenceBuffer) "sentence_lookahead" else "previous_1.6s")
                        .put("first_audio_ms", starts.first()).put("playback_starts_ms", JSONArray(starts))
                        .put("playback_ends_ms", JSONArray(ends)).put("generation_starts_ms", JSONArray(generationStarts))
                        .put("generation_ends_ms", JSONArray(generationEnds))
                        .put("sentence_gaps_ms", JSONArray((1..phrases.lastIndex).map { starts[it] - ends[it - 1] }))
                    rows.put(row)
                    File(app.filesDir, "kokoro-sentence-streaming.json").writeText(rows.toString(2))
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("kokoro_streaming", row.toString()) })
                } finally { withContext(Dispatchers.Main) { output.close() } }
            }
            val ready = CompletableDeferred<Unit>(); val started = CompletableDeferred<Unit>(); val resumed = CompletableDeferred<Unit>()
            val staleCompletion = java.util.concurrent.atomic.AtomicBoolean(false)
            val output = LocalVoiceOutput(runtime, VoiceOutputConfiguration(engine = VoiceOutputEngine.KOKORO, volume = .10f), 1f, { false })
            try {
                withContext(Dispatchers.Main) { output.prepare({ ready.complete(Unit) }, {
                    val error = IllegalStateException(it); ready.completeExceptionally(error); resumed.completeExceptionally(error)
                }) }
                withTimeout(30_000) { ready.await() }
                withContext(Dispatchers.Main) {
                    output.speak(phrases[0], { started.complete(Unit) }, { staleCompletion.set(true) })
                    output.prefetch(phrases[1])
                }
                withTimeout(30_000) { started.await() }
                delay(300)
                val stopMs = withContext(Dispatchers.Main) {
                    val begin = System.nanoTime(); output.stop(); (System.nanoTime() - begin) / 1e6
                }
                assertTrue("Stop must not wait for native synthesis: $stopMs ms", stopMs < 500)
                withContext(Dispatchers.Main) { output.speak("The new answer is ready.", {}, { resumed.complete(Unit) }) }
                withTimeout(30_000) { resumed.await() }
                assertFalse("Stopped sentence must not complete later", staleCompletion.get())
                rows.put(JSONObject().put("stop_ms", stopMs).put("reused_after_stop", true))
                File(app.filesDir, "kokoro-sentence-streaming.json").writeText(rows.toString(2))
            } finally { withContext(Dispatchers.Main) { output.close() } }
        } finally { withContext(Dispatchers.IO) { runtime.unload() } }
    }

    @Test fun compareThreadsAndPrecision() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        check(app.packageName == "org.praxisog.hermes.fixture")
        val files = VoiceOutputFiles(app, KokoroModel.model)
        val rows = JSONArray()
        for (precision in listOf("model_quantized.onnx", "model.onnx")) for (threads in listOf(1, 2, 4)) {
            val path = if (precision == "model_quantized.onnx") File(app.filesDir, precision) else File(files.directory, precision)
            NativeKokoroEngine(files, threads, path).use { engine ->
                repeat(2) {
                    val start = System.nanoTime(); var frames = 0
                    assertEquals(0, engine.synthesize("Your answer is ready. The total is twelve dollars and fifty cents.",
                        VoiceOutputConfiguration(engine = VoiceOutputEngine.KOKORO), object : LocalTtsSink {
                            override fun isCancelled() = false
                            override fun onChunk(samples: FloatArray): Boolean { frames += samples.size; return true }
                        }))
                    val row = JSONObject().put("precision", precision).put("threads", threads).put("repeat", it)
                        .put("synthesis_ms", (System.nanoTime() - start) / 1e6).put("audio_seconds", frames / 24000.0)
                    rows.put(row)
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("kokoro_benchmark", row.toString()) })
                    File(app.filesDir, "kokoro-benchmark.json").writeText(rows.toString(2))
                }
            }
        }
    }

    @Test fun coldWarmVoicesUnknownWordsAndInterruption() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        check(app.packageName == "org.praxisog.hermes.fixture")
        val files = VoiceOutputFiles(app, KokoroModel.model)
        val runtime = LocalTtsRuntime(files::verify) { NativeKokoroEngine(files) }
        val rows = JSONArray()
        try {
            for ((index, text) in listOf("Hello. I'm Bellpull. Your voice stays on this device.",
                "Your answer is ready.", "The total is $12.50, including a 10% discount.",
                "Xylophonically speaking, the quasar was unusually bright.",
                "Your answer is ready.").withIndex()) {
                val begin = System.nanoTime(); var first = 0.0; var load = 0.0; var frames = 0; var energy = 0.0
                val pcm = java.io.ByteArrayOutputStream()
                val code = withContext(Dispatchers.IO) { runtime.withModel { engine, ms, _ ->
                    load = ms
                    engine.synthesize(text, VoiceOutputConfiguration(engine = VoiceOutputEngine.KOKORO,
                        kokoroVoice = KokoroModel.voices[index % 4]), object : LocalTtsSink {
                        override fun isCancelled() = false
                        override fun onChunk(samples: FloatArray): Boolean {
                            if (frames == 0) first = (System.nanoTime() - begin) / 1e6 - load
                            frames += samples.size
                            val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                            samples.forEach { assertTrue(it.isFinite()); energy += it * it; bytes.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
                            pcm.write(bytes.array()); return true
                        }
                    })
                } }
                val elapsed = (System.nanoTime() - begin) / 1e6
                assertEquals(0, code); assertTrue(frames > 2400 && energy > .001)
                val row = JSONObject().put("text", text).put("voice", KokoroModel.voices[index % 4]).put("load_ms", load)
                    .put("first_audio_ms", first).put("synthesis_ms", elapsed - load).put("audio_seconds", frames / 24000.0)
                    .put("rtf", (elapsed - load) / (frames / 24.0))
                rows.put(row)
                File(app.filesDir, "kokoro-results.json").writeText(rows.toString(2))
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("kokoro_result", row.toString()) })
                val bytes = pcm.toByteArray(); val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray()).putInt(bytes.size + 36).put("WAVEfmt ".toByteArray()).putInt(16)
                    .putShort(1).putShort(1).putInt(24000).putInt(48000).putShort(2).putShort(16).put("data".toByteArray()).putInt(bytes.size)
                File(app.filesDir, "kokoro-$index.wav").outputStream().use { it.write(header.array()); it.write(bytes) }
            }
            withContext(Dispatchers.IO) {
                var chunks = 0
                val result = runtime.withModel { engine, _, _ -> engine.synthesize("Stop after this phrase. The remaining phrase must never be emitted.",
                    VoiceOutputConfiguration(engine = VoiceOutputEngine.KOKORO), object : LocalTtsSink {
                        override fun isCancelled() = chunks > 0
                        override fun onChunk(samples: FloatArray): Boolean { chunks++; return false }
                    }) }
                assertEquals(1, result); assertEquals(1, chunks)
            }
            val ready = CompletableDeferred<Unit>(); val done = CompletableDeferred<Unit>()
            val output = LocalVoiceOutput(runtime, VoiceOutputConfiguration(engine = VoiceOutputEngine.KOKORO, volume = .15f), 1f, { false })
            try {
                withContext(Dispatchers.Main) { output.prepare({ ready.complete(Unit) }, { ready.completeExceptionally(IllegalStateException(it)) }) }
                withTimeout(30_000) { ready.await() }
                withContext(Dispatchers.Main) { output.speak("Kokoro is ready.", {}, { done.complete(Unit) }) }
                withTimeout(30_000) { done.await() }
            } finally { withContext(Dispatchers.Main) { output.close() } }
        } finally { withContext(Dispatchers.IO) { runtime.unload() } }
    }
}
