package org.praxisog.hermes

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class QwenRuntimeTest {
    @Test fun loadingIsVerifiedCachedAndUnloadedBeforeReplacement() {
        var verified = 0; var created = 0; var closed = 0
        val runtime = QwenRuntime({ verified++ }) { created++; object : QwenEngine {
            override fun synthesize(text: String, config: VoiceOutputConfiguration, sink: QwenSink) = 0
            override fun close() { closed++ }
        } }
        runtime.withModel { _, _, _ -> }; runtime.withModel { _, ms, _ -> assertEquals(0.0, ms, .0) }
        assertEquals(1, verified); assertEquals(1, created); assertTrue(runtime.loaded)
        runtime.unload(); assertEquals(1, closed); assertFalse(runtime.loaded)
        runtime.withModel { _, _, _ -> }; assertEquals(2, created); assertEquals(2, verified)
        runtime.unload(); assertEquals(2, closed)
    }
    @Test fun corruptOrMissingWeightsNeverCallTheNativeConstructor() {
        var created = false
        val runtime = QwenRuntime({ error("No weights") }) { created = true; error("Must not load") }
        assertThrows(IllegalStateException::class.java) { runtime.withModel { _, _, _ -> } }
        assertFalse(created); assertFalse(runtime.loaded)
    }
    @Test fun unloadInvalidatesActiveInferenceAndWaitsForItBeforeFreeing() {
        val admitted = CountDownLatch(1); val exited = CountDownLatch(1); val freeCount = AtomicInteger()
        val runtime = QwenRuntime({}) { object : QwenEngine {
            override fun synthesize(text: String, config: VoiceOutputConfiguration, sink: QwenSink) = 0
            override fun close() { assertEquals(0L, exited.count); freeCount.incrementAndGet() }
        } }
        val worker = thread {
            runtime.withModel { _, _, invalid ->
                admitted.countDown()
                val deadline = System.nanoTime() + 2_000_000_000
                while (!invalid() && System.nanoTime() < deadline) Thread.yield()
                assertTrue(invalid()); assertEquals(0, freeCount.get()); exited.countDown()
            }
        }
        assertTrue(admitted.await(2, TimeUnit.SECONDS))
        runtime.unload(); worker.join(2000)
        assertFalse(worker.isAlive); assertEquals(1, freeCount.get())
    }
}
