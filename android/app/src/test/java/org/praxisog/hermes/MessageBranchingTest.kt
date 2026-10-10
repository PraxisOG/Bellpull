package org.praxisog.hermes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.HermesResult
import org.praxisog.hermes.model.Message
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MessageBranchingTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val forks = AtomicInteger()
    private val started = CountDownLatch(1)
    private val release = CountDownLatch(1)
    @Volatile private var cutoff = 0
    @Volatile private var editCutoff = 0
    @After fun tearDown() { release.countDown(); scope.cancel() }

    private fun client(supported: Boolean = true, fail: Boolean = false, hold: Boolean = false): HermesClient {
        val http = HermesClient.defaultHttpClient().newBuilder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            var status = 200
            val body = when (request.url.encodedPath) {
                "/v1/capabilities" -> if (supported) """{"features":{"session_fork_at_message":true}}"""
                    else """{"features":{"session_fork":true}}"""
                "/api/sessions" -> """{"data":[{"id":"source","title":"Original"},{"id":"branch","title":"Branch"}]}"""
                "/api/sessions/source/messages" -> """{"data":[{"id":1,"role":"user","content":"First question"},{"id":2,"role":"assistant","content":"First answer"},{"id":3,"role":"user","content":"Later question"},{"id":4,"role":"assistant","content":"Later answer"}]}"""
                "/api/sessions/branch/messages" -> """{"data":[{"id":5,"role":"user","content":"First question"},{"id":6,"role":"assistant","content":"First answer"}]}"""
                "/api/sessions/source/fork-before-message" -> {
                    val buffer = Buffer(); request.body!!.writeTo(buffer)
                    val payload = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                    assertEquals(setOf("before_message_id"), payload.keys)
                    editCutoff = payload.getValue("before_message_id").jsonPrimitive.int
                    forks.incrementAndGet()
                    status = if (fail) 400 else 201
                    if (fail) """{"error":{"code":"invalid_branch_message","message":"Invalid edit"}}"""
                    else """{"session":{"id":"branch","message_count":2}}"""
                }
                "/api/sessions/source/fork-at-message" -> {
                    assertEquals("POST", request.method)
                    val buffer = Buffer(); request.body!!.writeTo(buffer)
                    val payload = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                    assertEquals(setOf("through_message_id"), payload.keys)
                    cutoff = payload.getValue("through_message_id").jsonPrimitive.int
                    forks.incrementAndGet(); started.countDown()
                    if (hold) assertTrue(release.await(10, TimeUnit.SECONDS))
                    status = if (fail) 400 else 201
                    if (fail) """{"error":{"message":"Choose a saved assistant reply.","code":"invalid_branch_message"}}"""
                    else """{"object":"hermes.session","session":{"id":"branch","parent_session_id":"source","message_count":2}}"""
                }
                else -> throw AssertionError("Unexpected request path ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("test")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build()
        return HermesClient("http://disposable.invalid", { "test-only" }, httpClient = http)
    }

    private fun open(client: HermesClient, supported: Boolean = true): HermesStore =
        HermesStore(scope, client, supportsMessageBranching = supported, supportsPromptEditing = supported).also {
            it.loadSessions(); it.openSession("source")
            await { it.history is UiState.Ready && it.sessions is UiState.Ready }
        }

    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < end) Thread.sleep(5)
        assertTrue("Timed out waiting for branch state", condition())
    }

    private val reply = Message(2, role = "assistant", content = "First answer")

    @Test fun editedPromptOpensInBranchDraftWithoutSubmittingOrChangingSourceDraft() {
        val store = open(client())
        store.updateDraft(androidx.compose.ui.text.input.TextFieldValue("Unsent original draft"))
        store.forkAndEdit(Message(3, role = "user", content = "Later question"), "Revised question")
        await { store.openSessionId == "branch" && store.history is UiState.Ready }
        assertEquals(3, editCutoff)
        assertEquals("Revised question", store.draft.text)
        assertEquals("Unsent original draft", store.drafts["source"].text)
        assertEquals(1, forks.get())
    }

    @Test fun failedEditedBranchKeepsOriginalDraftAndReportsError() {
        val store = open(client(fail = true))
        store.updateDraft(androidx.compose.ui.text.input.TextFieldValue("Unsent original draft"))
        store.forkAndEdit(Message(3, role = "user"), "Revised question")
        await { store.branchError != null }
        assertEquals("source", store.openSessionId)
        assertEquals("Unsent original draft", store.draft.text)
    }

    @Test fun branchesAtSelectedSavedMessageAndOpensCanonicalPrefix() {
        val store = open(client())
        store.branchMessage(reply)
        await { store.openSessionId == "branch" && store.history is UiState.Ready }
        assertEquals(2, cutoff)
        assertEquals(1, forks.get())
        assertEquals(listOf("First question", "First answer"), (store.history as UiState.Ready).value.map { it.content })
        assertEquals("Branch", store.openSession?.title)
        assertNull(store.branchError)
    }

    @Test fun wholeConversationForkFlagDoesNotAuthorizeSelectedReplyFork() {
        val client = client(supported = false)
        val caps = (client.capabilities() as HermesResult.Ok).value
        assertFalse(caps.sessionForkAtMessage)
        val store = open(client, caps.sessionForkAtMessage)
        store.branchMessage(reply)
        assertEquals(0, forks.get())
        assertEquals("source", store.openSessionId)
    }

    @Test fun explicitSelectedReplyCapabilityEnablesBranching() {
        assertTrue((client().capabilities() as HermesResult.Ok).value.sessionForkAtMessage)
    }

    @Test fun failureKeepsOriginalTranscriptAndDisplaysAnError() {
        val store = open(client(fail = true))
        val history = store.history
        store.branchMessage(reply)
        await { store.branchError != null }
        assertEquals("source", store.openSessionId)
        assertEquals(history, store.history)
        assertFalse(store.branchingSession)
        store.dismissBranchError()
        assertNull(store.branchError)
    }

    @Test fun duplicateTapAndNavigationDuringForkDoNotCreateTwiceOrReopenChat() {
        val store = open(client(hold = true))
        store.branchMessage(reply)
        assertTrue(started.await(10, TimeUnit.SECONDS))
        store.branchMessage(reply)
        store.closeSession()
        release.countDown()
        await { !store.branchingSession }
        assertEquals(1, forks.get())
        assertNull(store.openSessionId)
    }

    @Test fun unsavedMessagesAndUserMessagesCannotBeBranched() {
        val store = open(client())
        store.branchMessage(Message(999, role = "assistant", content = "Not saved"))
        store.branchMessage(Message(1, role = "user", content = "First question"))
        assertEquals(0, forks.get())
    }
}
