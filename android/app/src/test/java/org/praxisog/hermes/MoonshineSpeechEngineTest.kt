package org.praxisog.hermes

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class MoonshineSpeechEngineTest {
    private val model = SpeechModel("moonshine-tiny", "Tiny", 2, "checkpoint", "INT8", emptyList())
    private class Api : MoonshineApi {
        override val version = 20000
        val events = mutableListOf<String>()
        var options = emptyMap<String, String>()
        var result: List<MoonshineLine>? = emptyList()
        var createResult = 10
        override fun load(path: String, architecture: Int, options: Map<String, String>): Int {
            events += "load $architecture"; this.options = options; return 7
        }
        override fun free(handle: Int) { events += "free $handle" }
        override fun createStream(handle: Int): Int { events += "create"; return createResult++ }
        override fun start(handle: Int, stream: Int): Int { events += "start $stream"; return 0 }
        override fun add(handle: Int, stream: Int, samples: FloatArray): Int { events += "add ${samples.size}"; return 0 }
        override fun poll(handle: Int, stream: Int, force: Boolean): List<MoonshineLine>? { events += "poll $force"; return result }
        override fun stop(handle: Int, stream: Int): Int { events += "stop $stream"; return 0 }
        override fun freeStream(handle: Int, stream: Int): Int { events += "freeStream $stream"; return 0 }
        override fun batch(handle: Int, audio: SpeechAudio) = result
    }
    @Test fun explicitCpuOptionsAndActualRuntimeSettingsAreReported() {
        val api = Api()
        val engine = MoonshineSpeechEngine(model, File("model"), SpeechTuning(750, .7f), api)
        assertEquals("cpu", api.options["ort_providers"])
        assertEquals("0.75", api.options["transcription_interval"])
        assertEquals("0.7", api.options["vad_threshold"])
        assertEquals("false", api.options["log_output_text"])
        assertEquals("false", api.options["return_audio_data"])
        assertEquals("CPU (ONNX Runtime)", engine.actualBackend)
        assertTrue(engine.runtimeVersion.contains("update_ms=750; vad_threshold=0.7"))
        engine.close(); engine.close()
        assertEquals(1, api.events.count { it == "free 7" })
    }
    @Test fun partialsRespectUpdateFloorAndFinalsAreNotDuplicated() {
        val api = Api()
        val engine = MoonshineSpeechEngine(model, File("model"), SpeechTuning(), api)
        val updates = mutableListOf<SpeechUpdate>()
        engine.start(updates::add)
        api.result = listOf(MoonshineLine(0, "hello", 0f, .4f, false))
        repeat(4) { engine.accept(FloatArray(1600)) }
        assertTrue(updates.isEmpty())
        engine.accept(FloatArray(1600))
        assertEquals("hello", updates.single().text)
        api.result = listOf(MoonshineLine(0, "hello world", 0f, 1f, true))
        engine.accept(FloatArray(8000)); engine.accept(FloatArray(8000))
        assertEquals("hello world", engine.finish())
        assertEquals(1, updates.count { it.completedLine })
        assertEquals(1000.0, updates.last().audioEndMs!!, .001)
        assertTrue(api.events.indexOf("stop 10") < api.events.indexOf("poll true"))
        assertEquals(1, api.events.count { it == "freeStream 10" })
        engine.close()
    }
    @Test fun repeatedWarmStreamsCanReuseLineIdsAndStillEmitFinals() {
        val api = Api()
        val engine = MoonshineSpeechEngine(model, File("model"), SpeechTuning(), api)
        val updates = mutableListOf<SpeechUpdate>()
        repeat(2) { turn ->
            engine.start(updates::add)
            api.result = listOf(MoonshineLine(0, "turn $turn", 0f, 1f, true))
            engine.accept(FloatArray(8000))
            assertEquals("turn $turn", engine.finish())
        }
        assertEquals(listOf("turn 0", "turn 1"), updates.filter { it.completedLine }.map { it.text })
        assertEquals(1, api.events.count { it == "load 2" })
        engine.close()
    }
    @Test fun failedFinalizationFreesTheStreamAndAllowsFreshRetry() {
        val api = Api()
        val engine = MoonshineSpeechEngine(model, File("model"), SpeechTuning(), api)
        engine.start { }
        api.result = null
        assertThrows(IllegalStateException::class.java) { engine.finish() }
        assertEquals(1, api.events.count { it == "freeStream 10" })
        api.result = listOf(MoonshineLine(0, "recovered", 0f, 1f, true))
        engine.start { }
        assertEquals("recovered", engine.finish())
        engine.close()
    }
    @Test fun invalidStreamHandleIsNeverFreed() {
        val api = Api().also { it.createResult = -1 }
        val engine = MoonshineSpeechEngine(model, File("model"), SpeechTuning(), api)
        assertThrows(IllegalStateException::class.java) { engine.start { } }
        engine.close()
        assertFalse(api.events.any { it.startsWith("freeStream") })
    }
    @Test fun cancelledLocalInferenceDoesNotFreeNativeStateConcurrentlyOrBlockUiInspection() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        var freedThread: String? = null
        val runtime = SpeechModelRuntime({}, {
            object : LocalSpeechEngine {
                override val actualBackend = "CPU"
                override val runtimeVersion = "fake"
                override fun start(onUpdate: (SpeechUpdate) -> Unit) {}
                override fun accept(samples: FloatArray) {}
                override fun finish() = ""
                override fun transcribe(audio: SpeechAudio): String {
                    entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)); return "stale words"
                }
                override fun close() { freedThread = Thread.currentThread().name }
            }
        })
        val transcriber = LocalVoiceTranscriber(runtime, model)
        val worker = Executors.newSingleThreadExecutor { Thread(it, "native-worker") }
        try {
            val result = worker.submit<Result<String>> { transcriber.transcribe(wav(ByteArray(3200))) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(model.id, runtime.loadedModel) // Must not take the held native lock.
            transcriber.close()
            assertNull(freedThread)
            finish.countDown()
            assertTrue(result.get(5, TimeUnit.SECONDS).exceptionOrNull() is CancellationException)
            assertEquals("native-worker", freedThread)
            assertNull(runtime.loadedModel)
        } finally { finish.countDown(); worker.shutdownNow(); runtime.close() }
    }
    @Test fun missingWeightsAndSevereHeatCannotSilentlyChooseAnotherRecognizer() {
        var nativeLoads = 0
        val runtime = SpeechModelRuntime({ error("Missing selected weights") }, { nativeLoads++; error("Must not load") })
        val missing = LocalVoiceTranscriber(runtime, model).transcribe(wav(ByteArray(3200)))
        assertTrue(missing.exceptionOrNull()!!.message!!.contains("Missing selected weights"))
        val hot = LocalVoiceTranscriber(runtime, model) { true }.transcribe(wav(ByteArray(3200)))
        assertTrue(hot.exceptionOrNull()!!.message!!.contains("severe thermal"))
        assertEquals(0, nativeLoads)
    }
}
