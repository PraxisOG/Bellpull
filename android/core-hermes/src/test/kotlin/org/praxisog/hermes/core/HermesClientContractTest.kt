package org.praxisog.hermes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.praxisog.hermes.model.HermesError
import org.praxisog.hermes.model.HermesResult
import org.praxisog.hermes.model.StreamEvent

/**
 * Contract tests against mock-hermes, which replays responses recorded from live Hermes
 * 0.21.1. Gradle starts and stops the server around the test task.
 *
 * The point of these is the error paths as much as the happy ones: Slice 2's accept criterion
 * is that *every* failure comes back as a typed scoped error.
 */
class HermesClientContractTest {

    private fun <T> ok(result: HermesResult<T>): T = when (result) {
        is HermesResult.Ok -> result.value
        is HermesResult.Err -> throw AssertionError("expected success, got ${result.error.summary}")
    }

    private fun err(result: HermesResult<*>): HermesError = when (result) {
        is HermesResult.Err -> result.error
        is HermesResult.Ok -> throw AssertionError("expected failure, got ${result.value}")
    }

    // ---- happy paths ----------------------------------------------------------

    @Test fun foregroundConnectionProbeIsAuthenticatedAndProfileScoped() {
        val good = MockHermes.client()
        val bad = MockHermes.client(key = "synthetic-wrong-key")
        try {
            ok(good.checkConnection())
            assertTrue(err(bad.checkConnection()) is HermesError.AuthFailed)
            bad.close()
            assertTrue(err(bad.checkConnection()) is HermesError.Unreachable)
            ok(good.checkConnection())
        } finally { good.close(); bad.close() }
    }

    @Test fun capabilitiesReportsStreamingSupport() {
        val caps = ok(MockHermes.client().capabilities())
        assertTrue("session_chat_streaming should be advertised", caps.sessionChatStreaming)
        assertTrue("expected the full flag set", caps.features.size > 20)
    }

    @Test fun listSessionsDecodesTheFlatEnvelope() {
        val list = ok(MockHermes.client().listSessions(source = "api_server"))
        assertEquals(1, list.data.size)
        // The recorded response was captured mid-pagination: limit 1, offset 4, more available.
        assertEquals(1, list.limit)
        assertEquals(4, list.offset)
        assertTrue("has_more should survive decoding", list.hasMore)
        val session = list.data.first()
        assertEquals(MockHermes.FIXTURE_SESSION_ID, session.id)
        assertEquals("api_server", session.source)
        assertEquals(4, session.messageCount)
        assertEquals(12109L, session.inputTokens)
        assertNotNull("last_active is present in list responses", session.lastActive)
    }

    @Test fun createSessionUnwrapsTheSessionEnvelope() {
        // The regression this guards: POST returns {object, session}, not {object, data}.
        val session = ok(MockHermes.client().createSession(title = "from a test"))
        assertTrue(session.id.startsWith("api_"))
        assertEquals(0, session.messageCount)
    }

    @Test fun messagesDecodeIncludingToolCallsAndNestedPagination() {
        val history = ok(MockHermes.client().messages(MockHermes.FIXTURE_SESSION_ID))
        assertEquals(4, history.data.size)
        assertEquals(listOf("user", "assistant", "tool", "assistant"), history.data.map { it.role })
        assertEquals(4, history.pagination?.returned)
        val toolCall = history.data.first { it.toolCalls != null }.toolCalls!!.single()
        assertEquals("fixture_tool", toolCall.function?.name)
        val toolMessage = history.data.first { it.role == "tool" }
        assertEquals("fixture_tool", toolMessage.toolName)
    }

    @Test fun streamDeliversEventsInOrderAndEndsTerminally() {
        val seen = mutableListOf<StreamEvent>()
        val result = MockHermes.client().streamTurn(MockHermes.FIXTURE_SESSION_ID, "hello") { seen += it }
        ok(result)
        assertTrue(seen.first() is StreamEvent.RunStarted)
        assertTrue(seen.last() is StreamEvent.Done)
        assertTrue("expected token deltas", seen.filterIsInstance<StreamEvent.AssistantDelta>().size >= 4)
        val completed = seen.filterIsInstance<StreamEvent.AssistantCompleted>().single()
        assertTrue(completed.completed)
        assertTrue("a finished turn must not look partial", !completed.partial && !completed.interrupted)
        assertEquals("sequence numbers should be monotonic", seen.mapNotNull { it.seq }.sorted(), seen.mapNotNull { it.seq })
    }

    @Test fun runStatusDecodes() {
        val status = ok(MockHermes.client().runStatus(MockHermes.FIXTURE_RUN_ID))
        assertEquals(MockHermes.FIXTURE_RUN_ID, status.runId)
        assertEquals("completed", status.status)
        assertTrue(status.isTerminal)
        assertEquals("run.completed", status.lastEvent)
    }

    @Test fun stopReturnsStoppingAndOmitsMostFields() {
        // The stop response carries only run_id and status — no `object`, no timestamps.
        val status = ok(MockHermes.client().stopRun(MockHermes.FIXTURE_RUN_ID))
        assertEquals("stopping", status.status)
        assertTrue(status.isStopping)
        assertEquals(null, status.`object`)
    }

    @Test fun stopTooEarlyIsNotStoppableYetNotAGenericFailure() {
        // Gate 0d's registration race. A client must be able to tell "too early, try again"
        // from "this went wrong", or it will show the user an error for a benign condition.
        val client = MockHermes.client(httpClient = MockHermes.faultingClient("run-not-active"))
        val error = err(client.stopRun(MockHermes.FIXTURE_RUN_ID))
        assertEquals(HermesError.RunNotStoppableYet(MockHermes.FIXTURE_RUN_ID), error)
    }

    // ---- error paths ----------------------------------------------------------

    @Test fun wrongKeyIsAuthFailed() {
        val error = err(MockHermes.client(key = "definitely-not-the-key").capabilities())
        assertTrue("got ${error::class.simpleName}", error is HermesError.AuthFailed)
    }

    @Test fun unknownSessionIsSessionNotFound() {
        val error = err(MockHermes.client().messages("api_0000000000_nope"))
        assertEquals(HermesError.SessionNotFound("api_0000000000_nope"), error)
    }

    @Test fun nonJsonErrorBodyDoesNotCrashTheParser() {
        // An unknown path returns the plain text "404: Not Found". Reached by pointing the
        // client at a prefix that does not exist, which is what a misconfigured base URL does.
        val error = err(MockHermes.client(baseUrl = "${MockHermes.baseUrl}/wrong-prefix").capabilities())
        val unparseable = error as? HermesError.UnparseableError
            ?: throw AssertionError("expected UnparseableError, got ${error.summary}")
        assertEquals(404, unparseable.status)
        assertTrue("should carry the real body", unparseable.bodySnippet.contains("404"))
    }

    @Test fun aRedirectIsRefusedNotFollowed() {
        // The regression this guards: the client this replaced set instanceFollowRedirects=false
        // and reported 3xx. OkHttp follows redirects by default, and drops the Authorization
        // header only on a host change — so a same-host redirect would carry a root-equivalent
        // key to wherever it pointed.
        val client = MockHermes.client(httpClient = MockHermes.faultingClient("redirect"))
        val error = err(client.capabilities())
        val redirect = error as? HermesError.UnexpectedRedirect
            ?: throw AssertionError("expected UnexpectedRedirect, got ${error.summary}")
        assertEquals(302, redirect.status)
        assertTrue("should report where it was sent", redirect.location!!.contains("example.invalid"))
    }

    @Test fun unreachableServerIsTypedNotThrown() {
        // Port 1 is reserved and nothing listens there.
        val error = err(MockHermes.client(baseUrl = "http://127.0.0.1:1").capabilities())
        val unreachable = error as? HermesError.Unreachable
            ?: throw AssertionError("expected Unreachable, got ${error.summary}")
        assertEquals(HermesError.Unreachable.Kind.CONNECT_REFUSED, unreachable.kind)
    }

    @Test fun rateLimitBacksOffAndHonoursRetryAfterInsteadOfSpinning() {
        val delays = mutableListOf<Long>()
        val client = MockHermes.client(
            httpClient = MockHermes.faultingClient("rate-limit"),
            sleeper = { delays += it },
        )
        val error = err(client.capabilities())
        val limited = error as? HermesError.RateLimited
            ?: throw AssertionError("expected RateLimited, got ${error.summary}")
        assertEquals("Retry-After from the server", 1L, limited.retryAfterSeconds)
        assertEquals("should retry a bounded number of times, then give up", 3, delays.size)
        assertTrue("must actually wait between attempts, not spin", delays.all { it >= 1000L })
    }

    @Test fun droppedStreamIsTruncatedNotSuccess() {
        val seen = mutableListOf<StreamEvent>()
        val client = MockHermes.client(httpClient = MockHermes.faultingClient("stream-abort"))
        val error = err(client.streamTurn(MockHermes.FIXTURE_SESSION_ID, "hello") { seen += it })
        val truncated = error as? HermesError.StreamTruncated
            ?: throw AssertionError("expected StreamTruncated, got ${error.summary}")
        assertEquals("mock drops after three events", 3, truncated.eventsReceived)
        assertEquals(3, seen.size)
        assertTrue("events before the drop are still usable", seen.first() is StreamEvent.RunStarted)
        assertTrue("no terminal event arrived", seen.none { it.isTerminal })
    }

    @Test fun blankMessageIsRejectedBeforeTheRequest() {
        val client = MockHermes.client()
        val thrown = runCatching { client.streamTurn(MockHermes.FIXTURE_SESSION_ID, "   ") { } }.exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $thrown", thrown is IllegalArgumentException)
    }

    @Test fun cancellingAStreamStopsIt() {
        val seen = mutableListOf<StreamEvent>()
        val client = MockHermes.client()
        val result = client.streamTurn(
            MockHermes.FIXTURE_SESSION_ID,
            "hello",
            onStart = { it.cancel() },
        ) { seen += it }
        // A cancelled call surfaces as a typed transport error, never as a clean finish.
        val error = err(result)
        assertTrue("got ${error.summary}", error is HermesError.Unreachable || error is HermesError.StreamTruncated)
    }
}
