package org.praxisog.hermes.core

import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.model.*

class CanvasContractTest {
    @Test fun canvasMenuUsesOnlyTheSupportedPluginCapability() {
        for (version in listOf(1, 99)) {
            val client = HermesClient("https://fixture.invalid", { "fixture" }, httpClient = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
                val request = chain.request()
                val body = if (request.url.encodedPath == "/v1/capabilities") """{"features":{}}"""
                    else """{"version":$version,"features":{"bellpull_canvas":true}}"""
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build())
            try { assertEquals(version == 1, client.capabilities().valueOrNull()?.features?.get("bellpull_canvas")) }
            finally { client.close() }
        }
    }
    @Test fun sameProfileAndSessionUseOneContractForSnapshotEditAndLiveEvents() {
        val snapshot = CanvasSnapshot(1,"a".repeat(64),"chat",listOf("chat"),3)
        val encoded = Json.encodeToString(snapshot)
        val requests = mutableListOf<Request>()
        val client = HermesClient("https://fixture.invalid/p/work", { "fixture" }, httpClient = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            assertEquals("Bearer fixture", request.header("Authorization"))
            assertTrue(request.url.encodedPath.startsWith("/p/work/api/bellpull/sessions/chat/canvas"))
            val stream = request.url.encodedPath.endsWith("/events")
            val body = if (stream) "id: 3\ndata: {\"event\":\"canvas.reset\",\"snapshot\":$encoded}\n\n" else encoded
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody((if (stream) "text/event-stream" else "application/json").toMediaType())).build()
        }.build())
        try {
            assertEquals(snapshot, client.canvas("chat").valueOrNull())
            assertTrue(client.canvasEdit("chat", buildJsonObject { put("action","create"); put("operation_id","once"); put("canvas_id","doc") }) is HermesResult.Ok)
            val events = mutableListOf<CanvasSnapshot>()
            assertTrue(client.canvasEvents("chat", {}, events::add) is HermesResult.Ok)
            assertEquals(listOf(snapshot), events); assertEquals(3,requests.size)
            assertThrows(IllegalArgumentException::class.java) { client.canvas("../other") }
        } finally { client.close() }
    }
}
