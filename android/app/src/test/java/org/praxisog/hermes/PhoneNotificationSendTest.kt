package org.praxisog.hermes

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import java.util.concurrent.CopyOnWriteArrayList

/** Proves both Hermes paths receive the frozen excerpt without a new endpoint/plugin. */
class PhoneNotificationSendTest {
    @Test fun legacyAndDurableInputsIncludeOnlyTheContextSelectedAtSend() {
        for (durable in listOf(false, true)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val sent = CopyOnWriteArrayList<JsonObject>()
            val saved = CopyOnWriteArrayList<RunSubmission>()
            val http = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                val body = when {
                    request.method == "POST" && (path.endsWith("/chat/stream") || path == "/v1/runs") -> {
                        val buffer = Buffer(); request.body!!.writeTo(buffer); sent.add(Json.parseToJsonElement(buffer.readUtf8()).jsonObject)
                        if (durable) """{"run_id":"fixture-run","session_id":"chat","status":"running"}"""
                        else "event: run.started\ndata: {\"run_id\":\"fixture-run\"}\n\nevent: run.completed\ndata: {}\n\n"
                    }
                    path.endsWith("/messages") -> """{"session_id":"chat","data":[]}"""
                    path.endsWith("/events") -> "event: run.completed\ndata: {\"run_id\":\"fixture-run\"}\n\n"
                    path.startsWith("/v1/runs/") -> """{"run_id":"fixture-run","session_id":"chat","status":"completed"}"""
                    else -> """{"data":[]}"""
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(body.toResponseBody((if (path.endsWith("/stream") || path.endsWith("/events")) "text/event-stream" else "application/json").toMediaType())).build()
            }.build()
            val client = HermesClient("http://fixture.invalid", { "fixture" }, httpClient = http)
            val journal = object : RunJournal {
                override fun readRuns() = emptyList<RunSubmission>()
                override fun writeRun(value: RunSubmission): Boolean { saved.add(value); return true }
            }
            val store = HermesStore(scope, client, durableRetention = if (durable) 86400 else null, journal = if (durable) journal else null)
            val row = PhoneNotificationRecord("id", "key", "source.app", "Messenger", "Alice", "At noon", 100, 100, 100)
            val excerpt = notificationContextText(listOf(row), true)!!
            val queries = CopyOnWriteArrayList<String>()
            store.phoneNotificationContext = { query -> queries.add(query); excerpt }
            try {
                store.openSession("chat")
                await { store.history is UiState.Ready }
                store.updateDraft(androidx.compose.ui.text.input.TextFieldValue("What did Alice say?"))
                assertTrue(store.send("What did Alice say?"))
                store.phoneNotificationContext = { error("A pending submission must not re-read mutable notification history") }
                await { sent.isNotEmpty() }
                val input = sent.single()[if (durable) "input" else "message"]!!.jsonPrimitive.content
                assertEquals("What did Alice say?" + excerpt, input)
                assertEquals(listOf("What did Alice say?"), queries)
                if (durable) assertEquals(input, saved.first().text)
                assertEquals("What did Alice say?", voicePromptTitle(input))
            } finally { store.close(); scope.cancel(); client.close() }
        }
    }
    @Test fun contextDoesNotChangeVoiceUserWordsOrTitle() {
        val row = PhoneNotificationRecord("id", "key", "source.app", "Messenger", "Alice", "At noon", 100, 100, 100)
        val text = VOICE_PRESET + "\n\nWhat did Alice say?" + notificationContextText(listOf(row), true)
        assertEquals("What did Alice say?", voicePromptUserText(text))
        assertEquals("What did Alice say?", voicePromptTitle(text))
    }
    private fun await(check: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000
        while (!check()) { check(System.nanoTime() < end) { "Fixture timed out" }; Thread.sleep(10) }
    }
}
