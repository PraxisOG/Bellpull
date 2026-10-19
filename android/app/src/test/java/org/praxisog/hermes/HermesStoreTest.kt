package org.praxisog.hermes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.praxisog.hermes.core.HermesClient

/**
 * Drives the real store against mock-hermes. These are the two Slice 3 accept criteria that
 * screenshots cannot prove — "streaming renders incrementally" and "a dropped connection
 * mid-stream shows a scoped error and leaves the session readable" — plus the refetch rule,
 * which is the subtle one.
 */
class HermesStoreTest {
    @Test fun reconciledOrReleasedCompactedPromptIsNotProjectedAfterTheSavedReply() {
        for (synced in listOf(true, false)) {
            val record = org.praxisog.hermes.model.RunSubmission("request", "one", "Archived prompt", 1.0, 1, emptySet(),
                runId = "run", status = "completed", historySynced = synced, trackingDismissed = !synced)
            val journal = object : RunJournal {
                override fun readRuns() = listOf(record)
                override fun writeRun(value: org.praxisog.hermes.model.RunSubmission) = true
            }
            val http = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body("""{"session_id":"one","data":[{"id":20,"role":"assistant","content":"Compacted summary"},{"id":21,"role":"assistant","content":"Final reply"}]}"""
                        .toResponseBody("application/json".toMediaType())).build()
            }.build()
            val client = HermesClient("http://fixture.invalid", { "fixture" }, httpClient = http)
            val store = HermesStore(scope, client, durableRetention = 86400, journal = journal)
            try {
                store.startRecovery()
                store.openSession("one")
                awaitUntil("saved compacted history") { store.history is UiState.Ready }
                assertNull(store.pendingUserText)
                assertTrue(store.turn is TurnState.Idle)
                assertEquals(listOf("Compacted summary", "Final reply"), (store.history as UiState.Ready).value.map { it.content })
            } finally { store.close(); client.close() }
        }
    }
    @Test fun conversationListDefaultsToEveryServerSourceAndStillSupportsAnExplicitFilter() {
        val sources = java.util.concurrent.CopyOnWriteArrayList<String>()
        val http = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val source = request.url.queryParameter("source")
            sources += source ?: "all"
            val rows = listOf("api_server", "cli", "tui", "telegram").filter { source == null || source == it }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(("{\"data\":[" + rows.joinToString(",") { "{\"id\":\"$it\",\"source\":\"$it\"}" } + "]}")
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val store = HermesStore(scope, HermesClient("http://fixture.invalid", { "fixture" }, httpClient = http))
        store.loadSessions()
        awaitUntil("all server conversations") { store.sessions is UiState.Ready }
        assertEquals(setOf("api_server", "cli", "tui", "telegram"), (store.sessions as UiState.Ready).value.map { it.source }.toSet())
        assertTrue(sources.all { it == "all" })
        store.chooseSessionSource("cli")
        awaitUntil("explicit CLI filter") { store.sessions is UiState.Ready }
        assertEquals(listOf("cli"), (store.sessions as UiState.Ready).value.map { it.source })
    }

    @Test fun droppedLegacyStreamLoadsLaterSavedReplyWithoutOpeningChatAgain() {
        val saved = AtomicBoolean(false)
        val submissions = AtomicInteger()
        val http = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val body = when {
                path.endsWith("/chat/stream") -> {
                    submissions.incrementAndGet()
                    "event: run.started\ndata: {\"run_id\":\"run_fixture\"}\n\nevent: message.delta\ndata: {\"delta\":\"partial\"}\n\n"
                }
                path.endsWith("/messages") -> if (saved.get())
                    """{"session_id":"one","data":[{"id":1,"role":"user","content":"hello"},{"id":2,"role":"assistant","content":"Saved after disconnect"}]}"""
                    else """{"session_id":"one","data":[]}"""
                path.startsWith("/v1/runs/") -> """{"run_id":"run_fixture","status":"${if (saved.get()) "completed" else "running"}"}"""
                else -> """{"data":[{"id":"one","source":"api_server","title":"Fixture"}]}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody((if (path.endsWith("/stream")) "text/event-stream" else "application/json").toMediaType())).build()
        }.build()
        val store = store(httpClient = http, baseUrl = "http://fixture.invalid")
        store.openSession("one")
        awaitUntil("initial history") { store.history is UiState.Ready }
        store.send("hello")
        awaitUntil("truncated stream") { store.turn is TurnState.Truncated }
        saved.set(true)
        awaitUntil("automatic recovery") { (store.history as? UiState.Ready)?.value?.lastOrNull()?.content == "Saved after disconnect" }
        assertEquals(1, submissions.get())
        store.close()
    }

    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        MockHermesLauncher.ensureRunning()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun tearDown() = scope.cancel()

    private fun store(
        httpClient: OkHttpClient = HermesClient.defaultHttpClient(),
        baseUrl: String = MockHermesLauncher.baseUrl,
        cache: SessionSnapshots? = null,
    ): HermesStore {
        val client = HermesClient(baseUrl, { MockHermesLauncher.key }, httpClient = httpClient)
        return HermesStore(scope, client, sessionSource = "api_server", cache = cache)
    }

    /** In-memory stand-in; SessionCache itself needs a real AndroidKeyStore. */
    private class FakeSnapshots(var snapshot: SessionSnapshots.Snapshot? = null) : SessionSnapshots {
        var writes = 0
        var untitled: Set<String> = emptySet()
        val pendingTitles = LinkedHashMap<String, String>()
        override fun read() = snapshot
        override fun write(sessions: List<org.praxisog.hermes.model.Session>, atEpochSeconds: Double) {
            writes++
            snapshot = SessionSnapshots.Snapshot(sessions, atEpochSeconds)
        }
        override fun clear() { snapshot = null }
        override fun readUntitled() = untitled
        override fun writeUntitled(ids: Set<String>) { untitled = ids }
        override fun readPendingTitles() = pendingTitles.toMap()
        override fun writePendingTitle(id: String, title: String) { pendingTitles[id] = title }
        override fun clearPendingTitle(id: String) { pendingTitles.remove(id) }
    }

    private fun faulting(fault: String): OkHttpClient =
        HermesClient.defaultHttpClient().newBuilder()
            .addInterceptor(Interceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-Mock-Fail", fault).build())
            })
            .build()

    /** Polls rather than pulling in a coroutine-test dependency for a handful of waits. */
    private fun awaitUntil(what: String, timeoutMs: Long = 20_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test fun loadsSessions() {
        val store = store()
        store.loadSessions()
        awaitUntil("sessions") { store.sessions is UiState.Ready }
        val sessions = (store.sessions as UiState.Ready).value
        assertEquals(1, sessions.size)
        assertEquals(MockHermesLauncher.FIXTURE_SESSION_ID, sessions.first().id)
    }

    @Test fun aSuccessfulSyncWritesTheCacheAndClearsOfflineState() {
        val cache = FakeSnapshots()
        val store = store(cache = cache)
        store.loadSessions()
        awaitUntil("sessions") { store.sessions is UiState.Ready }
        assertEquals(1, cache.writes)
        assertEquals(null, store.offline)
    }

    @Test fun aFailedSyncFallsBackToTheCacheAndSaysSo() {
        val cached = listOf(UiFixtures.sessions.first())
        val cache = FakeSnapshots(SessionSnapshots.Snapshot(cached, 1_700_000_000.0))
        // Port 1 is reserved; nothing listens there, so the fetch fails at the transport.
        val store = store(baseUrl = "http://127.0.0.1:1", cache = cache)
        store.loadSessions()
        awaitUntil("cached sessions") { store.offline != null }

        assertEquals(cached, (store.sessions as UiState.Ready).value)
        val offline = store.offline!!
        assertEquals(1_700_000_000.0, offline.cachedAtEpochSeconds, 0.1)
        // Names which layer failed, not just "offline".
        assertTrue(offline.error is org.praxisog.hermes.model.HermesError.Unreachable)
    }

    @Test fun aFailedSyncWithNoCacheFailsHonestlyRatherThanShowingAnEmptyList() {
        // An empty list would read as "you have no sessions", which is a different claim.
        val store = store(baseUrl = "http://127.0.0.1:1", cache = FakeSnapshots())
        store.loadSessions()
        awaitUntil("failure") { store.sessions is UiState.Failed }
        assertEquals(null, store.offline)
    }

    @Test fun anEmptyCacheIsTreatedAsNoCache() {
        val cache = FakeSnapshots(SessionSnapshots.Snapshot(emptyList(), 1_700_000_000.0))
        val store = store(baseUrl = "http://127.0.0.1:1", cache = cache)
        store.loadSessions()
        awaitUntil("failure") { store.sessions is UiState.Failed }
        assertEquals("an empty cache must not present as offline data", null, store.offline)
    }

    @Test fun recoveringFromOfflineClearsTheBanner() {
        val cache = FakeSnapshots(SessionSnapshots.Snapshot(listOf(UiFixtures.sessions.first()), 1.0))
        val store = store(cache = cache)
        store.loadSessions()
        awaitUntil("live sessions") { store.sessions is UiState.Ready }
        assertEquals("a live sync must clear the stale banner", null, store.offline)
    }

    @Test fun openingASessionFetchesHistoryRatherThanCaching() {
        val store = store()
        store.openSession(MockHermesLauncher.FIXTURE_SESSION_ID)
        awaitUntil("history") { store.history is UiState.Ready }
        assertEquals(4, (store.history as UiState.Ready).value.size)
    }

    @Test fun streamingAccumulatesIncrementallyAndSettlesFinished() {
        // Against the delayed instance: with no delay the whole stream lands in microseconds
        // and this would be a test of thread scheduling, not of incremental accumulation.
        MockHermesLauncher.ensureSlowRunning()
        val store = store(baseUrl = MockHermesLauncher.slowBaseUrl)
        store.openSession(MockHermesLauncher.FIXTURE_SESSION_ID)
        awaitUntil("history") { store.history is UiState.Ready }

        // Record every distinct streamed prefix to prove text arrives in pieces rather than
        // appearing whole at the end.
        val prefixes = linkedSetOf<String>()
        val watcher = Thread {
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                (store.turn as? TurnState.Streaming)?.let { prefixes += it.text }
                Thread.sleep(2)
            }
        }
        watcher.isDaemon = true
        watcher.start()

        store.send("hello")
        awaitUntil("turn to finish") { store.turn is TurnState.Finished }

        val finished = store.turn as TurnState.Finished
        assertTrue("expected streamed text", finished.text.isNotEmpty())
        val growing = prefixes.filter { it.isNotEmpty() }
        assertTrue(
            "expected multiple growing prefixes, saw ${growing.size}: $growing",
            growing.size >= 2,
        )
        assertTrue("prefixes should grow", growing.zipWithNext().all { (a, b) -> b.length >= a.length })
        assertEquals("final text is the full accumulation", finished.text, growing.last())
    }

    @Test fun droppedStreamKeepsPartialTextAndLeavesHistoryReadable() {
        val store = store(httpClient = faulting("stream-abort"))
        store.openSession(MockHermesLauncher.FIXTURE_SESSION_ID)
        awaitUntil("history") { store.history is UiState.Ready }

        store.send("hello")
        awaitUntil("truncation") { store.turn is TurnState.Truncated }

        val truncated = store.turn as TurnState.Truncated
        // The mock drops after three events, one of which is a delta, so there is real
        // partial text — it must survive, not be discarded with the error.
        assertTrue("partial text should be kept", truncated.text.isNotEmpty())
        assertTrue(
            "should read as a dropped connection, not a generic failure",
            truncated.error.toUserMessage().contains("may still be running"),
        )
        // The session must stay usable after a drop.
        awaitUntil("history refetched after the drop") {
            (store.history as? UiState.Ready)?.value?.isNotEmpty() == true
        }
    }

    // NOT ASSERTED HERE, deliberately: that history is refetched after a *truncated* stream.
    //
    // The behaviour is implemented -- HermesStore.send calls refreshHistory() on its terminal
    // path, which runs for every outcome rather than only on run.completed -- but this test
    // could not observe it. `history` is Compose state written from a nested coroutine, and
    // reading it from the test thread never saw the new value even with a 60s window, while
    // `turn` written on the same path was seen reliably. That points at snapshot visibility
    // for cross-thread reads with no Compose runtime driving the global snapshot, not at the
    // store. Rather than assert something the harness cannot see, or add production state
    // purely to observe it, this is left unproven and written down. Proving it properly wants
    // either a Compose-aware test rule or an instrumentation test.
    //
    // What IS proven: the same refetch on a clean completion (openingASessionFetchesHistory...
    // plus streamingAccumulates... leaving history Ready), and that a drop keeps its partial
    // text and a scoped error (droppedStreamKeepsPartialTextAndLeavesHistoryReadable).

    @Test fun sendIsIgnoredWhileATurnIsAlreadyStreaming() {
        val store = store()
        store.openSession(MockHermesLauncher.FIXTURE_SESSION_ID)
        awaitUntil("history") { store.history is UiState.Ready }
        store.send("first")
        store.send("second")
        awaitUntil("turn to finish") { store.turn is TurnState.Finished }
        // No crash, no interleaving: the second send is dropped while one is in flight.
        assertTrue(store.turn is TurnState.Finished)
    }

    @Test fun newSessionCreatesAndOpensIt() {
        val store = store()
        store.loadSessions()
        awaitUntil("sessions") { store.sessions is UiState.Ready }
        store.newSession()
        awaitUntil("a session to be opened") { store.openSessionId != null }
        awaitUntil("create to settle") { !store.creatingSession }
        assertEquals(null, store.createError)
        // The fixture's created session id, opened straight away.
        assertTrue(store.openSessionId!!.startsWith("api_"))
    }

    @Test fun aFailedCreateSurfacesAndDoesNotOpenAnything() {
        val store = store(httpClient = faulting("auth"))
        store.newSession()
        awaitUntil("create to fail") { store.createError != null }
        assertEquals(null, store.openSessionId)
        assertTrue(!store.creatingSession)
    }

    @Test fun askHermesCreatesASessionAndPrefillsTheComposerWithoutSending() {
        val store = store()
        store.setQuery("what is the tailnet IP")
        store.askHermes("what is the tailnet IP")
        awaitUntil("a session to be opened") { store.openSessionId != null }
        assertEquals("what is the tailnet IP", store.pendingPrompt)
        // Crucially the turn is NOT started: a search box is a low-confidence surface, and
        // firing inference (possibly a shell command) off one keystroke too many is hostile.
        assertEquals(TurnState.Idle, store.turn)
        assertEquals("the query is consumed", "", store.searchQuery)
    }

    @Test fun localProfileRequestCreatesAndSendsOnceWithoutReplacingAnExistingDraft() {
        val submissions = AtomicInteger()
        val http = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            if (chain.request().url.encodedPath.endsWith("/chat/stream")) submissions.incrementAndGet()
            chain.proceed(chain.request())
        }.build()
        val store = store(httpClient = http)
        val original = MockHermesLauncher.FIXTURE_SESSION_ID
        store.openSession(original); awaitUntil("original history") { store.history is UiState.Ready }
        store.drafts.set(original, androidx.compose.ui.text.input.TextFieldValue("Keep this unfinished draft."))
        store.requestLocalSystemPrompt(); store.requestLocalSystemPrompt()
        awaitUntil("profile reply") { store.turn is TurnState.Finished }
        assertTrue(store.openSessionId != original)
        assertEquals(1, submissions.get())
        assertEquals("Keep this unfinished draft.", store.drafts[original].text)
        store.refreshHistory(); awaitUntil("refreshed profile history") { store.history is UiState.Ready }
        assertEquals(1, submissions.get())
        store.close()
    }

    @Test fun editingTheProfileRequestWhileHistoryLoadsPreventsAutomaticSubmission() {
        val waiting = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val firstHistory = AtomicBoolean(true)
        val submissions = AtomicInteger()
        val http = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            if (chain.request().url.encodedPath.endsWith("/messages") && firstHistory.compareAndSet(true, false)) {
                waiting.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
            }
            if (chain.request().url.encodedPath.endsWith("/chat/stream")) submissions.incrementAndGet()
            chain.proceed(chain.request())
        }.build()
        val store = store(httpClient = http)
        try {
            store.requestLocalSystemPrompt()
            assertTrue(waiting.await(10, java.util.concurrent.TimeUnit.SECONDS))
            store.drafts.set(store.openSessionId!!, androidx.compose.ui.text.input.TextFieldValue("I want to edit this first."))
            release.countDown()
            awaitUntil("loaded history") { store.history is UiState.Ready }
            assertEquals(TurnState.Idle, store.turn)
            assertEquals(0, submissions.get())
            assertEquals("I want to edit this first.", store.drafts[store.openSessionId!!].text)
        } finally { release.countDown(); store.close() }
    }

    @Test fun sendingClearsThePrefillSoItDoesNotReappear() {
        val store = store()
        store.askHermes("prompt from search")
        awaitUntil("session") { store.openSessionId != null }
        awaitUntil("history") { store.history is UiState.Ready }
        // A newly created session has no messages, which the mock now reflects.
        assertEquals(0, (store.history as UiState.Ready).value.size)

        store.send("prompt from search")
        // send() clears the prefill synchronously, before the turn is dispatched, so a
        // recomposition mid-stream cannot re-seed the composer with text already sent.
        assertEquals("", store.pendingPrompt)
        awaitUntil("turn to finish") { store.turn is TurnState.Finished }
    }

    @Test fun completionPublishesCanonicalHistoryAndRefreshesSessionMetadata() {
        val sent = AtomicBoolean(false)
        val lists = AtomicInteger()
        val client = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val body = when {
                path.endsWith("/chat/stream") -> { sent.set(true); null }
                path.endsWith("/messages") -> if (sent.get())
                    """{"data":[{"id":3,"role":"user","content":"hello"},{"id":4,"role":"assistant","content":"Saved answer"}]}"""
                    else """{"data":[]}"""
                path == "/api/sessions" -> {
                    lists.incrementAndGet()
                    if (sent.get()) """{"data":[{"id":"fixture-id-1","title":"Updated title","message_count":2,"tool_call_count":1}]}"""
                    else """{"data":[{"id":"fixture-id-1","message_count":0}]}"""
                }
                else -> null
            }
            if (body == null) chain.proceed(request) else Response.Builder().request(request)
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val store = store(httpClient = client)
        store.loadSessions()
        store.openSession(MockHermesLauncher.FIXTURE_SESSION_ID)
        awaitUntil("initial state") { store.sessions is UiState.Ready && store.history is UiState.Ready }
        store.send("hello")
        awaitUntil("canonical answer and metadata") {
            store.turn is TurnState.Idle && store.openSession?.messageCount == 2 &&
                (store.history as? UiState.Ready)?.value?.lastOrNull()?.content == "Saved answer"
        }
        assertEquals("Updated title", store.openSession?.title)
        assertEquals(1, store.openSession?.toolCallCount)
        assertTrue("completion must reload the session list", lists.get() >= 2)
    }

    @Test fun blankSendDoesNothing() {
        val store = store()
        store.openSession(MockHermesLauncher.FIXTURE_SESSION_ID)
        awaitUntil("history") { store.history is UiState.Ready }
        store.send("   ")
        assertEquals(TurnState.Idle, store.turn)
    }
}
