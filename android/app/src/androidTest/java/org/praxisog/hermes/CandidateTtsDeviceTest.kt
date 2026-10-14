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
import java.util.concurrent.atomic.AtomicInteger

internal object CandidateTtsNative {
    init { System.loadLibrary("bellpull_pocket") }
    external fun load(directory: String, paradee: Boolean, threads: Int): Long
    external fun free(handle: Long)
    external fun synthesize(handle: Long, tokens: LongArray, seed: Int): FloatArray
}

/** Official frontends run on the host; phone timings measure native ONNX CPU graphs. */
@RunWith(AndroidJUnit4::class)
class CandidateTtsDeviceTest {
    @Test fun pinnedCandidatesOnPixelCpu() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        check(app.packageName == "org.praxisog.hermes.fixture")
        val directory = File(app.filesDir, "tts-candidates")
        val fixtures = Json.parseToJsonElement(File(directory, "fixtures.json").readText()).jsonObject
        val rows = mutableListOf<JsonObject>(); val thermals = SpeechThermals(app); val peak = AtomicInteger()
        val monitor = launch(Dispatchers.IO) {
            while (isActive) {
                val info = Debug.MemoryInfo(); Debug.getMemoryInfo(info); peak.accumulateAndGet(info.totalPss, ::maxOf); delay(50)
            }
        }
        fun save() = File(app.filesDir, "candidate-tts-results.json").writeText(buildJsonObject {
            put("device", android.os.Build.MODEL); put("sdk", android.os.Build.VERSION.SDK_INT)
            put("frontend", "Official Python frontends on host; staged tokens, frontend time excluded")
            put("models", fixtures.getValue("models")); put("results", JsonArray(rows))
        }.toString())
        try {
            for (engine in listOf("paradee", "inflect")) for (threads in listOf(1, 4)) {
                assertFalse("Severe thermal status", thermals.severe)
                val loadStart = System.nanoTime()
                val handle = withContext(Dispatchers.IO) { CandidateTtsNative.load(directory.path, engine == "paradee", threads) }
                val loadMs = (System.nanoTime() - loadStart) / 1e6
                assertNotEquals(0L, handle)
                try {
                    for (fixture in fixtures.getValue("fixtures").jsonArray) repeat(2) { repetition ->
                        assertFalse("Severe thermal status", thermals.severe)
                        val row = fixture.jsonObject; val id = row.getValue("id").jsonPrimitive.int
                        val tokens = row.getValue(engine).jsonArray.map { it.jsonPrimitive.long }.toLongArray()
                        peak.set(0); val thermalStart = thermals.snapshot(); val start = System.nanoTime()
                        val samples = withContext(Dispatchers.IO) { CandidateTtsNative.synthesize(handle, tokens, 42) }
                        val elapsed = (System.nanoTime() - start) / 1e6
                        assertTrue(samples.isNotEmpty()); assertTrue(samples.all { it.isFinite() })
                        val seconds = samples.size / 24000.0
                        rows += buildJsonObject {
                            put("engine", engine); put("threads", threads); put("fixture", id); put("text", row.getValue("text"))
                            put("repetition", repetition); put("load_ms", if (id == 0 && repetition == 0) loadMs else 0.0)
                            put("synthesis_ms", elapsed); put("first_pcm_ms", elapsed); put("audio_seconds", seconds)
                            put("real_time_factor", elapsed / 1000.0 / seconds); put("peak_pss_kib", peak.get())
                            put("thermal_start", thermalStart); put("thermal_end", thermals.snapshot())
                            put("rms", kotlin.math.sqrt(samples.sumOf { it.toDouble() * it } / samples.size))
                        }; save()
                        if (threads == 1 && repetition == 0) {
                            val bytes = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                            bytes.put("RIFF".toByteArray()); bytes.putInt(36 + samples.size * 2); bytes.put("WAVEfmt ".toByteArray())
                            bytes.putInt(16); bytes.putShort(1); bytes.putShort(1); bytes.putInt(24000); bytes.putInt(48000)
                            bytes.putShort(2); bytes.putShort(16); bytes.put("data".toByteArray()); bytes.putInt(samples.size * 2)
                            samples.forEach { bytes.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
                            File(app.filesDir, "$engine-$id.wav").writeBytes(bytes.array())
                        }
                        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                            putString("tts_benchmark", "$engine threads=$threads fixture=$id repetition=$repetition synthesis_ms=$elapsed audio_seconds=$seconds")
                        })
                    }
                } finally { withContext(Dispatchers.IO) { CandidateTtsNative.free(handle) } }
            }
        } finally { monitor.cancelAndJoin(); save() }
    }
}
