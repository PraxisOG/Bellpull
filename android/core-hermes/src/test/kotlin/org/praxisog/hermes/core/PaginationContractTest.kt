package org.praxisog.hermes.core

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.model.*

class PaginationContractTest {
    private fun client(reply: (okhttp3.Request) -> String) = HermesClient("http://fixture.invalid", { "fixture" },
        httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(reply(chain.request()).toResponseBody("application/json".toMediaType())).build()
        }.build())
    private fun <T> ok(result: HermesResult<T>) = (result as HermesResult.Ok).value

    @Test fun moreThanFiftyConversationsSurviveActivityBetweenPages() {
        val old = (1..450).map { Session("s$it", source = "cli", lastActive = 1000.0 - it) }
        var changed = false
        val offsets = mutableListOf<Int>()
        val client = client { request ->
            assertEquals("cli", request.url.queryParameter("source"))
            val offset = request.url.queryParameter("offset")!!.toInt().also { offsets += it }
            if (offset > 0) changed = true
            val rows = if (changed) listOf(Session("new", source = "cli", lastActive = 2000.0)) + old else old
            Json.encodeToString(SessionList(data = rows.drop(offset).take(200), limit = 200, offset = offset,
                hasMore = offset + 200 < rows.size))
        }
        val result = ok(client.allSessions("cli"))
        assertEquals(451, result.data.size)
        assertEquals((old.map { it.id } + "new").toSet(), result.data.map { it.id }.toSet())
        assertFalse(result.hasMore)
        assertTrue(offsets.count { it == 0 } >= 3)
    }

    @Test fun moreThanFiveHundredMessagesIncludeNewArrivalsOnce() {
        val rows = (1..621).map { Message(it, role = "user", content = "Message $it") }
        val client = client { request ->
            assertEquals("oldest", request.url.queryParameter("order"))
            val offset = request.url.queryParameter("offset")!!.toInt()
            val page = rows.drop(offset).take(500)
            Json.encodeToString(MessageHistory(sessionId = "resolved", data = page,
                pagination = Pagination(500, offset, "oldest", page.size)))
        }
        val result = ok(client.completeHistory("original"))
        assertEquals(rows, result.data)
        assertEquals("resolved", result.sessionId)
        assertEquals(621, result.pagination?.returned)
    }

    @Test fun compressionDuringPagingRestartsAtTheNewLineage() {
        var compressed = false
        val client = client { request ->
            val offset = request.url.queryParameter("offset")!!.toInt()
            if (offset > 0) compressed = true
            Json.encodeToString(MessageHistory(sessionId = if (compressed) "tip" else "original",
                data = if (compressed) listOf(Message(900, role = "assistant", content = "Summary"))
                    else (1..500).map { Message(it, role = "user", content = "Old $it") }))
        }
        assertEquals(listOf(900), ok(client.completeHistory("original")).data.map { it.id })
    }

    @Test fun aBrokenPagingServerFailsInsteadOfLoopingOrClaimingCompleteness() {
        val client = client { Json.encodeToString(SessionList(data = listOf(Session("same")), hasMore = true)) }
        assertTrue(client.allSessions() is HermesResult.Err)
    }

    @Test fun versionedPluginDiscoveryEnablesStablePagingWithoutReplacingNativeRunContract() {
        val calls = mutableListOf<String>()
        val client = client { request ->
            val path = request.url.encodedPath.also { calls += it }
            when (path) {
                "/v1/capabilities" -> """{"features":{"run_submission":true,"runs_idempotency":{"supported":true,"retention_seconds":86400}}}"""
                "/api/bellpull/capabilities" -> """{"version":1,"features":{"bellpull_snapshot_paging":true,"session_fork_at_message":true,"bellpull_reasoning_streaming":true,"bellpull_phone_context":true,"bellpull_background_folder":true,"bellpull_kanban":true}}"""
                "/api/bellpull/sessions" -> if (request.url.queryParameter("cursor") == null)
                    Json.encodeToString(SessionList(data = listOf(Session("s1")), hasMore = true, nextCursor = "cursor:1"))
                    else Json.encodeToString(SessionList(data = listOf(Session("s2"))))
                "/api/bellpull/sessions/s1/messages" -> if (request.url.queryParameter("cursor") == null)
                    Json.encodeToString(MessageHistory(sessionId = "tip", data = listOf(Message(1, role = "user", content = "a")),
                        pagination = Pagination(1, 0, "oldest", 1, "cursor:1", 2)))
                    else Json.encodeToString(MessageHistory(sessionId = "tip", data = listOf(Message(2, role = "assistant", content = "b")),
                        pagination = Pagination(1, 1, "oldest", 1, null, 2)))
                else -> error(path)
            }
        }
        val caps = ok(client.capabilities())
        assertTrue(caps.features["bellpull_phone_context"] == true)
        assertTrue(caps.features["bellpull_background_folder"] == true)
        assertTrue(caps.features["bellpull_kanban"] == true)
        assertTrue(caps.sessionForkAtMessage)
        assertTrue(caps.features["bellpull_reasoning_streaming"] == true)
        assertTrue(caps.features["run_submission"] == true)
        assertEquals(listOf("s1", "s2"), ok(client.allSessions()).data.map { it.id })
        assertEquals(listOf(1, 2), ok(client.completeHistory("s1")).data.map { it.id })
        assertTrue(calls.none { it == "/api/sessions" })
    }

    @Test fun unknownPluginVersionDisablesExtensionControlsAndPreservesNativeRuns() {
        val client = client { request -> if (request.url.encodedPath == "/v1/capabilities")
            """{"features":{"run_submission":true,"session_fork_at_message":true}}"""
            else """{"version":2,"features":{"session_fork_at_message":true,"bellpull_snapshot_paging":true,"run_submission":false}}""" }
        val caps = ok(client.capabilities())
        assertFalse(caps.sessionForkAtMessage)
        assertFalse(caps.features["bellpull_snapshot_paging"] == true)
        assertTrue(caps.features["run_submission"] == true)
    }
}
