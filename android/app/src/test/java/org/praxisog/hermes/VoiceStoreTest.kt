package org.praxisog.hermes

import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.Assert.*
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.RunSubmission
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real store/client/durable journal with fake inference and audio ports. */
class VoiceStoreTest {
    @Test fun confirmedInterruptionCallsTheExistingStopEndpointBeforeOneReplacementAdmission() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val admissions = AtomicInteger(); val stops = AtomicInteger()
        val saved = ConcurrentHashMap<String, RunSubmission>()
        val spoken = CopyOnWriteArrayList<String>()
        val callbacks = java.util.concurrent.LinkedBlockingQueue<() -> Unit>()
        val http = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val body = when {
                path == "/v1/runs" -> {
                    val number = admissions.incrementAndGet()
                    assertEquals(number, saved.size)
                    """{"run_id":"run_$number","status":"running"}"""
                }
                path == "/v1/runs/run_1/stop" -> { stops.incrementAndGet(); """{"run_id":"run_1","session_id":"one","status":"running"}""" }
                path.endsWith("/events") -> ""
                path.endsWith("/messages") -> if (stops.get() > 0)
                    """{"data":[{"id":1,"role":"user","content":"First question."},{"id":2,"role":"assistant","content":"Partial old answer."}]}""" else """{"data":[]}"""
                path == "/v1/runs/run_1" && stops.get() > 0 ->
                    """{"run_id":"run_1","session_id":"one","status":"cancelled","output":"Partial old answer."}"""
                path.startsWith("/v1/runs/") ->
                    """{"run_id":"${path.substringAfterLast('/')}","session_id":"one","status":"running"}"""
                else -> """{"data":[]}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body.toResponseBody()).build()
        }.build()
        val client = HermesClient("https://fixture.invalid", { "fixture" }, httpClient = http)
        val store = HermesStore(scope, client, durableRetention = 86400, journal = object : RunJournal {
            override fun readRuns() = saved.values.toList()
            override fun writeRun(value: RunSubmission): Boolean { saved[value.requestId] = value; return true }
        })
        val voice = VoiceSession("one", "profile", scope, StoreVoiceConversation(store, "one"), VoiceConfiguration(concise = false),
            object : VoiceOutput {
                override fun prepare(onReady: (String) -> Unit, onError: (String) -> Unit) = onReady("fixture")
                override fun speak(text: String, started: () -> Unit, complete: () -> Unit) { spoken += text; callbacks.add(complete); started() }
                override fun stop() { callbacks.clear() }
                override fun close() = stop()
            }, object : VoiceCapture {
                override fun start(frame: (ShortArray, Int) -> Unit, error: (String) -> Unit) = true
                override fun close() {}
            }, initialTranscript = "First question.", transcriber = { object : VoiceTranscriber {
                override fun transcribe(wav: ByteArray) = Result.success("Replacement question.")
                override fun close() {}
            } })
        fun await(check: () -> Boolean) { repeat(600) { Snapshot.sendApplyNotifications(); if (check()) return; Thread.sleep(15) }; fail("Interruption integration timed out: ${voice.state}, ${voice.notice}") }
        try {
            store.openSession("one"); await { store.history is UiState.Ready }
            voice.start(); voice.sendTranscript(); callbacks.poll(2, java.util.concurrent.TimeUnit.SECONDS)!!.invoke()
            await { admissions.get() == 1 && store.runPresentation?.submission?.runId == "run_1" }
            repeat(25) { voice.frame(ShortArray(320) { 2500 }, 320) }; repeat(90) { voice.frame(ShortArray(320), 320) }
            await { stops.get() == 1 }
            assertEquals(1, admissions.get())
            await { spoken.count { it == "Working." } == 2 }
            assertEquals(1, admissions.get())
            callbacks.poll(2, java.util.concurrent.TimeUnit.SECONDS)!!.invoke()
            await { admissions.get() == 2 && saved.values.any { it.runId == "run_2" } }
            assertEquals(1, stops.get()); assertEquals(2, saved.size)
            assertEquals("Replacement question.", saved.values.single { it.runId == "run_2" }.text.substringAfterLast("\n\n"))
            assertFalse(spoken.any { it.contains("Partial old answer") })
        } finally { voice.close(); store.close(); scope.cancel(); client.close() }
    }
    @Test fun voiceUsesOneDurableAdmissionAndNeverSpeaksReasoning() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val calls = AtomicInteger(); val finished = AtomicBoolean()
        val recognitions = AtomicInteger()
        val speechModel = SpeechModel("moonshine-tiny", "Tiny", 2, "fixture", "INT8", emptyList())
        val local = SpeechModelRuntime({}, {
            object : LocalSpeechEngine {
                override val actualBackend = "CPU fixture"
                override val runtimeVersion = "fake native inference"
                override fun start(onUpdate: (SpeechUpdate) -> Unit) {}
                override fun accept(samples: FloatArray) {}
                override fun finish() = ""
                override fun transcribe(audio: SpeechAudio): String {
                    recognitions.incrementAndGet()
                    assertEquals(16000, audio.sampleRate)
                    assertTrue(audio.samples.any { it > 0f })
                    return "Voice question"
                }
                override fun close() {}
            }
        })
        val saved = ConcurrentHashMap<String, RunSubmission>()
        val spoken = CopyOnWriteArrayList<String>()
        var playback: (() -> Unit)? = null
        val http = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val body = when {
                request.url.encodedPath == "/v1/runs" -> { calls.incrementAndGet(); assertEquals(1, saved.size); """{"run_id":"run_voice","status":"running"}""" }
                request.url.encodedPath.endsWith("/events") ->
                    "event: tool.progress\ndata: {\"seq\":0,\"tool_name\":\"_thinking\",\"delta\":\"Private reasoning must never be spoken\"}\n\nevent: message.delta\ndata: {\"seq\":1,\"delta\":\"The answer starts here. More\"}\n\n"
                request.url.encodedPath.endsWith("/messages") -> if (finished.get())
                    """{"data":[{"id":1,"role":"user","content":"Voice question"},{"id":2,"role":"assistant","content":"The answer starts here. More detail."}]}""" else """{"data":[]}"""
                request.url.encodedPath.startsWith("/v1/runs/") -> if (finished.get())
                    """{"run_id":"run_voice","session_id":"one","status":"completed","output":"The answer starts here. More detail."}""" else
                    """{"run_id":"run_voice","session_id":"one","status":"running"}"""
                else -> """{"data":[]}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body.toResponseBody()).build()
        }.build()
        val client = HermesClient("https://fixture.invalid", { "fixture" }, httpClient = http)
        val store = HermesStore(scope, client, durableRetention = 86400, journal = object : RunJournal {
            override fun readRuns() = saved.values.toList()
            override fun writeRun(value: RunSubmission): Boolean { saved[value.requestId] = value; return true }
        })
        val voice = VoiceSession("one", "profile", scope, StoreVoiceConversation(store, "one"), VoiceConfiguration(concise = false),
            object : VoiceOutput {
                override fun prepare(onReady: (String) -> Unit, onError: (String) -> Unit) = onReady("fixture")
                override fun speak(text: String, started: () -> Unit, complete: () -> Unit) { playback = complete; spoken += text; started() }
                override fun stop() { playback = null }
                override fun close() = stop()
            }, object : VoiceCapture {
                override fun start(frame: (ShortArray, Int) -> Unit, error: (String) -> Unit) = true
                override fun close() {}
            }, transcriber = { LocalVoiceTranscriber(local, speechModel) })
        fun await(check: () -> Boolean) { repeat(400) { Snapshot.sendApplyNotifications(); if (check()) return; Thread.sleep(15) }; fail("Voice integration timed out: ${voice.state}") }
        try {
            store.openSession("one"); await { store.history is UiState.Ready }
            voice.start(VoiceMode.DICTATION)
            repeat(25) { voice.frame(ShortArray(320) { 2500 }, 320) }; repeat(90) { voice.frame(ShortArray(320), 320) }
            await { voice.state == VoiceState.REVIEW }; voice.sendTranscript()
            assertEquals(0, calls.get()); val preamble = playback!!; playback = null; preamble()
            await { spoken.size == 2 }; assertEquals("Thinking.", spoken[1])
            playback!!(); await { spoken.size == 3 }; assertEquals("The answer starts here.", spoken[2])
            assertTrue(store.runPresentation!!.busy); assertEquals(1, calls.get())
            val first = playback!!; playback = null; first(); finished.set(true)
            await { spoken.size == 4 }; val last = playback!!; playback = null; last()
            await { !store.runPresentation!!.busy && voice.state == VoiceState.READY }
            assertEquals(listOf("Working.", "Thinking.", "The answer starts here.", "More detail."), spoken)
            assertEquals(1, calls.get()); assertEquals(1, saved.size)
            assertEquals(1, recognitions.get())
        } finally { voice.close(); store.close(); scope.cancel(); client.close(); local.close() }
    }
}
