package org.praxisog.hermes.core

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.model.*

class NativeProfileContractTest {
    private fun reply(request: Request, body: String, code: Int = 200) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body.toResponseBody("application/json".toMediaType())).build()

    @Test fun discoveryAndSubsequentRequestsKeepTheSelectedNativeProfile() {
        for (prefix in listOf("", "/p/work")) {
            val paths = mutableListOf<String>()
            val client = HermesClient("https://fixture.invalid$prefix/", { "profile-fixture" },
                httpClient = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
                    val request = chain.request()
                    assertEquals("Bearer profile-fixture", request.header("Authorization"))
                    val path = request.url.encodedPath.also { paths += it }
                    assertTrue(path.startsWith("$prefix/"))
                    val body = when (path.removePrefix(prefix)) {
                        "/v1/capabilities" -> """{"features":{"run_submission":true}}"""
                        "/api/bellpull/capabilities" -> """{"version":1,"features":{"bellpull_snapshot_paging":true}}"""
                        "/api/bellpull/sessions" -> """{"data":[],"has_more":false}"""
                        "/api/bellpull/sessions/chat/messages" -> """{"session_id":"chat","data":[]}"""
                        "/api/bellpull/artifacts" -> """{"data":[]}"""
                        "/v1/runs" -> """{"run_id":"run","status":"running"}"""
                        "/v1/runs/run" -> """{"run_id":"run","status":"running","session_id":"chat"}"""
                        "/v1/runs/run/events" -> "data: {\"event\":\"message.delta\",\"delta\":\"answer\",\"seq\":0}\n\n"
                        else -> error("Unexpected request $path")
                    }
                    reply(request, body)
                }.build())
            try {
                assertTrue(client.capabilities() is HermesResult.Ok)
                assertTrue(client.allSessions() is HermesResult.Ok)
                assertTrue(client.completeHistory("chat") is HermesResult.Ok)
                assertTrue(client.artifacts() is HermesResult.Ok)
                assertTrue(client.submitRun("chat", "hello", "once") is HermesResult.Ok)
                assertTrue(client.runStatus("run") is HermesResult.Ok)
                var answer = ""
                client.runEvents("run") { if (it is StreamEvent.AssistantDelta) answer += it.delta }
                assertEquals("answer", answer)
                assertEquals(8, paths.size)
            } finally { client.close() }
        }
    }

    @Test fun unservedProfileFailsAuthenticatedDiscoveryWithoutRootFallback() {
        val paths = mutableListOf<String>()
        val client = HermesClient("https://fixture.invalid/p/unserved", { "fixture" },
            httpClient = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
                paths += chain.request().url.encodedPath
                reply(chain.request(), """{"error":{"message":"Profile is not served"}}""", 404)
            }.build())
        try {
            assertTrue(client.capabilities() is HermesResult.Err)
            assertEquals(listOf("/p/unserved/v1/capabilities"), paths)
        } finally { client.close() }
    }
}
