package org.praxisog.hermes

import android.content.Context
import android.os.Bundle
import android.os.Debug
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Never targets a saved server/account. Weights are staged in the disposable fixture app. */
@RunWith(AndroidJUnit4::class)
class QwenVoiceDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private fun progress(message: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
        Bundle().apply { putString("qwen_progress", message) })
    @Test fun directCpuColdWarmStreamingAndCancellation() = runBlocking {
        check(app.packageName == "org.praxisog.hermes.fixture")
        val files = VoiceOutputFiles(app)
        val thermals = SpeechThermals(app)
        val runtime = LocalTtsRuntime(files::verify) { NativeQwenEngine(files) }
        val report = File(app.filesDir, "qwen-results.json")
        val rows = mutableListOf<JsonObject>()
        val peak = AtomicInteger()
        val monitor = launch(Dispatchers.IO) {
            while (isActive) {
                val info = Debug.MemoryInfo(); Debug.getMemoryInfo(info)
                peak.accumulateAndGet(info.totalPss, ::maxOf)
                delay(250)
            }
        }
        fun save() = report.writeText(buildJsonObject {
            put("checkpoint", QwenModel.checkpoint); put("gguf_revision", QwenModel.revision)
            put("quantization", "Q4_K_M"); put("runtime", QwenModel.runtime); put("backend", "CPU")
            put("device", android.os.Build.MODEL); put("results", JsonArray(rows))
        }.toString())
        try {
            val samples = listOf("Working.", "Your answer is ready.", "The board measures twenty four inches by twelve inches.")
            val count = InstrumentationRegistry.getArguments().getString("repetitions", "2")!!.toInt().coerceIn(1, 4)
            for ((fixture, text) in samples.withIndex()) repeat(count) { repetition ->
                assertFalse("Severe thermal status", thermals.severe)
                val thermalStart = thermals.snapshot()
                peak.set(0)
                val start = System.nanoTime()
                var firstMs: Double? = null; var frames = 0L; var energy = 0.0
                var loading = 0.0; var cancelled = false
                val pcm = if (fixture == 0 && repetition == 0) java.io.ByteArrayOutputStream() else null
                var sawChunk = false
                val status = withContext(Dispatchers.IO) {
                    runtime.withModel { engine, loadMs, invalid ->
                        loading = loadMs
                        val generation = System.nanoTime()
                        engine.synthesize(text, VoiceOutputConfiguration(seed = 42), object : LocalTtsSink {
                            override fun isCancelled(): Boolean {
                                cancelled = thermals.severe || invalid() || (System.nanoTime() - generation) > 120_000_000_000L
                                return cancelled
                            }
                            override fun onChunk(samples: FloatArray): Boolean {
                                if (isCancelled()) return false
                                if (firstMs == null) firstMs = (System.nanoTime() - generation) / 1e6
                                sawChunk = true; frames += samples.size
                                samples.forEach { value -> assertTrue(value.isFinite()); energy += value * value }
                                if (pcm != null) {
                                    val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                                    samples.forEach { bytes.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
                                    pcm.write(bytes.array())
                                }
                                return true
                            }
                        })
                    }
                }
                val elapsed = (System.nanoTime() - start) / 1e6
                val row = buildJsonObject {
                    put("fixture", fixture); put("text", text); put("repetition", repetition)
                    put("load_including_verification_ms", loading); put("first_pcm_ms_after_load", firstMs?.let(::JsonPrimitive) ?: JsonNull)
                    put("elapsed_including_load_ms", elapsed); put("audio_seconds", frames / 24000.0)
                    put("real_time_factor_without_load", (elapsed - loading) / (frames / 24.0))
                    put("rms", kotlin.math.sqrt(energy / maxOf(1, frames))); put("status", status); put("cancelled", cancelled)
                    put("peak_pss_kib", peak.get()); put("thermal_start", thermalStart); put("thermal_end", thermals.snapshot())
                }
                rows += row; save(); progress(row.toString())
                assertEquals("Native generation failed", 0, status)
                assertTrue("No PCM chunks", sawChunk && frames > 1000 && energy > .00001)
                if (pcm != null) writeWav(File(app.filesDir, "qwen-working.wav"), pcm.toByteArray())
            }
            // Cancellation does not unload/free a context while its worker is active.
            withContext(Dispatchers.IO) {
                var first = false; var requested = 0L
                val status = runtime.withModel { engine, _, _ -> engine.synthesize(
                    "This passage must stop as soon as its first audio chunk arrives. It must never play after interruption.",
                    VoiceOutputConfiguration(seed = 42), object : LocalTtsSink {
                        override fun isCancelled() = first || thermals.severe
                        override fun onChunk(samples: FloatArray): Boolean { first = true; requested = System.nanoTime(); return false }
                    }) }
                assertEquals(-5, status); assertTrue(first)
                val cancellationMs = (System.nanoTime() - requested) / 1e6
                rows += buildJsonObject { put("test", "cancel_after_first_pcm"); put("cancel_ms", cancellationMs); put("status", status) }
                save(); progress("Cancellation after first PCM: $cancellationMs ms")
            }
        } finally { withContext(Dispatchers.IO) { runtime.unload() }; monitor.cancelAndJoin() }
    }
    @Test fun nativePlaybackDrainsBeforeCompleteAndStopClearsAudio() = runBlocking {
        check(app.packageName == "org.praxisog.hermes.fixture")
        val files = VoiceOutputFiles(app); val thermals = SpeechThermals(app)
        val runtime = LocalTtsRuntime(files::verify) { NativeQwenEngine(files) }
        val output = LocalVoiceOutput(runtime, VoiceOutputConfiguration(volume = .2f, seed = 42), 1f, { thermals.severe })
        val ready = CompletableDeferred<Unit>(); val started = CompletableDeferred<Unit>(); val done = CompletableDeferred<Unit>()
        try {
            withContext(Dispatchers.Main) { output.prepare({ ready.complete(Unit) }, { ready.completeExceptionally(IllegalStateException(it)) }) }
            withTimeout(60_000) { ready.await() }
            withContext(Dispatchers.Main) { output.speak("Working.", { started.complete(Unit) }, { done.complete(Unit) }) }
            withTimeout(120_000) { started.await(); done.await() }
            val stopped = CompletableDeferred<Unit>(); val late = AtomicBoolean(false)
            withContext(Dispatchers.Main) { output.speak("Here is another answer that should stop playing.", { output.stop(); stopped.complete(Unit) }, { late.set(true) }) }
            withTimeout(120_000) { stopped.await() }; delay(1000); assertFalse(late.get())
        } finally { withContext(Dispatchers.Main) { output.close() }; withContext(Dispatchers.IO) { runtime.unload() } }
    }
    private fun writeWav(file: File, pcm: ByteArray) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(24000).putInt(48000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(pcm.size)
        file.outputStream().use { it.write(header.array()); it.write(pcm) }
    }
}
