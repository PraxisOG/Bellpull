package org.praxisog.hermes

import android.content.Context
import android.os.Debug
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.model.*
import java.io.File

/** Real Android ICU/ART; the host JVM does not reproduce the native Matcher copies. */
@RunWith(AndroidJUnit4::class)
class LiveHistoryDeviceTest {
    @Test fun longReconnectReplayUsesBoundedMemoryAndKeepsMainThreadResponsive() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "org.praxisog.hermes.fixture")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val result = JSONObject()
        val record = RunSubmission("request", "fixture", "Check", 1.0, 1, emptySet(), runId = "run")
        fun history(text: String) = listOf(Message(1, role = "user", content = "Check"),
            Message(2, role = "assistant", content = text, reasoningContent = text))

        // Demonstrate the old native-copy behavior only with a deliberately tiny
        // bounded input. Never run the old algorithm with the long stress input.
        instrumentation.runOnMainSync {
            val small = "word ".repeat(512)
            val before = Debug.getNativeHeapAllocatedSize()
            val start = System.nanoTime()
            val a = Regex("\\S+").findAll(small).toList()
            val b = Regex("\\S+").findAll(small).toList()
            val held = Debug.getNativeHeapAllocatedSize() - before
            assertEquals(512, a.size); assertEquals(a.map { it.value }, b.map { it.value })
            result.put("legacy_sample_chars", small.length).put("legacy_sample_tokens", a.size)
                .put("legacy_native_growth_bytes", held).put("legacy_elapsed_ms", (System.nanoTime() - start) / 1e6)
        }
        Runtime.getRuntime().gc()
        Runtime.getRuntime().runFinalization()

        val saved = "word ".repeat(120_000)
        val rows = history(saved)
        val turn = TurnState.Streaming(saved + "New answer", reasoning = saved + "New thought", parts = listOf(
            LivePart.Prose("thought", saved + "New thought", thinking = true), LivePart.Prose("answer", saved + "New answer")))
        val measurements = mutableListOf<Double>()
        var peak = 0L
        val before = Debug.getNativeHeapAllocatedSize()
        repeat(20) {
            instrumentation.runOnMainSync {
                val start = System.nanoTime()
                val projected = projectLiveHistory(turn, rows, record).turn as TurnState.Streaming
                measurements += (System.nanoTime() - start) / 1e6
                assertEquals("New answer", projected.text); assertEquals("New thought", projected.reasoning)
                assertEquals(listOf("New thought", "New answer"), projected.parts.filterIsInstance<LivePart.Prose>().map { it.text })
                peak = maxOf(peak, Debug.getNativeHeapAllocatedSize() - before)
            }
            // A separate main-loop dispatch after every projection must complete.
            var dispatched = false
            instrumentation.runOnMainSync { dispatched = true }
            assertTrue(dispatched)
        }
        result.put("stress_saved_chars", saved.length).put("stress_saved_tokens", 120_000)
            .put("iterations", measurements.size).put("max_projection_ms", measurements.max())
            .put("median_projection_ms", measurements.sorted()[measurements.size / 2])
            .put("peak_native_growth_bytes", peak)
        File(context.filesDir, "live-history-metrics.json").writeText(result.toString(2))
        assertTrue("Projection blocked main thread: $result", measurements.max() < 1000.0)
        assertTrue("Native allocation growth: $result", peak < 64L * 1024 * 1024)
    }
}
