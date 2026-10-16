package org.praxisog.hermes

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class QwenVoiceOutputTest {
    private fun await(latch: CountDownLatch) { assertTrue("Timed out", latch.await(3, TimeUnit.SECONDS)) }
    @Test fun pcmStartsDuringSynthesisButCompletionWaitsForPlaybackDrain() {
        val first = CountDownLatch(1); val allowFinish = CountDownLatch(1); val drained = CompletableDeferred<Unit>()
        val generating = AtomicBoolean(true); val done = CountDownLatch(1)
        val runtime = LocalTtsRuntime({}) { object : LocalTtsEngine {
            override fun synthesize(text: String, config: VoiceOutputConfiguration, sink: LocalTtsSink): Int {
                assertTrue(sink.onChunk(FloatArray(2400) { .1f })); await(allowFinish)
                generating.set(false); return 0
            }
            override fun close() {}
        } }
        val voice = LocalVoiceOutput(runtime, VoiceOutputConfiguration(), 1f, { false }, player = { _, _ -> object : VoicePcmPlayer {
            override fun write(samples: FloatArray) {}
            override suspend fun drain() { drained.await() }
            override fun stop() { drained.complete(Unit) }
            override fun close() {}
        } }, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), callbacks = Dispatchers.Unconfined)
        val ready = CountDownLatch(1); voice.prepare({ ready.countDown() }, { fail(it) }); await(ready)
        voice.speak("A phrase", { first.countDown() }, { done.countDown() }); await(first)
        assertTrue(generating.get()); assertEquals(1L, done.count)
        allowFinish.countDown(); Thread.sleep(50); assertEquals(1L, done.count)
        drained.complete(Unit); await(done); voice.close(); runtime.unload()
    }
    @Test fun stopFlushesPlaybackAndRejectsLateNativeChunksAndCompletion() {
        val first = CountDownLatch(1); val cancelled = CountDownLatch(1); val freed = CountDownLatch(1)
        val complete = AtomicBoolean(false); val stopped = AtomicBoolean(false)
        val runtime = LocalTtsRuntime({}) { object : LocalTtsEngine {
            override fun synthesize(text: String, config: VoiceOutputConfiguration, sink: LocalTtsSink): Int {
                assertTrue(sink.onChunk(FloatArray(2400)))
                val end = System.nanoTime() + 2_000_000_000
                while (!sink.isCancelled() && System.nanoTime() < end) Thread.yield()
                assertTrue(sink.isCancelled()); assertFalse(sink.onChunk(FloatArray(2400)))
                cancelled.countDown(); return -5
            }
            override fun close() { freed.countDown() }
        } }
        val voice = LocalVoiceOutput(runtime, VoiceOutputConfiguration(), 1f, { false }, player = { _, _ -> object : VoicePcmPlayer {
            override fun write(samples: FloatArray) {}
            override suspend fun drain() {}
            override fun stop() { stopped.set(true) }
            override fun close() { stopped.set(true) }
        } }, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), callbacks = Dispatchers.Unconfined)
        val ready = CountDownLatch(1); voice.prepare({ ready.countDown() }, { fail(it) }); await(ready)
        voice.speak("A phrase", { first.countDown() }, { complete.set(true) }); await(first)
        voice.stop(); assertTrue(stopped.get()); await(cancelled)
        voice.close(); runtime.unload(); await(freed); assertFalse(complete.get())
    }
    @Test fun severeThermalsFailBeforeNativeLoadOrPlayback() {
        var loaded = false
        val runtime = LocalTtsRuntime({}) { loaded = true; error("Must not load") }
        var failure: String? = null
        val voice = LocalVoiceOutput(runtime, VoiceOutputConfiguration(), 1f, { true },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), callbacks = Dispatchers.Unconfined)
        voice.prepare({ fail("Unexpected ready") }, { failure = it })
        assertNotNull(failure); assertFalse(loaded); voice.close()
    }
    @Test fun nextPhraseSynthesizesDuringDrainAndStopCancelsBoundedPrefetch() {
        val firstDrain = CompletableDeferred<Unit>()
        val secondGenerated = CountDownLatch(1); val cancelled = CountDownLatch(1)
        val received = java.util.concurrent.CopyOnWriteArrayList<String>()
        val ready = CountDownLatch(1)
        val runtime = LocalTtsRuntime({}) { object : LocalTtsEngine {
            override fun synthesize(text: String, config: VoiceOutputConfiguration, sink: LocalTtsSink): Int {
                received += text
                if (text == "First") return if (sink.onChunk(FloatArray(2400))) 0 else -5
                secondGenerated.countDown()
                // A large native chunk exceeds the bounded queue and must block/cancel.
                assertFalse(sink.onChunk(FloatArray(2400 * 100)))
                cancelled.countDown(); return -5
            }
            override fun close() {}
        } }
        val voice = LocalVoiceOutput(runtime, VoiceOutputConfiguration(), 1f, { false }, player = { _, _ -> object : VoicePcmPlayer {
            override fun write(samples: FloatArray) { assertTrue(samples.size <= 2400) }
            override suspend fun drain() { firstDrain.await() }
            override fun stop() { firstDrain.complete(Unit) }
            override fun close() {}
        } }, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), callbacks = Dispatchers.Unconfined)
        try {
            voice.prepare({ ready.countDown() }, { fail(it) }); await(ready)
            voice.speak("First", {}, {})
            voice.prefetch("Second"); await(secondGenerated)
            assertEquals(listOf("First", "Second"), received)
            voice.stop(); await(cancelled)
        } finally { voice.close(); runtime.unload() }
    }
}
