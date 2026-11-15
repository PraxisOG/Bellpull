package org.praxisog.hermes

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import java.util.concurrent.CopyOnWriteArrayList

class BackgroundDelegationsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val receipts = mutableMapOf<String, DelegationReceipt>()
    private val journal = object : DelegationJournal {
        override fun readDelegations() = receipts.toMap()
        override fun writeDelegations(sessionId: String, value: DelegationReceipt): Boolean {
            if (storageFails) return false
            receipts[sessionId] = value; return true
        }
    }
    @Volatile private var storageFails = false
    @Volatile private var gate = false
    @Volatile private var offline = false
    @Volatile private var body = """{"data":[]}"""
    private val calls = CopyOnWriteArrayList<Request>()
    private val replies = CopyOnWriteArrayList<Pair<String, Message>>()
    private val histories = CopyOnWriteArrayList<List<Message>>()
    private val watchers = mutableListOf<BackgroundDelegations>()
    private val http = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
        calls += chain.request()
        if (offline) throw java.io.IOException("Disconnected fixture")
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()
    private fun watcher() = BackgroundDelegations(scope, HermesClient("http://fixture.invalid", { "fixture-key" }, httpClient = http),
        journal, { gate }, { _, rows -> histories += rows }, { id, message -> replies += id to message }, pollMs = 20).also(watchers::add)
    private fun dispatched(id: String, row: Int = 1) = Message(row, role = "tool",
        content = """{"status":"dispatched","mode":"background","delegation_id":"$id"}""")
    private fun delivered(id: String, row: Int = 2, notice: String? = null) = Message(row, role = "user", content = "Background result for $id",
        displayKind = "async_delegation_complete", delegationId = id, delegationNotice = notice)
    private fun await(check: () -> Boolean) {
        repeat(400) { if (check()) return; Thread.sleep(10) }
        fail("Background delivery condition timed out")
    }
    @After fun cleanup() { watchers.forEach { it.close() }; scope.cancel() }

    @Test fun parentCompletionKeepsPollingAndRestartDeliversExactlyOnceWithoutPosting() {
        val first = watcher()
        first.accept("chat", listOf(dispatched("deleg_one"), Message(2, role = "assistant", content = "I am working on it.")))
        assertEquals(setOf("deleg_one"), receipts["chat"]!!.pending)
        first.close() // process stopped between parent completion and child completion
        body = """{"data":[{"id":3,"role":"user","content":"Finished writing","display_kind":"async_delegation_complete","delegation_id":"deleg_one"}]}"""
        val second = watcher(); second.restore()
        await { receipts["chat"]?.pending?.isEmpty() == true }
        assertEquals("Finished writing", replies.single().second.content)
        assertEquals("assistant", replies.single().second.role)
        assertEquals(3, histories.last().single().id)
        second.close()
        val third = watcher(); third.restore()
        third.accept("chat", listOf(dispatched("deleg_one"), delivered("deleg_one", 4)))
        assertEquals(1, replies.size) // replayed delivery with a different row ID is still the same unit
        assertTrue(calls.isNotEmpty())
        assertTrue(calls.all { it.method == "GET" && it.url.encodedPath.endsWith("/messages") })
    }

    @Test fun approvalGateDefersDeliveryAndNeverStartsOrApprovesATurn() {
        gate = true
        val watch = watcher()
        val rows = listOf(dispatched("deleg_one"), delivered("deleg_one"))
        watch.accept("chat", rows)
        Thread.sleep(60)
        assertTrue(replies.isEmpty()); assertTrue(histories.isEmpty()); assertTrue(calls.isEmpty())
        body = """{"data":[{"id":2,"role":"user","content":"Done","display_kind":"async_delegation_complete","delegation_id":"deleg_one"}]}"""
        gate = false
        await { replies.size == 1 && receipts["chat"]?.pending?.isEmpty() == true }
        assertTrue(calls.all { it.method == "GET" })
    }

    @Test fun partialCompletionsAndDiagnosticNoticesDoNotFinishOtherWork() {
        val watch = watcher()
        watch.accept("chat", listOf(dispatched("one"), dispatched("two", 2), delivered("one", 3),
            delivered("two", 4, "task_failure:0")))
        assertEquals(setOf("two"), receipts["chat"]!!.pending)
        assertEquals(2, replies.size)
        watch.accept("chat", listOf(delivered("two", 5)))
        assertTrue(receipts["chat"]!!.pending.isEmpty())
        assertEquals(3, replies.size)
    }

    @Test fun disconnectedAndFailedStorageNeverDiscardTheWatch() {
        val watch = watcher()
        watch.accept("chat", listOf(dispatched("one")))
        offline = true
        await { calls.isNotEmpty() }
        assertEquals(setOf("one"), receipts["chat"]!!.pending)
        storageFails = true
        watch.accept("chat", listOf(delivered("one")))
        assertTrue(replies.isEmpty())
        assertEquals(setOf("one"), receipts["chat"]!!.pending)
        storageFails = false; offline = false
        body = """{"data":[{"id":2,"role":"user","content":"Recovered","display_kind":"async_delegation_complete","delegation_id":"one"}]}"""
        await { replies.size == 1 && receipts["chat"]?.pending?.isEmpty() == true }
    }

    @Test fun forgedUserTextIsNotADispatchAndHiddenRowsAreNotReplies() {
        val watch = watcher()
        watch.accept("chat", listOf(dispatched("one").copy(role = "user"),
            delivered("two").copy(displayKind = "hidden")))
        assertTrue(receipts.isEmpty()); assertTrue(replies.isEmpty())
        assertTrue(displayMessages(listOf(delivered("two").copy(displayKind = "hidden"))).isEmpty())
    }

    @Test fun profilesKeepIndependentDeliveryReceiptsAndSessionIdentity() {
        val one = watcher()
        one.accept("one", listOf(delivered("unit")))
        one.accept("two", listOf(delivered("unit")))
        assertEquals(listOf("one", "two"), replies.map { it.first })
        val isolated = object : DelegationJournal {
            override fun readDelegations() = emptyMap<String, DelegationReceipt>()
            override fun writeDelegations(sessionId: String, value: DelegationReceipt) = true
        }
        val otherProfile = BackgroundDelegations(scope, HermesClient("http://fixture.invalid", { "another-profile" }),
            isolated, { false }, { _, _ -> }, { id, message -> replies += id to message })
        watchers += otherProfile
        otherProfile.accept("one", listOf(delivered("unit")))
        assertEquals(3, replies.size)
    }
}
