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
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real store/client/durable journal with fake inference and audio ports.
 *
 * Threading mirrors production: BellpullRuntime owns a single-threaded
 * `Dispatchers.Main.immediate` scope; AndroidVoiceCapture marshals every PCM frame to Main
 * (VoiceAudio) and LocalVoiceOutput delivers playback callbacks on Main
 * (QwenVoiceOutput.callbacks). VoiceSession and HermesStore keep unsynchronized Compose
 * state under that one-writer confinement, so these tests drive every voice/store entry
 * point and every playback-completion callback through ONE executor thread that also backs
 * the store scope. When the scope ran on a pool thread while the JUnit thread mutated voice
 * state, the snapshotFlow observer could pair a pre-send `run()` read with a post-send
 * `ownedRun` write and misfire the ownership guard ("Another turn started in this chat")
 * under CI load — that, not the voice logic, was the interruption-test flake. Cross-thread
 * ordering now flows only through explicit playback-completion callbacks; the polling
 * helpers below wait for asynchronous HTTP/poll settle, never to race a callback into place.
 */
class VoiceStoreTest {
    /** Single-threaded stand-in for Android Main; every voice/store call runs on it. */
    private class MainSim(name: String) : AutoCloseable {
        val executor = Executors.newSingleThreadExecutor { Thread(it, name) }
        val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        fun <T> onMain(body: () -> T): T = executor.submit<T> { body() }.get()
        override fun close() { scope.cancel(); executor.shutdownNow() }
    }

    private fun fixtureJournal(saved: ConcurrentHashMap<String, RunSubmission>) = object : RunJournal {
        override fun readRuns() = saved.values.toList()
        override fun writeRun(value: RunSubmission): Boolean { saved[value.requestId] = value; return true }
    }

    @Test fun confirmedInterruptionCallsTheExistingStopEndpointBeforeOneReplacementAdmission() {
        val main = MainSim("voice-main-interrupt")
        val admissions = AtomicInteger(); val stops = AtomicInteger()
        val saved = ConcurrentHashMap<String, RunSubmission>()
        val spoken = CopyOnWriteArrayList<String>()
        val playbackCompletions = LinkedBlockingQueue<() -> Unit>()
        val http = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val body = when {
                path == "/v1/runs" -> {
                    val number = admissions.incrementAndGet()
                    assertEquals("journal must precede admission", number, saved.size)
                    """{"run_id":"run_$number","status":"running"}"""
                }
                path == "/v1/runs/run_1/stop" -> { stops.incrementAndGet(); """{"run_id":"run_1","session_id":"one","status":"running"}""" }
                path.endsWith("/events") -> ""
                path.endsWith("/messages") -> if (stops.get() > 0)
                    """{"data":[{"id":1,"role":"user","content":"First question."},{"id":2,"role":"assistant","content":"Partial old answer."}]}""" else """{"data":[]}"""
                path == "/v1/runs/run_1" && stops.get() > 0 ->
                    """{"run_id":"run_1","session_id":"one","status":"cancelled","output":"Partial old answer."}"""
                path.startsWith("/v1/runs/") ->
                    """{"run_id":"${path.substringAfterLast('/')}", "session_id":"one","status":"running"}"""
                else -> """{"data":[]}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body.toResponseBody()).build()
        }.build()
        val client = HermesClient("https://fixture.invalid", { "fixture" }, httpClient = http)
        val store = main.onMain { HermesStore(main.scope, client, durableRetention = 86400, journal = fixtureJournal(saved)) }
        val voice = main.onMain {
            VoiceSession("one", "profile", main.scope, StoreVoiceConversation(store, "one"), VoiceConfiguration(concise = false),
                object : VoiceOutput {
                    override fun prepare(onReady: (String) -> Unit, onError: (String) -> Unit) = onReady("fixture")
                    override fun speak(text: String, started: () -> Unit, complete: () -> Unit) { spoken += text; playbackCompletions.add(complete); started() }
                    override fun stop() { playbackCompletions.clear() }
                    override fun close() = stop()
                }, object : VoiceCapture {
                    override fun start(frame: (ShortArray, Int) -> Unit, error: (String) -> Unit) = true
                    override fun close() {}
                }, initialTranscript = "First question.", transcriber = { object : VoiceTranscriber {
                    override fun transcribe(wav: ByteArray) = Result.success("Replacement question.")
                    override fun close() {}
                } })
        }
        // Waits only for the store's asynchronous settle (HTTP replies, the 2s run-status poll);
        // ordering between turns is gated on takeCompletion, a blocking queue take.
        fun await(what: String, check: () -> Boolean) { repeat(1000) { Snapshot.sendApplyNotifications(); if (check()) return; Thread.sleep(15) }; fail("Interruption integration timed out at $what: ${voice.state}, ${voice.notice}") }
        fun takeCompletion(what: String): () -> Unit {
            val completion = playbackCompletions.poll(15, TimeUnit.SECONDS)
            if (completion == null) fail("No playback completion queued for $what; spoken=$spoken")
            return completion
        }
        try {
            main.onMain { store.openSession("one") }
            await("history ready") { store.history is UiState.Ready }
            // Turn 1 admits only when the harness completes its "Working." preamble.
            main.onMain { voice.start(); voice.sendTranscript() }
            val firstPreamble = takeCompletion("first Working.")
            assertEquals("admission must wait for the playback barrier", 0, admissions.get())
            main.onMain { firstPreamble() }
            await("first admission") { admissions.get() == 1 && store.runPresentation?.submission?.runId == "run_1" }
            // Loud frames then silence close one utterance through the real VAD gate.
            main.onMain {
                repeat(25) { voice.frame(ShortArray(320) { 2500 }, 320) }
                repeat(90) { voice.frame(ShortArray(320), 320) }
            }
            await("cancellation requested") { stops.get() == 1 }
            assertEquals("exactly one original admission", 1, admissions.get())
            await("replacement preamble queued") { spoken.count { it == "Working." } == 2 }
            assertEquals("replacement must not admit before its preamble completes", 1, admissions.get())
            assertEquals("exactly one cancellation request", 1, stops.get())
            main.onMain { takeCompletion("replacement Working.")() }
            await("replacement admission") { admissions.get() == 2 && saved.values.any { it.runId == "run_2" } }
            assertEquals(1, stops.get()); assertEquals(2, saved.size)
            assertEquals("Replacement question.", saved.values.single { it.runId == "run_2" }.text.substringAfterLast("\n\n"))
            assertFalse("stale playback must never speak", spoken.any { it.contains("Partial old answer") })
            assertEquals("no unexpected pause after the replacement admitted", VoiceState.WORKING, voice.state)
            assertNotEquals(VoiceState.PAUSED, voice.state)
        } finally { main.onMain { voice.close(); store.close() }; main.close(); client.close() }
    }

    @Test fun failedStopKeepsTheReplacementWordsForReviewWithoutASecondAdmission() {
        val main = MainSim("voice-main-failed-stop")
        val admissions = AtomicInteger()
        val saved = ConcurrentHashMap<String, RunSubmission>()
        val spoken = CopyOnWriteArrayList<String>()
        val playbackCompletions = LinkedBlockingQueue<() -> Unit>()
        val http = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            // The existing cancellation endpoint refuses: cancellation failed, honestly.
            val refused = path == "/v1/runs/run_1/stop"
            val body = when {
                path == "/v1/runs" -> {
                    val number = admissions.incrementAndGet()
                    assertEquals("a failed cancellation must never admit a replacement", 1, number)
                    assertEquals(number, saved.size)
                    """{"run_id":"run_$number","status":"running"}"""
                }
                refused -> """{"error":{"code":"run_not_active","message":"Not ready"}}"""
                path.endsWith("/events") -> ""
                path.endsWith("/messages") -> """{"data":[{"id":1,"role":"user","content":"First question."}]}"""
                path.startsWith("/v1/runs/") ->
                    """{"run_id":"${path.substringAfterLast('/')}", "session_id":"one","status":"running"}"""
                else -> """{"data":[]}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (refused) 409 else 200)
                .message("fixture").body(body.toResponseBody()).build()
        }.build()
        val client = HermesClient("https://fixture.invalid", { "fixture" }, httpClient = http)
        val store = main.onMain { HermesStore(main.scope, client, durableRetention = 86400, journal = fixtureJournal(saved)) }
        val voice = main.onMain {
            VoiceSession("one", "profile", main.scope, StoreVoiceConversation(store, "one"), VoiceConfiguration(concise = false),
                object : VoiceOutput {
                    override fun prepare(onReady: (String) -> Unit, onError: (String) -> Unit) = onReady("fixture")
                    override fun speak(text: String, started: () -> Unit, complete: () -> Unit) { spoken += text; playbackCompletions.add(complete); started() }
                    override fun stop() { playbackCompletions.clear() }
                    override fun close() = stop()
                }, object : VoiceCapture {
                    override fun start(frame: (ShortArray, Int) -> Unit, error: (String) -> Unit) = true
                    override fun close() {}
                }, initialTranscript = "First question.", transcriber = { object : VoiceTranscriber {
                    override fun transcribe(wav: ByteArray) = Result.success("Replacement question.")
                    override fun close() {}
                } })
        }
        fun await(what: String, check: () -> Boolean) { repeat(1000) { Snapshot.sendApplyNotifications(); if (check()) return; Thread.sleep(15) }; fail("Failed-stop integration timed out at $what: ${voice.state}, ${voice.notice}") }
        try {
            main.onMain { store.openSession("one") }
            await("history ready") { store.history is UiState.Ready }
            main.onMain { voice.start(); voice.sendTranscript() }
            val preamble = playbackCompletions.poll(15, TimeUnit.SECONDS)
            if (preamble == null) fail("No preamble spoken")
            main.onMain { preamble!!() }
            await("first admission") { admissions.get() == 1 && store.runPresentation?.submission?.runId == "run_1" }
            main.onMain {
                repeat(25) { voice.frame(ShortArray(320) { 2500 }, 320) }
                repeat(90) { voice.frame(ShortArray(320), 320) }
            }
            await("unconfirmed stop surfaced") { store.runPresentation?.notice?.startsWith("Stop was not confirmed.") == true }
            await("voice paused for review") { voice.state == VoiceState.PAUSED }
            assertEquals("only the original run was admitted", 1, admissions.get())
            assertEquals("the new words must survive a failed cancellation for review", "Replacement question.", voice.transcript)
            assertEquals(1, saved.size)
            assertTrue("the pause must explain the failed stop", voice.notice?.contains("Stop was not confirmed") == true)
        } finally { main.onMain { voice.close(); store.close() }; main.close(); client.close() }
    }

    @Test fun voiceUsesOneDurableAdmissionAndNeverSpeaksReasoning() {
        val main = MainSim("voice-main-durable")
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
        val playbackGate = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)
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
        val store = main.onMain { HermesStore(main.scope, client, durableRetention = 86400, journal = fixtureJournal(saved)) }
        val voice = main.onMain {
            VoiceSession("one", "profile", main.scope, StoreVoiceConversation(store, "one"), VoiceConfiguration(concise = false),
                object : VoiceOutput {
                    override fun prepare(onReady: (String) -> Unit, onError: (String) -> Unit) = onReady("fixture")
                    override fun speak(text: String, started: () -> Unit, complete: () -> Unit) { playbackGate.set(complete); spoken += text; started() }
                    override fun stop() { playbackGate.set(null) }
                    override fun close() = stop()
                }, object : VoiceCapture {
                    override fun start(frame: (ShortArray, Int) -> Unit, error: (String) -> Unit) = true
                    override fun close() {}
                }, transcriber = { LocalVoiceTranscriber(local, speechModel) })
        }
        fun await(check: () -> Boolean) { repeat(1000) { Snapshot.sendApplyNotifications(); if (check()) return; Thread.sleep(15) }; fail("Voice integration timed out: ${voice.state}") }
        try {
            main.onMain { store.openSession("one") }
            await { store.history is UiState.Ready }
            main.onMain { voice.start(VoiceMode.DICTATION) }
            main.onMain {
                repeat(25) { voice.frame(ShortArray(320) { 2500 }, 320) }
                repeat(90) { voice.frame(ShortArray(320), 320) }
            }
            await { voice.state == VoiceState.REVIEW }; main.onMain { voice.sendTranscript() }
            assertEquals(0, calls.get()); val preamble = playbackGate.getAndSet(null); assertNotNull(preamble); main.onMain { preamble!!() }
            await { spoken.size == 2 }; assertEquals("Thinking.", spoken[1])
            main.onMain { playbackGate.getAndSet(null)!!() }; await { spoken.size == 3 }; assertEquals("The answer starts here.", spoken[2])
            assertTrue(store.runPresentation!!.busy); assertEquals(1, calls.get())
            val first = playbackGate.getAndSet(null); assertNotNull(first); main.onMain { first!!() }; finished.set(true)
            await { spoken.size == 4 }; val last = playbackGate.getAndSet(null); assertNotNull(last); main.onMain { last!!() }
            await { !store.runPresentation!!.busy && voice.state == VoiceState.READY }
            assertEquals(listOf("Working.", "Thinking.", "The answer starts here.", "More detail."), spoken)
            assertEquals(1, calls.get()); assertEquals(1, saved.size)
            assertEquals(1, recognitions.get())
        } finally { main.onMain { voice.close(); store.close() }; main.close(); client.close(); local.close() }
    }
}
