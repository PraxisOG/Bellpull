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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PocketBaselineDeviceTest {
    @Test fun defaultPocketOnIdenticalCandidateTexts() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        check(app.packageName == "org.praxisog.hermes.fixture")
        val inputs = Json.parseToJsonElement(File(app.filesDir, "tts-candidates/fixtures.json").readText()).jsonObject.getValue("fixtures").jsonArray
        val files = VoiceOutputFiles(app, PocketModel.model)
        val runtime = LocalTtsRuntime(files::verify) { NativePocketEngine(files) }
        val thermal = SpeechThermals(app); val peak = AtomicInteger(); val rows = mutableListOf<JsonObject>(); val gaps = mutableListOf<JsonObject>()
        val monitor = launch(Dispatchers.IO) {
            while (isActive) { val info = Debug.MemoryInfo(); Debug.getMemoryInfo(info); peak.accumulateAndGet(info.totalPss, ::maxOf); delay(100) }
        }
        fun save() = File(app.filesDir, "pocket-baseline-results.json").writeText(buildJsonObject {
            put("device", android.os.Build.MODEL); put("revision", PocketModel.revision); put("runtime", PocketModel.runtime)
            put("gap_comparison", JsonArray(gaps)); put("steps", 5); put("chunk_frames", 15); put("temperature", .7); put("threads", 4); put("results", JsonArray(rows))
        }.toString())
        try {
            for (input in inputs) repeat(2) { repetition ->
                assertFalse(thermal.severe); peak.set(0)
                val row = input.jsonObject; val text = row.getValue("text").jsonPrimitive.content
                val start = System.nanoTime(); var loading = 0.0; var first: Double? = null; var frames = 0L
                val thermalStart = thermal.snapshot()
                val status = withContext(Dispatchers.IO) {
                    runtime.withModel { engine, loadMs, invalid ->
                        loading = loadMs
                        val synthesis = System.nanoTime()
                        // Legacy direct-engine benchmark; Pocket is no longer a selectable engine.
                        engine.synthesize(text, VoiceOutputConfiguration(seed = 42), object : LocalTtsSink {
                            override fun isCancelled() = invalid() || thermal.severe || System.nanoTime() - synthesis > 180_000_000_000L
                            override fun onChunk(samples: FloatArray): Boolean {
                                if (isCancelled()) return false
                                if (first == null) first = (System.nanoTime() - synthesis) / 1e6
                                assertTrue(samples.all { it.isFinite() }); frames += samples.size; return true
                            }
                        })
                    }
                }
                val elapsed = (System.nanoTime() - start) / 1e6
                rows += buildJsonObject {
                    put("fixture", row.getValue("id")); put("text", text); put("repetition", repetition); put("load_ms", loading)
                    put("synthesis_ms", elapsed - loading); put("first_pcm_ms", first?.let(::JsonPrimitive) ?: JsonNull)
                    put("audio_seconds", frames / 24000.0); put("real_time_factor", (elapsed - loading) / (frames / 24.0))
                    put("status", status); put("peak_pss_kib", peak.get()); put("thermal_start", thermalStart); put("thermal_end", thermal.snapshot())
                }; save(); assertEquals(0, status); assertTrue(frames > 0)
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("pocket_baseline", rows.last().toString()) })
            }
            // Actual Android playback: compare the same two phrases with/without synthesis ahead.
            for (prefetch in listOf(false, true)) {
                val output = LocalVoiceOutput(runtime, VoiceOutputConfiguration(seed = 42), 1f, { thermal.severe })
                val ready = CompletableDeferred<Unit>(); val done = CompletableDeferred<Unit>()
                val starts = mutableListOf<Long>(); val ends = mutableListOf<Long>()
                val phrases = listOf("Your answer is ready.", "The board measures twenty four inches by twelve inches.")
                try {
                    withContext(Dispatchers.Main) { output.prepare({ ready.complete(Unit) }, { done.completeExceptionally(IllegalStateException(it)) }) }
                    withTimeout(30000) { ready.await() }
                    withContext(Dispatchers.Main) {
                        fun speak(index: Int) {
                            if (index == phrases.size) { done.complete(Unit); return }
                            output.speak(phrases[index], { starts += System.nanoTime() }, {
                                ends += System.nanoTime(); speak(index + 1)
                            })
                            if (prefetch) phrases.getOrNull(index + 1)?.let(output::prefetch)
                        }
                        speak(0)
                    }
                    withTimeout(120000) { done.await() }
                    assertEquals(2, starts.size); assertEquals(2, ends.size)
                    gaps += buildJsonObject {
                        put("prefetch", prefetch); put("gap_ms_between_drain_and_next_pcm", (starts[1] - ends[0]) / 1e6)
                        put("measurement", "Completion after AudioTrack drain; next start after first PCM write")
                    }; save()
                } finally { withContext(Dispatchers.Main) { output.close() } }
            }
        } finally { withContext(Dispatchers.IO) { runtime.unload() }; monitor.cancelAndJoin(); save() }
    }
}
