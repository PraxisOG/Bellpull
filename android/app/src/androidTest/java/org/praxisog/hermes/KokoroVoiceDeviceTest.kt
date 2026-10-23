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
