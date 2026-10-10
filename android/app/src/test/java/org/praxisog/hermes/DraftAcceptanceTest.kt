package org.praxisog.hermes

import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.core.HermesClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** An acknowledged, tool-first stream must clear the draft before it produces any prose. */
class DraftAcceptanceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requestReached = CountDownLatch(1)
    private val acknowledge = CountDownLatch(1)
    private val finish = CountDownLatch(1)
    @Volatile private var saved = false
    @After fun cleanup() { acknowledge.countDown(); finish.countDown(); scope.cancel() }

    private fun open(fail: Boolean = false): HermesStore {
        val http = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            val streaming = path.endsWith("/chat/stream")
            val body = if (streaming && !fail) object : ResponseBody() {
                override fun contentType() = "text/event-stream".toMediaType()
                override fun contentLength() = -1L
                private val stream = object : Source {
                    private var step = 0
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        val text = when (step++) {
                            0 -> {
                                requestReached.countDown()
                                check(acknowledge.await(10, TimeUnit.SECONDS))
                                "event: run.started\ndata: {\"run_id\":\"run-test\",\"seq\":1}\n\n" +
                                    "event: tool.started\ndata: {\"tool_name\":\"web_search\",\"seq\":2}\n\n"
                            }
                            1 -> {
                                check(finish.await(10, TimeUnit.SECONDS))
                                saved = true
                                "event: assistant.delta\ndata: {\"delta\":\"Done\",\"seq\":3}\n\n" +
                                    "event: run.completed\ndata: {\"completed\":true,\"seq\":4}\n\n"
                            }
                            else -> return -1
                        }
                        sink.writeUtf8(text)
                        return text.toByteArray().size.toLong()
                    }
                    override fun timeout() = Timeout.NONE
                    override fun close() = Unit
                }.buffer()
                override fun source() = stream
            } else {
                val json = when {
                    streaming -> """{"error":{"code":"unauthorized","message":"Test rejection"}}"""
                    path.endsWith("/messages") && saved && path.contains("/source/") ->
                        """{"data":[{"id":1,"role":"user","content":"Search this"},{"id":2,"role":"assistant","content":"Done"}]}"""
                    path.endsWith("/messages") -> """{"data":[]}"""
                    else -> """{"data":[{"id":"source"},{"id":"other"}]}"""
                }
                json.toResponseBody("application/json".toMediaType())
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (streaming && fail) 401 else 200).message("test").body(body).build()
        }.build()
        return HermesStore(scope, HermesClient("http://test.invalid", { "test-only" }, httpClient = http)).also {
            it.openSession("source")
            await { it.history is UiState.Ready }
            it.updateDraft(TextFieldValue("Search this"))
        }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("Timed out waiting for draft acceptance", condition())
    }
    @Test fun toolFirstAcknowledgementClearsDraftAndShowsPromptBeforeAnyProse() {
        val store = open()
        store.send(store.draft.text)
        assertTrue(requestReached.await(10, TimeUnit.SECONDS))
        assertEquals("Search this", store.draft.text)
        acknowledge.countDown()
        await { store.draft.text.isEmpty() && store.pendingUserText != null && (store.turn as? TurnState.Streaming)?.runningToolName == "web_search" }
        assertEquals("", (store.turn as TurnState.Streaming).text)
        assertEquals("Search this", store.pendingUserText)
        finish.countDown()
        await { store.turn is TurnState.Idle && store.pendingUserText == null }
        assertEquals(1, (store.history as UiState.Ready).value.count { it.role == "user" })
    }
    @Test fun newerTypingSurvivesAnOlderAcknowledgement() {
        val store = open()
        store.send(store.draft.text)
        assertTrue(requestReached.await(10, TimeUnit.SECONDS))
        store.updateDraft(TextFieldValue("My next question"))
        acknowledge.countDown()
        await { store.pendingUserText != null }
        assertEquals("My next question", store.draft.text)
    }
    @Test fun acknowledgementAfterSwitchingChatsClearsOnlyTheSubmittedChat() {
        val store = open()
        store.send(store.draft.text)
        assertTrue(requestReached.await(10, TimeUnit.SECONDS))
        store.openSession("other")
        store.updateDraft(TextFieldValue("Other draft"))
        acknowledge.countDown()
        await { store.drafts["source"].text.isEmpty() }
        assertEquals("Other draft", store.draft.text)
        assertNull(store.pendingUserText)
    }
    @Test fun rejectedSendRetainsDraft() {
        val store = open(fail = true)
        store.send(store.draft.text)
        await { store.turn is TurnState.Failed }
        assertEquals("Search this", store.draft.text)
        assertNull(store.pendingUserText)
        assertTrue(store.readState.unreadIds.isEmpty())
        assertEquals(0L, store.completionRevision)
    }

    @Test fun completedReplyInAnotherChatIsUnreadAndRequestsListReveal() {
        val store = open()
        store.send(store.draft.text)
        acknowledge.countDown()
        await { (store.turn as? TurnState.Streaming)?.runningToolName == "web_search" }
        store.openSession("other")
        store.updateDraft(TextFieldValue("Keep the other draft"))
        finish.countDown()
        await { store.completionRevision == 1L }
        assertEquals(setOf("source"), store.readState.unreadIds)
        assertEquals("other", store.openSessionId)
        assertEquals("Keep the other draft", store.draft.text)
        store.readState.readThrough("source", 1)
        assertEquals(setOf("source"), store.readState.unreadIds)
        store.readState.readThrough("source", 2)
        assertTrue(store.readState.unreadIds.isEmpty())
    }
}
