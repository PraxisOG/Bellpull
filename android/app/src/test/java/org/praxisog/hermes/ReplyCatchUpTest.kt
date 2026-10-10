package org.praxisog.hermes

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.Session

class ReplyCatchUpTest {
    @Test fun initialImportIsBaselineThenMissedReplyIsRecoveredOnce() = runBlocking {
        var reply = 10
        var fetches = 0
        var unavailable = false
        val records = mutableMapOf<String, ReplyCursor>()
        val cursors = object : ReplyCursorStore {
            override fun readCursor(id: String) = records[id]
            override fun writeCursor(id: String, value: ReplyCursor) { records[id] = value }
        }
        val http = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            fetches++
            if (unavailable) throw java.io.IOException("fixture disconnected")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"data":[{"id":$reply,"role":"assistant","content":"saved reply"}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = HermesClient("http://fixture.invalid", { "test-only" }, httpClient = http)
        val receipts = ConversationReadState()
        var discoveries = 0
        fun catcher() = ReplyCatchUp(this, client, cursors, receipts) { id, message -> discoveries++; receipts.completed(id, message.id) }
        val old = Session("chat", lastActive = 100.0, messageCount = 2)
        catcher().sync(listOf(old)).join()
        assertTrue(receipts.unreadIds.isEmpty())
        assertEquals(1, fetches)
        catcher().sync(listOf(old)).join()
        assertEquals(1, fetches)
        reply = 12
        unavailable = true
        val updated = old.copy(lastActive = 200.0, messageCount = 4)
        catcher().sync(listOf(updated)).join()
        assertEquals(10, records["chat"]?.replyId)
        unavailable = false
        catcher().sync(listOf(updated)).join() // newly created catch-up owner simulates restart
        assertEquals(setOf("chat"), receipts.unreadIds)
        assertEquals(1, discoveries)
        catcher().sync(listOf(updated)).join()
        assertEquals(1, discoveries)
        receipts.readThrough("chat", 12)
        assertTrue(receipts.unreadIds.isEmpty())
    }
}
