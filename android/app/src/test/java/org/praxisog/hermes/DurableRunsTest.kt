package org.praxisog.hermes

import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class DurableRunsTest {
    @Test fun lostAcknowledgementRetriesThePersistedModelAndAttachmentsUnchanged() {
        loseAck = true
        val options = BellpullRunOptions(selection = ModelSelection("fixture-model", "fixture", ModelOptions(ReasoningChoice(false))),
            artifactIds = listOf("a".repeat(32)))
        val manager = manager()
        manager.send("one", "hello", emptySet(), options)
        await { manager.states["one"]?.submission?.runId != null }
        val submissions = calls.filter { it.url.encodedPath == "/v1/runs" }
        assertEquals(2, submissions.size)
        assertEquals(options, saved.values.single().bellpull)
        val bodies = submissions.map { request -> okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8() }
        assertEquals(bodies.first(), bodies.last())
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val calls = CopyOnWriteArrayList<Request>()
    private val saved = java.util.concurrent.ConcurrentHashMap<String, RunSubmission>()
    private var storeWorks = true
    @Volatile private var loseAck = false
    @Volatile private var runStatus = "running"
    @Volatile private var approval = ""
    @Volatile private var rejectStop = false
    @Volatile private var historyOffline = false
    @Volatile private var historyLag = false
    private val eventBodies = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val drafts = ChatDrafts()
    private val journal = object : RunJournal {
        override fun readRuns() = saved.values.toList()
        override fun writeRun(value: RunSubmission): Boolean {
            if (!storeWorks) return false
            saved[value.requestId] = value
            return true
        }
    }
    private val managers = mutableListOf<DurableRuns>()
    private val http = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
        val request = chain.request()
        calls += request
        val path = request.url.encodedPath
        if (path.endsWith("/messages") && historyOffline) throw IOException("History temporarily offline")
        var code = 200
        val body = when {
            path == "/v1/runs" -> {
                assertTrue("journal must precede submission", saved.values.any { it.requestId == request.header("Idempotency-Key") })
                if (loseAck) { loseAck = false; throw IOException("Acknowledgement lost after admission") }
                """{"run_id":"run_same","status":"running","replayed":true}"""
            }
            path.endsWith("/events") -> eventBodies.poll() ?: "event: message.delta\ndata: {\"run_id\":\"run_same\",\"delta\":\"partial\"}\n\n"
            path.endsWith("/stop") -> if (rejectStop) {
                code = 409
                """{"error":{"code":"run_not_active","message":"Not ready"}}"""
            } else { runStatus = "cancelled"; """{"run_id":"run_same","status":"stopping"}""" }
            path.endsWith("/approval") -> { approval = ""; """{"resolved":1}""" }
            path.endsWith("/messages") && historyLag -> """{"data":[{"id":10,"role":"user","content":"hello"},{"id":11,"role":"assistant","content":"Working","tool_calls":[{"id":"tool","function":{"name":"terminal"}}]}]}"""
            path.endsWith("/messages") -> if (runStatus == "completed")
                """{"data":[{"id":10,"role":"user","content":"hello"},{"id":11,"role":"assistant","content":"saved answer"}]}"""
                else """{"data":[]}"""
            else -> """{"run_id":"run_same","session_id":"one","status":"$runStatus","created_at":100,"output":"saved answer"$approval}"""
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody((if (path.endsWith("/events")) "text/event-stream" else "application/json").toMediaType())).build()
    }.build()
    private fun manager(now: Double = 100.0): DurableRuns = DurableRuns(scope,
        HermesClient("http://fixture.invalid", { "test-only" }, httpClient = http), journal, drafts, 86400,
        clock = { now }, pollMs = 25, retryMs = 10).also(managers::add)
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < end) Thread.sleep(5)
        assertTrue("condition timed out", condition())
    }
    @After fun cleanup() { managers.forEach { it.close() }; scope.cancel() }

    @Test fun reasoningAndInterimProseUpdateWhileTheRunIsStillWorking() {
        eventBodies.add("event: reasoning.delta\ndata: {\"seq\":0,\"text\":\"Checking \"}\n\n" +
            "event: reasoning.delta\ndata: {\"seq\":1,\"text\":\"the files.\"}\n\n" +
            "event: message.interim\ndata: {\"seq\":2,\"text\":\"Reading your notes.\",\"already_streamed\":false}\n\n" +
            "event: message.interim\ndata: {\"seq\":3,\"text\":\"Reading your notes.\",\"already_streamed\":true}\n\n")
        val manager = manager()
        manager.send("one", "hello", emptySet())
        await { (manager.states["one"]?.turn as? TurnState.Streaming)?.reasoning == "Checking the files." }
        await { (manager.states["one"]?.turn as? TurnState.Streaming)?.text == "Reading your notes." }
        val state = manager.states.getValue("one")
        assertFalse(state.submission.terminal)
        assertEquals(listOf(true, false), (state.turn as TurnState.Streaming).parts.filterIsInstance<LivePart.Prose>().map { it.thinking })
        assertEquals(1, calls.count { it.url.encodedPath == "/v1/runs" })
    }

    @Test fun droppedStreamResumesWithoutRepeatingTextOrSubmittingAgain() {
        eventBodies.add("event: message.delta\ndata: {\"seq\":0,\"delta\":\"Hello \"}\n\n")
        eventBodies.add("event: message.delta\ndata: {\"seq\":0,\"delta\":\"Hello \"}\n\nevent: message.delta\ndata: {\"seq\":1,\"delta\":\"world\"}\n\n")
        val manager = manager()
        manager.send("one", "hello", emptySet())
        await { (manager.states["one"]?.turn as? TurnState.Streaming)?.text == "Hello world" }
        val connections = calls.filter { it.url.encodedPath.endsWith("/events") }
        assertNull(connections[0].header("Last-Event-ID"))
        assertEquals("0", connections[1].header("Last-Event-ID"))
        assertEquals(1, calls.count { it.url.encodedPath == "/v1/runs" })
    }

    @Test fun truncatedReplayKeepsReadableTextInsteadOfJoiningAnUnknownTail() {
        eventBodies.add("event: message.delta\ndata: {\"seq\":0,\"delta\":\"Readable prefix\"}\n\n")
        eventBodies.add("event: replay.truncated\ndata: {\"oldest_retained_seq\":5}\n\nevent: message.delta\ndata: {\"seq\":5,\"delta\":\"wrong tail\"}\n\n")
        val manager = manager()
        manager.send("one", "hello", emptySet())
        await { manager.states["one"]?.notice?.contains("Live text is unavailable") == true }
        assertEquals("Readable prefix", (manager.states["one"]!!.turn as TurnState.Streaming).text)
    }

    @Test fun restoredRunCanReplayACompleteSequencedStream() {
        saved["request"] = RunSubmission("request", "one", "hello", 100.0, 1, emptySet(), "run_same", "running")
        eventBodies.add("event: message.delta\ndata: {\"seq\":0,\"delta\":\"Restored text\"}\n\n")
        val manager = manager()
        manager.restore()
        await { (manager.states["one"]?.turn as? TurnState.Streaming)?.text == "Restored text" }
        assertTrue(calls.none { it.url.encodedPath == "/v1/runs" })
    }

    @Test fun completionWaitsForSavedFinalReplyAfterOldHistoryReturns200() {
        runStatus = "completed"
        historyLag = true
        val manager = manager()
        var reply: Message? = null
        manager.onCompleted = { _, value -> reply = value }
        manager.send("one", "hello", emptySet())
        await { manager.states["one"]?.notice?.contains("Waiting for its saved reply") == true }
        assertFalse(manager.states["one"]!!.submission.historySynced)
        assertNull(reply)
        historyLag = false
        await { manager.states["one"]?.submission?.historySynced == true }
        assertEquals("saved answer", reply?.content)
    }

    @Test fun lostAdmissionRetriesSameIdentityAndPreservesNewTyping() {
        drafts.set("one", TextFieldValue("hello"))
        loseAck = true
        val manager = manager()
        manager.send("one", "hello", emptySet())
        await { calls.any { it.url.encodedPath == "/v1/runs" } }
        drafts.set("one", TextFieldValue("new question"))
        await { manager.states["one"]?.submission?.runId != null }
        val submissions = calls.filter { it.url.encodedPath == "/v1/runs" }
        assertEquals(2, submissions.size)
        assertEquals(1, submissions.map { it.header("Idempotency-Key") }.distinct().size)
        assertEquals("new question", drafts["one"].text)
        runStatus = "completed"
        await { manager.states["one"]?.submission?.historySynced == true }
        assertEquals("new question", drafts["one"].text)
        assertTrue(manager.states["one"]!!.turn is TurnState.Idle)
    }
    @Test fun cannotSendWithoutDurableJournal() {
        storeWorks = false
        val manager = manager()
        manager.send("one", "hello", emptySet())
        await { manager.states["one"]?.submission?.status == "rejected" }
        assertTrue(calls.isEmpty())
    }
    @Test fun observedCompletionUsesSavedReplyWithoutInventingPromptOrDuplicatingOutput() {
        runStatus = "completed"
        drafts.set("one", TextFieldValue("Keep my new draft"))
        val manager = manager()
        manager.observe("run_same", "one", 11) // The reply may already have been read.
        await { manager.states["one"]?.submission?.historySynced == true }
        assertTrue(manager.states["one"]!!.turn is TurnState.Idle)
        assertEquals("Keep my new draft", drafts["one"].text)
        assertTrue(calls.none { it.url.encodedPath == "/v1/runs" })
    }
    @Test fun coldRecoveryFindsSavedReplyWithoutNewSubmission() {
        saved["request"] = RunSubmission("request", "one", "hello", 100.0, 1, emptySet(), "run_same", "running")
        drafts.restore("one", TextFieldValue("new question"), 2)
        runStatus = "completed"
        val manager = manager()
        var received: Message? = null
        manager.onCompleted = { _, reply -> received = reply }
        manager.restore()
        await { manager.states["one"]?.submission?.historySynced == true }
        assertEquals(11, received?.id)
        assertEquals("new question", drafts["one"].text)
        assertTrue(calls.none { it.url.encodedPath == "/v1/runs" })
    }
    @Test fun ambiguousColdRecoveryUsesSavedIdentityAndPayload() {
        saved["request"] = RunSubmission("request", "one", "hello", 100.0, 1, emptySet())
        drafts.restore("one", TextFieldValue("hello"), 1)
        val manager = manager()
        manager.restore()
        await { manager.states["one"]?.submission?.runId != null }
        assertEquals("request", calls.first().header("Idempotency-Key"))
        await { drafts["one"].text.isEmpty() }
    }
    @Test fun completedRunRestoredOfflineReconcilesWhenHistoryReturns() {
        saved["request"] = RunSubmission("request", "one", "hello", 100.0, 1, emptySet(), "run_same", "completed")
        runStatus = "completed"
        historyOffline = true
        val manager = manager()
        manager.restore()
        await { calls.any { it.url.encodedPath.endsWith("/messages") } }
        assertTrue(manager.states["one"]!!.busy)
        historyOffline = false
        await { manager.states["one"]?.submission?.historySynced == true }
        assertFalse(manager.states["one"]!!.busy)
        assertTrue(calls.none { it.url.encodedPath == "/v1/runs" || it.url.encodedPath.endsWith("/events") })
    }
    @Test fun expiredIdentityCannotBeResubmitted() {
        saved["request"] = RunSubmission("request", "one", "hello", 1.0, 1, emptySet())
        val manager = manager(now = 90_000.0)
        manager.restore()
        await { manager.states["one"]?.submission?.status == "review" }
        assertTrue(calls.isEmpty())
    }
    @Test fun expiredRequestCanBeDismissedAfterReviewWithoutLosingNewDraft() {
        saved["request"] = RunSubmission("request", "one", "hello", 1.0, 1, emptySet())
        drafts.set("one", TextFieldValue("newer draft"))
        val manager = manager(now = 90_000.0)
        manager.restore()
        await { manager.states["one"]?.submission?.status == "review" }
        Thread.sleep(30)
        manager.abandonAfterReview("one")
        await { manager.states["one"]?.submission?.status == "abandoned" }
        assertFalse(manager.states["one"]!!.busy)
        assertEquals("newer draft", drafts["one"].text)
        assertTrue(calls.isEmpty())
    }
    @Test fun failedStopDoesNotPretendRunStopped() {
        saved["request"] = RunSubmission("request", "one", "hello", 100.0, 1, emptySet(), "run_same", "running")
        rejectStop = true
        val manager = manager()
        manager.restore()
        manager.stop("one")
        await { manager.states["one"]?.notice?.startsWith("Stop was not confirmed") == true }
        assertFalse(manager.states["one"]!!.submission.stopRequested)
    }
    @Test fun duplicateSendInSameChatIsPrevented() {
        val manager = manager()
        manager.send("one", "hello", emptySet())
        manager.send("one", "again", emptySet())
        await { manager.states["one"]?.submission?.runId != null }
        assertEquals(1, calls.count { it.url.encodedPath == "/v1/runs" })
    }
    @Test fun staleApprovalCannotApproveItsReplacement() {
        saved["request"] = RunSubmission("request", "one", "hello", 100.0, 1, emptySet(), "run_same", "running")
        runStatus = "waiting_for_approval"
        approval = """, "approval":{"request_id":"old","command":"fixture operation","choices":["once","deny"]}"""
        val manager = manager()
        manager.restore()
        await { manager.states["one"]?.approval?.requestId == "old" }
        approval = """, "approval":{"request_id":"new","command":"replacement","choices":["once","deny"]}"""
        manager.respond("one", "old", "once")
        await { manager.states["one"]?.checking == false }
        assertTrue(calls.none { it.url.encodedPath.endsWith("/approval") })
    }
}
