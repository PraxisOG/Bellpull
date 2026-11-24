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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class DurableRunsTest {
    @Test fun fallbackAndServingModelSurviveRecoveryWithoutResubmission() {
        approval = """, "model":"request-alias", "bellpull_model_fallback":{"from":{"provider":"one","model":"primary"},"to":{"provider":"two","model":"backup","vision":false}}"""
        val manager = manager()
        manager.send("one", "hello", emptySet())
        await { manager.states["one"]?.submission?.modelFallback?.to == RunModel("two", "backup", false) }
        assertNull(manager.states["one"]!!.submission.servedModel)
        approval += """, "runtime":{"provider":"two","model":"backup","route_source":"global"},"updated_at":113"""
        runStatus = "completed"
        await { saved.values.singleOrNull()?.historySynced == true }
        val complete = saved.values.single()
        assertEquals(RunModel("two", "backup"), complete.servedModel)
        manager.close()
        val reopened = manager()
        reopened.restore()
        assertEquals(complete, reopened.statsRecords[complete.requestId])
        assertEquals(1, calls.count { it.url.encodedPath == "/v1/runs" })
    }

    @Test fun openingInfoForAnOlderMatchedTurnRecoversOnlyItsRunMetadata() {
        val old = RunSubmission("request", "one", "hello", 100.0, 1, emptySet(), "run_same", "completed",
            historySynced = true, completionOutput = "saved answer")
        saved[old.requestId] = old
        runStatus = "completed"
        approval = """, "model":"public-alias", "runtime":{"provider":"cloud","model":"actual","requested":{"model":"alias"}}"""
        val history = listOf(Message(10, role = "user", content = "hello"), Message(11, role = "assistant", content = "saved answer"))
        val manager = manager()
        manager.restore()
        manager.refreshMessageRoute("one", 10, history)
        manager.refreshMessageRoute("one", 11, history)
        await { manager.statsRecords["request"]?.servedModel?.model == "actual" }
        assertNull(saved["request"]!!.modelFallback)
        assertEquals(1, calls.size)
        assertEquals("GET", calls.single().method)
    }

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
    @Test fun typedInstructionIsAppliedOnceAcrossLostAcknowledgement() {
        val config = VoiceConfiguration(typedInstructionEnabled = true, typedInstruction = "Keep it short")
        loseAck = true
        val manager = manager()
        manager.send("one", config.typedPrompt("hello"), emptySet())
        await { manager.states["one"]?.submission?.runId != null }
        val requests = calls.filter { it.url.encodedPath == "/v1/runs" }
        assertEquals(2, requests.size)
        for (request in requests) {
            val json = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            val input = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject.getValue("input").jsonPrimitive.content
            assertEquals("hello", voicePromptPresentation(input)?.body)
        }
    }
    @Test fun defaultTypedRequestContainsOnlyTheUsersWords() {
        val manager = manager()
        manager.send("one", VoiceConfiguration().typedPrompt("hello"), emptySet())
        await { manager.states["one"]?.submission?.runId != null }
        val request = calls.single { it.url.encodedPath == "/v1/runs" }
        val json = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertEquals("hello", kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject.getValue("input").jsonPrimitive.content)
    }

    @Test fun pollRestoresAndClearsCompactionWhileApprovalTakesPrecedence() {
        approval = """, "bellpull_phase":"compacting" """
        val manager = manager()
        manager.send("one", "hello", emptySet())
        fun label() = (manager.states["one"]?.turn as? TurnState.Streaming)?.statusLabel
        await { label() == "Compacting…" }
        approval += """, "approval":{"request_id":"gate"}"""
        await { label() == "Waiting for approval…" }
        approval = ""
        await { label() == "Working…" }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val calls = CopyOnWriteArrayList<Request>()
    private val saved = java.util.concurrent.ConcurrentHashMap<String, RunSubmission>()
    private var storeWorks = true
    @Volatile private var loseAck = false
    @Volatile private var runStatus = "running"
    @Volatile private var approval = ""
    @Volatile private var statusOffline = false
    @Volatile private var loseApprovalAck = false
    @Volatile private var rejectStop = false
    @Volatile private var stoppedStatus = "cancelled"
    @Volatile private var stopResponseGate: java.util.concurrent.CountDownLatch? = null
    @Volatile private var historyResponseGate: java.util.concurrent.CountDownLatch? = null
    @Volatile private var historyOffline = false
    @Volatile private var historyLag = false
    @Volatile private var historyBody: String? = null
    @Volatile private var streamFailures = 0
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
        if (path == "/v1/runs/run_same" && statusOffline) throw IOException("Status temporarily offline")
        if (path.endsWith("/messages") && historyOffline) throw IOException("History temporarily offline")
        if (path.endsWith("/messages")) historyResponseGate?.await(3, java.util.concurrent.TimeUnit.SECONDS)
        if (path.endsWith("/events") && streamFailures > 0) {
            streamFailures--
            throw IOException("SSE connection failed before headers")
        }
        var code = 200
        val body = when {
            path.endsWith("/messages") && historyBody != null -> historyBody!!
            path == "/v1/runs" -> {
                assertTrue("journal must precede submission", saved.values.any { it.requestId == request.header("Idempotency-Key") })
                if (loseAck) { loseAck = false; throw IOException("Acknowledgement lost after admission") }
                """{"run_id":"run_same","status":"running","replayed":true}"""
            }
            path.endsWith("/events") -> eventBodies.poll() ?: "event: message.delta\ndata: {\"run_id\":\"run_same\",\"delta\":\"partial\"}\n\n"
            path.endsWith("/stop") -> if (rejectStop) {
                code = 409
                """{"error":{"code":"run_not_active","message":"Not ready"}}"""
            } else {
                runStatus = stoppedStatus
                stopResponseGate?.await(3, java.util.concurrent.TimeUnit.SECONDS)
                """{"run_id":"run_same","status":"stopping"}"""
            }
            path.endsWith("/approval") -> {
                approval = ""
                if (loseApprovalAck) throw IOException("Acknowledgement lost after decision")
                """{"resolved":1}"""
            }
            path.endsWith("/messages") && historyLag -> """{"data":[{"id":10,"role":"user","content":"hello"},{"id":11,"role":"assistant","content":"Working","tool_calls":[{"id":"tool","function":{"name":"terminal"}}]}]}"""
            path.endsWith("/messages") -> if (runStatus == "completed")
                """{"data":[{"id":10,"role":"user","content":"hello"},{"id":11,"role":"assistant","content":"saved answer"}]}"""
                else """{"data":[]}"""
            else -> """{"run_id":"run_same","session_id":"one","status":"$runStatus","created_at":100,"output":"saved answer"$approval}"""
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody((if (path.endsWith("/events")) "text/event-stream" else "application/json").toMediaType())).build()
    }.build()
    private fun manager(now: Double = 100.0, maxHistoryChecks: Int = 100): DurableRuns = DurableRuns(scope,
        HermesClient("http://fixture.invalid", { "test-only" }, httpClient = http), journal, drafts, 86400,
        clock = { now }, pollMs = 25, retryMs = 10, maxHistoryChecks = maxHistoryChecks).also(managers::add)
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < end) Thread.sleep(5)
        assertTrue("condition timed out", condition())
    }
    @After fun cleanup() { managers.forEach { it.close() }; scope.cancel() }

    @Test fun compactedPromptReconcilesOnlyWhenTheMatchingFinalReplyIsSaved() {
        runStatus = "completed"
        historyBody = """{"session_id":"tip","data":[{"id":20,"role":"user","content":"Compaction summary","timestamp":101}]}"""
        val manager = manager()
        manager.send("one", "hello", setOf(1, 2))
        await { manager.states["one"]?.submission?.historyChecks ?: 0 > 0 }
        assertTrue(manager.states["one"]!!.busy)
        historyBody = """{"session_id":"tip","data":[{"id":20,"role":"user","content":"Compaction summary","timestamp":101},{"id":21,"role":"assistant","content":"saved answer","timestamp":102}]}"""
        await { manager.states["one"]?.submission?.historySynced == true }
        assertFalse(manager.states["one"]!!.busy)
        assertTrue(manager.states["one"]!!.turn is TurnState.Idle)
        assertEquals(1, calls.count { it.url.encodedPath == "/v1/runs" })
        manager.send("one", "A different follow-up", setOf(20, 21))
        await { calls.count { it.url.encodedPath == "/v1/runs" } == 2 }
        val followUp = calls.last { it.url.encodedPath == "/v1/runs" }
        val body = okio.Buffer().also { followUp.body!!.writeTo(it) }.readUtf8()
        assertTrue(body.contains("A different follow-up"))
    }

    @Test fun repeatedOldPromptAndIdenticalAnswerCannotSettleTheNewRun() {
        runStatus = "completed"
        historyBody = """{"data":[{"id":1,"role":"user","content":"hello"},{"id":2,"role":"assistant","content":"saved answer"},{"id":10,"role":"user","content":"hello"},{"id":11,"role":"assistant","content":"Interim text"}]}"""
        val manager = manager()
        manager.send("one", "hello", setOf(1, 2))
        await { manager.states["one"]?.submission?.historyChecks ?: 0 > 0 }
        assertTrue(manager.states["one"]!!.busy)
        historyBody = """{"data":[{"id":1,"role":"user","content":"hello"},{"id":2,"role":"assistant","content":"saved answer"},{"id":10,"role":"user","content":"hello"},{"id":12,"role":"assistant","content":"saved answer"}]}"""
        await { manager.states["one"]?.submission?.historySynced == true }
        assertEquals(1, calls.count { it.url.encodedPath == "/v1/runs" })
    }

    @Test fun restartWhileWaitingPreservesCompletionEvidenceAndNeverResubmits() {
        runStatus = "completed"
        historyBody = """{"data":[{"id":20,"role":"user","content":"Summary","timestamp":101}]}"""
        val first = manager()
        first.send("one", "hello", setOf(1, 2))
        await { saved.values.singleOrNull()?.historyChecks ?: 0 > 0 }
        first.close()
        val persisted = saved.values.single()
        assertEquals("saved answer", persisted.completionOutput)
        assertEquals(100.0, persisted.serverCreatedAt!!, 0.0)
        val restored = manager()
        restored.restore()
        historyBody = """{"data":[{"id":21,"role":"assistant","content":"saved answer","timestamp":102}]}"""
        await { restored.states["one"]?.submission?.historySynced == true }
        assertFalse(restored.states["one"]!!.busy)
        assertEquals(1, calls.count { it.url.encodedPath == "/v1/runs" })
    }

    @Test fun unmatchedCompletionStopsPollingAndCanBeRetriedOrReleasedAfterReview() {
        runStatus = "completed"
        historyBody = """{"data":[{"id":20,"role":"assistant","content":"Unrelated summary","timestamp":101}]}"""
        val first = manager(maxHistoryChecks = 2)
        first.send("one", "hello", emptySet())
        await { first.states["one"]?.submission?.historyNeedsReview == true }
        assertFalse(first.states["one"]!!.submission.historySynced)
        assertFalse("a terminal review must not block new sends", first.states["one"]!!.busy)
        first.close()
        val restored = manager(maxHistoryChecks = 2)
        restored.restore()
        val before = calls.size
        Thread.sleep(75)
        assertEquals(before, calls.size)
        restored.retry("one")
        await { calls.size > before && restored.states["one"]?.submission?.historyNeedsReview == true }
        Thread.sleep(25)
        drafts.set("one", TextFieldValue("My newer draft"))
        restored.abandonAfterReview("one")
        await { restored.states["one"]?.submission?.trackingDismissed == true }
        assertFalse(restored.states["one"]!!.busy)
        assertFalse(restored.states["one"]!!.submission.historySynced)
        assertEquals("My newer draft", drafts["one"].text)
        val again = manager()
        again.restore()
        assertFalse(again.states["one"]!!.busy)
        assertEquals(1, calls.count { it.url.encodedPath == "/v1/runs" })
    }

    @Test fun stoppedCompletedRunSettlesWithoutMatchingPartialOutput() {
        saved["request"] = RunSubmission("request", "one", "hello", 100.0, 1, emptySet(), "run_same", "completed",
            stopRequested = true, completionOutput = "Partial reply")
        historyBody = """{"data":[{"id":10,"role":"user","content":"hello"}]}"""
        val manager = manager()
        manager.restore()
        await { manager.states["one"]?.submission?.historySynced == true }
        assertFalse(manager.states["one"]!!.busy)
        assertEquals(TurnState.StoppedByUser("Partial reply"), manager.states["one"]!!.turn)
        assertEquals(0, manager.states["one"]!!.submission.historyChecks)
        assertTrue(calls.all { it.url.encodedPath.endsWith("/messages") })
    }

    @Test fun stoppedRunDoesNotHoldReplacementBehindAnInFlightHistoryFetch() {
        saved["request"] = RunSubmission("request", "one", "hello", 100.0, 1, emptySet(), "run_same", "completed",
            stopRequested = true)
        val gate = java.util.concurrent.CountDownLatch(1)
        historyResponseGate = gate
        val manager = manager()
        try {
            manager.restore()
            await { calls.any { it.url.encodedPath.endsWith("/messages") } }
            assertTrue(manager.send("one", "Replacement", emptySet()))
            await { calls.any { it.url.encodedPath == "/v1/runs" } }
            gate.countDown()
            await { manager.states["one"]?.submission?.runId != null }
            assertEquals("Replacement", manager.states["one"]!!.submission.text)
            assertFalse(manager.states["one"]!!.submission.stopRequested)
            assertEquals(1, calls.count { it.url.encodedPath == "/v1/runs" })
        } finally { gate.countDown() }
    }

    @Test fun replacementStartsEvenFromThePreviousTrackersFinalCallback() {
        runStatus = "completed"
        historyBody = """{"data":[]}"""
        val manager = manager(maxHistoryChecks = 1)
        var replacementAccepted = false
        manager.onChanged = { id ->
            if (manager.states[id]?.submission?.text == "hello" && manager.states[id]?.submission?.historyNeedsReview == true) {
                replacementAccepted = manager.send(id, "Replacement", emptySet())
            }
        }
        manager.send("one", "hello", emptySet())
        await { calls.count { it.url.encodedPath == "/v1/runs" } == 2 }
        assertTrue(replacementAccepted)
        assertEquals("Replacement", manager.states["one"]!!.submission.text)
    }

    @Test fun stopAcknowledgedAfterTerminalStatusKeepsATerminalPresentation() {
        stoppedStatus = "completed"
        historyBody = """{"data":[]}"""
        val gate = java.util.concurrent.CountDownLatch(1)
        stopResponseGate = gate
        val manager = manager()
        try {
            manager.send("one", "hello", emptySet())
            await { manager.states["one"]?.submission?.runId != null }
            manager.stop("one")
            await { manager.states["one"]?.submission?.terminal == true }
            gate.countDown()
            await { manager.states["one"]?.submission?.stopRequested == true }
            assertTrue(manager.states["one"]!!.turn is TurnState.StoppedByUser)
            assertFalse(manager.states["one"]!!.busy)
            await { manager.states["one"]?.submission?.historySynced == true }
        } finally { gate.countDown() }
    }

    @Test fun reasoningAndInterimProseUpdateWhileTheRunIsStillWorking() {
        // Actual stock /v1/runs frames have no separate event: line.
        eventBodies.add("id: 0\ndata: {\"event\":\"reasoning.delta\",\"seq\":0,\"text\":\"Checking \"}\n\n" +
            "id: 1\ndata: {\"event\":\"reasoning.delta\",\"seq\":1,\"text\":\"the files.\"}\n\n" +
            "id: 2\ndata: {\"event\":\"message.interim\",\"seq\":2,\"text\":\"Reading your notes.\",\"already_streamed\":false}\n\n" +
            "id: 3\ndata: {\"event\":\"message.interim\",\"seq\":3,\"text\":\"Reading your notes.\",\"already_streamed\":true}\n\n")
        val manager = manager()
        manager.send("one", "hello", emptySet())
        await { (manager.states["one"]?.turn as? TurnState.Streaming)?.reasoning == "Checking the files." }
        await { (manager.states["one"]?.turn as? TurnState.Streaming)?.text == "Reading your notes." }
        val state = manager.states.getValue("one")
        assertFalse(state.submission.terminal)
        assertEquals(listOf(true, false), (state.turn as TurnState.Streaming).parts.filterIsInstance<LivePart.Prose>().map { it.thinking })
        assertEquals(1, calls.count { it.url.encodedPath == "/v1/runs" })
    }

    @Test fun failureBeforeFirstFrameRetriesSubscriptionAndShowsThinkingAndAnswer() {
        streamFailures = 1
        eventBodies.add("id: 0\ndata: {\"event\":\"reasoning.delta\",\"seq\":0,\"text\":\"Live thinking\"}\n\n" +
            "id: 1\ndata: {\"event\":\"message.delta\",\"seq\":1,\"delta\":\"Live answer\"}\n\n")
        val manager = manager()
        manager.send("one", "hello", emptySet())
        await { (manager.states["one"]?.turn as? TurnState.Streaming)?.text == "Live answer" }
        assertEquals("Live thinking", (manager.states["one"]!!.turn as TurnState.Streaming).reasoning)
        val connections = calls.filter { it.url.encodedPath.endsWith("/events") }
        assertTrue(connections.size >= 2)
        assertNull(connections[1].header("Last-Event-ID"))
        assertEquals(1, calls.count { it.url.encodedPath == "/v1/runs" })
        manager.close()
        val count = calls.count { it.url.encodedPath.endsWith("/events") }
        Thread.sleep(75)
        assertEquals(count, calls.count { it.url.encodedPath.endsWith("/events") })
    }

    @Test fun consumedLegacyStreamFallsBackWithoutReplayingUnsequencedText() {
        val manager = manager()
        manager.send("one", "hello", emptySet())
        await { manager.states["one"]?.notice?.contains("Live text is unavailable") == true }
        assertEquals("partial", (manager.states["one"]!!.turn as TurnState.Streaming).text)
        assertEquals(1, calls.count { it.url.encodedPath.endsWith("/events") })
        assertEquals(1, calls.count { it.url.encodedPath == "/v1/runs" })
    }

    @Test fun terminalStatusCancelsPreFrameSubscriptionRetries() {
        streamFailures = 100
        val manager = manager()
        manager.send("one", "hello", emptySet())
        await { calls.count { it.url.encodedPath.endsWith("/events") } >= 2 }
        runStatus = "completed"
        await { manager.states["one"]?.submission?.historySynced == true }
        val count = calls.count { it.url.encodedPath.endsWith("/events") }
        Thread.sleep(100)
        assertEquals(count, calls.count { it.url.encodedPath.endsWith("/events") })
        assertEquals(1, calls.count { it.url.encodedPath == "/v1/runs" })
    }

    @Test fun droppedStreamResumesWithoutRepeatingTextOrSubmittingAgain() {
        eventBodies.add("event: message.delta\ndata: {\"seq\":0,\"delta\":\"Hello \"}\n\n")
        eventBodies.add("id: 0\ndata: {\"event\":\"message.delta\",\"seq\":0,\"delta\":\"Hello \"}\n\nid: 1\ndata: {\"event\":\"message.delta\",\"seq\":1,\"delta\":\"world\"}\n\n")
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
    @Test fun rejectedStopRemainsVisibleAcrossLaterSuccessfulStatusPolls() {
        saved["request"] = RunSubmission("request", "one", "hello", 100.0, 1, emptySet(), "run_same", "running")
        rejectStop = true
        val manager = manager()
        manager.restore()
        manager.stop("one")
        await { calls.any { it.url.encodedPath.endsWith("/stop") } && manager.states["one"]?.checking == false }
        val polls = calls.count { it.url.encodedPath == "/v1/runs/run_same" }
        await { calls.count { it.url.encodedPath == "/v1/runs/run_same" } >= polls + 3 }
        assertTrue(manager.states["one"]!!.notice.orEmpty().startsWith("Stop was not confirmed"))
        assertTrue(manager.states["one"]!!.busy)
        assertFalse(manager.states["one"]!!.submission.stopRequested)
        rejectStop = false
        manager.stop("one")
        await { manager.states["one"]?.submission?.stopRequested == true }
        assertFalse(manager.states["one"]!!.notice.orEmpty().startsWith("Stop was not confirmed"))
    }
    @Test fun duplicateSendInSameChatIsPrevented() {
        val manager = manager()
        manager.send("one", "hello", emptySet())
        manager.send("one", "again", emptySet())
        await { manager.states["one"]?.submission?.runId != null }
        assertEquals(1, calls.count { it.url.encodedPath == "/v1/runs" })
    }
    @Test fun approvalStaysPendingWhenStatusCannotBeCheckedAndOnlyExplicitRetryPostsTheDecision() {
        saved["request"] = RunSubmission("request", "one", "hello", 100.0, 1, emptySet(), "run_same", "running")
        runStatus = "waiting_for_approval"
        approval = """, "approval":{"request_id":"gate","command":"fixture operation","choices":["once","deny"]}"""
        val manager = manager(); manager.restore()
        await { manager.states["one"]?.approval?.requestId == "gate" }
        manager.respond("one", "gate", "once", expectedRunId = "earlier-run")
        statusOffline = true
        val outcomes = CopyOnWriteArrayList<ApprovalOutcome>()
        manager.respond("one", "gate", "once", expectedRunId = "run_same", onResult = { outcomes += it })
        await { manager.states["one"]?.checking == false }
        assertEquals("gate", manager.states["one"]?.approval?.requestId)
        assertTrue(calls.none { it.url.encodedPath.endsWith("/approval") })
        assertEquals(listOf(ApprovalOutcome.CHECK_FAILED), outcomes)
        statusOffline = false
        manager.respond("one", "gate", "deny", expectedRunId = "run_same", onResult = { outcomes += it })
        await { outcomes.lastOrNull() == ApprovalOutcome.ACCEPTED }
        await { calls.any { it.url.encodedPath.endsWith("/approval") } }
        val decision = calls.single { it.url.encodedPath.endsWith("/approval") }
        val body = okio.Buffer().also { decision.body!!.writeTo(it) }.readUtf8()
        assertTrue(body.contains("\"choice\":\"deny\"")); assertTrue(body.contains("\"resolve_all\":false"))
    }
    @Test fun lostDecisionAcknowledgementIsReportedAsUnconfirmedAndDoesNotReplay() {
        saved["request"] = RunSubmission("request", "one", "hello", 100.0, 1, emptySet(), "run_same", "running")
        runStatus = "waiting_for_approval"
        approval = """, "approval":{"request_id":"gate","command":"label the card","choices":["once","deny"]}"""
        val manager = manager()
        manager.restore()
        await { manager.states["one"]?.approval?.requestId == "gate" }
        loseApprovalAck = true
        val outcomes = CopyOnWriteArrayList<ApprovalOutcome>()
        assertTrue(manager.respond("one", "gate", "once", onResult = { outcomes += it }))
        await { outcomes.isNotEmpty() && manager.states["one"]?.approval == null }
        assertEquals(listOf(ApprovalOutcome.UNCONFIRMED), outcomes)
        assertFalse(manager.respond("one", "gate", "once"))
        assertEquals(1, calls.count { it.url.encodedPath.endsWith("/approval") })
    }
    @Test fun staleApprovalCannotApproveItsReplacement() {
        saved["request"] = RunSubmission("request", "one", "hello", 100.0, 1, emptySet(), "run_same", "running")
        runStatus = "waiting_for_approval"
        approval = """, "approval":{"request_id":"old","command":"fixture operation","choices":["once","deny"]}"""
        val manager = manager()
        manager.restore()
        await { manager.states["one"]?.approval?.requestId == "old" }
        approval = """, "approval":{"request_id":"new","command":"replacement","choices":["once","deny"]}"""
        val outcomes = CopyOnWriteArrayList<ApprovalOutcome>()
        manager.respond("one", "old", "once", onResult = { outcomes += it })
        await { manager.states["one"]?.checking == false }
        assertEquals(listOf(ApprovalOutcome.CHANGED), outcomes)
        assertTrue(calls.none { it.url.encodedPath.endsWith("/approval") })
    }
}
