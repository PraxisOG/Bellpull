package org.praxisog.hermes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.praxisog.hermes.core.HermesClient

/**
 * First-delivered-voice-prompt titling: eligibility and derived titles survive process
 * death, a failed PATCH recovers on the next sync, and renamed chats are never re-titled.
 * The server keeps no Bellpull-voice knowledge (stock Hermes titling sees only the wrapped
 * prose), so this client seam is the recognized-wrapper boundary.
 */
class SessionTitleRepairTest {
    private lateinit var scope: CoroutineScope

    @Before fun setUp() { scope = CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    @After fun tearDown() = scope.cancel()

    @Test fun pendingVoiceTitleUsesConditionalEndpointEvenAfterAutomaticTitleAppears() {
        val cache = TestSnapshots()
        cache.pendingTitles["voice-chat"] = "Inspect turbine blades"
        val requests = CopyOnWriteArrayList<String>()
        val http = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.method + " " + request.url.encodedPath
            val body = if (request.url.encodedPath.endsWith("/voice-title")) """{"updated":true}"""
                else """{"data":[{"id":"voice-chat","title":"Reply in spoken paragraphs"}]}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = HermesClient("https://fixture.invalid", { "fixture" }, httpClient = http)
        val store = HermesStore(scope, client, cache = cache, supportsVoiceTitles = true)
        try {
            store.loadSessions()
            awaitUntil("conditional title recovery") { !cache.pendingTitles.containsKey("voice-chat") }
            assertEquals(1, requests.count { it == "POST /api/bellpull/sessions/voice-chat/voice-title" })
            assertTrue(requests.none { it.startsWith("PATCH") })
        } finally { store.close(); client.close() }
    }

    /** Server-shaped fixture: PATCH honored or refused per switch; titles are the truth. */
    private class TitleFixture {
        val titles = ConcurrentHashMap<String, String>()
        val patches = CopyOnWriteArrayList<Pair<String, String>>()
        var acceptPatches = true
        fun client(): HermesClient {
            val http = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                val (code, body) = when {
                    path == "/api/sessions" && request.method == "POST" -> {
                        titles.putIfAbsent("voice-chat", "")
                        201 to """{"session":{"id":"voice-chat","source":"api_server","title":null,"message_count":0}}"""
                    }
                    path == "/api/sessions" -> {
                        val rows = titles.entries.joinToString(",") { (id, title) ->
                            """{"id":"$id","source":"api_server","title":${if (title.isEmpty()) "null" else "\"$title\""},"message_count":0}"""
                        }
                        200 to """{"data":[$rows],"has_more":false}"""
                    }
                    path == "/api/sessions/voice-chat/messages" -> 200 to """{"data":[]}"""
                    path == "/api/sessions/voice-chat" && request.method == "PATCH" -> {
                        val title = Json.parseToJsonElement(okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                            .let { (it as kotlinx.serialization.json.JsonObject)["title"]!!.toString().trim('"') }
                        patches += "voice-chat" to title
                        if (acceptPatches) { titles["voice-chat"] = title; 200 to """{"id":"voice-chat","title":"$title"}""" }
                        else 500 to """{"error":{"code":"boom","message":"rename unavailable"}}"""
                    }
                    else -> 404 to """{"error":{"code":"missing","message":"unmapped $path"}}"""
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            return HermesClient("https://fixture.invalid", { "fixture" }, httpClient = http)
        }
    }

    private fun awaitUntil(what: String, timeoutMs: Long = 20_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $what")
    }

    /** Fire the durable seam exactly as DurableRuns.onPromptDelivered does. */
    private fun delivered(store: HermesStore, text: String) {
        val fire = store.runs?.onPromptDelivered
        assertEquals("durable runs must be wired", true, fire != null)
        kotlinx.coroutines.runBlocking { fire!!.invoke("voice-chat", text) }
    }

    private val wrappedWords = "$VOICE_PRESET\n\nWhere should I inspect the turbine blades first"

    @Test fun restartBetweenChatCreationAndFirstVoiceTurnStillTitlesTheChat() {
        val fixture = TitleFixture()
        val cache = TestSnapshots()
        // The phone creates the chat before the restart; eligibility must persist.
        val before = HermesStore(scope, fixture.client(), cache = cache, durableRetention = 86400, journal = NoopJournal)
        before.newSession()
        awaitUntil("chat created and opened") { before.openSessionId == "voice-chat" && before.history is UiState.Ready }
        assertEquals(setOf("voice-chat"), cache.untitled)
        before.close()
        // Process death: a fresh store over the same cache keeps the eligibility.
        fixture.acceptPatches = true
        val restarted = HermesStore(scope, fixture.client(), cache = cache, durableRetention = 86400, journal = NoopJournal)
        restarted.loadSessions()
        awaitUntil("sessions") { restarted.sessions is UiState.Ready }
        restarted.openSession("voice-chat")
        awaitUntil("history") { restarted.history is UiState.Ready }
        delivered(restarted, wrappedWords)
        awaitUntil("title applied") { fixture.titles["voice-chat"]?.isNotBlank() == true }
        assertEquals("Where should I inspect the turbine blades first", fixture.titles["voice-chat"])
        awaitUntil("receipt cleared") { !cache.pendingTitles.containsKey("voice-chat") }
        assertEquals(listOf("voice-chat" to "Where should I inspect the turbine blades first"), fixture.patches)
        restarted.close()
    }

    @Test fun failedTitleRequestKeepsItsReceiptAndRecoversOnTheNextSync() {
        val fixture = TitleFixture()
        fixture.acceptPatches = false
        val cache = TestSnapshots()
        cache.untitled = setOf("voice-chat")
        val store = HermesStore(scope, fixture.client(), cache = cache, durableRetention = 86400, journal = NoopJournal)
        store.loadSessions()
        awaitUntil("sessions") { store.sessions is UiState.Ready }
        store.openSession("voice-chat")
        awaitUntil("history") { store.history is UiState.Ready }
        delivered(store, wrappedWords)
        awaitUntil("failed PATCH keeps the receipt") { cache.pendingTitles.containsKey("voice-chat") && fixture.patches.isNotEmpty() }
        // A process restart must not lose the pending title.
        store.close()
        fixture.acceptPatches = true
        val restarted = HermesStore(scope, fixture.client(), cache = cache, durableRetention = 86400, journal = NoopJournal)
        restarted.loadSessions()
        awaitUntil("title re-applied after recovery") { fixture.titles["voice-chat"]?.startsWith("Where should") == true }
        awaitUntil("receipt cleared") { !cache.pendingTitles.containsKey("voice-chat") }
        assertTrue("at least one failed and one successful PATCH", fixture.patches.size >= 2)
        restarted.close()
    }

    @Test fun aChatRenamedElsewhereIsNeverRetitled() {
        val fixture = TitleFixture()
        fixture.acceptPatches = false
        val cache = TestSnapshots()
        cache.untitled = setOf("voice-chat")
        val store = HermesStore(scope, fixture.client(), cache = cache, durableRetention = 86400, journal = NoopJournal)
        store.loadSessions()
        awaitUntil("sessions") { store.sessions is UiState.Ready }
        delivered(store, wrappedWords)
        awaitUntil("receipt pending") { cache.pendingTitles.containsKey("voice-chat") }
        // The user renames the chat on the server; the next sync must drop the receipt.
        fixture.titles["voice-chat"] = "Engine room notes"
        store.loadSessions()
        awaitUntil("receipt dropped") { !cache.pendingTitles.containsKey("voice-chat") }
        val attempts = fixture.patches.size
        store.loadSessions()
        Thread.sleep(250)
        assertEquals("no PATCH may follow a manual rename", attempts, fixture.patches.size)
        assertEquals("Engine room notes", fixture.titles["voice-chat"])
        store.close()
    }

    /** Durable seams only; the journal stays empty because titling fires from the seam directly. */
    private object NoopJournal : RunJournal {
        override fun readRuns() = emptyList<org.praxisog.hermes.model.RunSubmission>()
        override fun writeRun(value: org.praxisog.hermes.model.RunSubmission) = true
    }

    /** In-memory cache stand-in; SessionCache itself needs a real AndroidKeyStore. */
    private class TestSnapshots : SessionSnapshots {
        var snapshot: SessionSnapshots.Snapshot? = null
        var untitled: Set<String> = emptySet()
        val pendingTitles = LinkedHashMap<String, String>()
        override fun read() = snapshot
        override fun write(sessions: List<org.praxisog.hermes.model.Session>, atEpochSeconds: Double) {
            snapshot = SessionSnapshots.Snapshot(sessions, atEpochSeconds)
        }
        override fun clear() { snapshot = null }
        override fun readUntitled() = untitled
        override fun writeUntitled(ids: Set<String>) { untitled = ids }
        override fun readPendingTitles() = pendingTitles.toMap()
        override fun writePendingTitle(id: String, title: String) { pendingTitles[id] = title }
        override fun clearPendingTitle(id: String) { pendingTitles.remove(id) }
    }
}
