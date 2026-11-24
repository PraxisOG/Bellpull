package org.praxisog.hermes.core

import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.model.*

class IntegrationChecksTest {
    @Test fun nativeFallbackPreservesIndependentRedactedEvidenceAndManualChecksProbeEveryEndpoint() {
        val calls = mutableListOf<String>()
        var gatewayCode = 200
        var pluginCode = 503
        val http = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val req = chain.request()
            calls += req.url.encodedPath
            assertTrue(req.url.encodedPath.startsWith("/p/work/"))
            assertEquals("GET", req.method)
            assertEquals("Bearer fixture", req.header("Authorization"))
            val (status, body) = when (req.url.encodedPath.removePrefix("/p/work")) {
                "/v1/capabilities" -> gatewayCode to if (gatewayCode == 200) """{"features":{"session_chat_streaming":true}}""" else """{"error":{"message":"private key detail"}}"""
                "/api/bellpull/capabilities" -> pluginCode to if (pluginCode == 200) """{"version":1,"features":{"bellpull_notification_events":true}}""" else """{"error":{"code":"bellpull_restart_required","message":"private operator log"}}"""
                "/health/detailed" -> 200 to """{"updated_at":"2026-01-01T01:00:00Z","readiness":{"status":"degraded","checks":{"model":{"status":"ok"},"background_queues":{"active_api_runs":2,"active_delegations":4,"process_completions":0}}},"exit_reason":"private text"}"""
                "/api/bellpull/models" -> 200 to """{"version":1,"default":{"provider":"lab","model":"text-v2","model_options":{"secret":"private value"}},"models":[{"provider":"lab","model":"text-v2","configured":true,"vision":false}]}"""
                else -> error("Unexpected probe")
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = HermesClient("https://fixture.invalid/p/work", { "fixture" }, httpClient=http)
        try {
            val evidence = mutableListOf<IntegrationCheck>()
            val caps = (client.capabilities(evidence::add) as HermesResult.Ok).value
            assertTrue(caps.sessionChatStreaming)
            assertFalse(caps.notificationEvents)
            assertEquals(listOf(IntegrationCondition.READY, IntegrationCondition.RESTART_REQUIRED), evidence.map { it.condition })
            assertEquals(2, calls.size)
            gatewayCode = 401
            pluginCode = 200
            val results = client.checkIntegrations().associateBy { it.endpoint }
            assertEquals(6, calls.size)
            assertEquals(IntegrationCondition.DENIED, results[IntegrationEndpoint.GATEWAY]?.condition)
            assertEquals(true, results[IntegrationEndpoint.PLUGIN]?.relayAvailable)
            val runtime = results.getValue(IntegrationEndpoint.READINESS)
            assertEquals(IntegrationCondition.DEGRADED, runtime.condition)
            assertEquals(2, runtime.activeRuns)
            assertEquals(4, runtime.activeDelegations)
            assertEquals(true, runtime.modelConfigured)
            assertNotNull(runtime.runtimeUpdatedAt)
            val route = results.getValue(IntegrationEndpoint.MODEL_ROUTE)
            assertEquals("text-v2", route.model)
            assertEquals("lab", route.provider)
            assertEquals(false, route.vision)
            assertFalse((results.toString() + evidence.toString()).contains("private"))
            pluginCode = 429
            assertEquals(IntegrationCondition.RATE_LIMITED, client.checkIntegrations().single { it.endpoint == IntegrationEndpoint.PLUGIN }.condition)
            assertEquals(10, calls.size)
        } finally { client.close() }
    }

    @Test fun recentFailureSurvivesALateOlderSuccessAndRestart() {
        fun check(at: Long, condition: IntegrationCondition) = IntegrationCheck(IntegrationEndpoint.PLUGIN, at, condition)
        val first = IntegrationHealth().record(check(120, IntegrationCondition.UNAVAILABLE))
            .record(check(100, IntegrationCondition.READY))
        val restored = Json.decodeFromString<IntegrationHealth>(Json.encodeToString(first))
        assertEquals(IntegrationCondition.UNAVAILABLE, restored.checks.getValue(IntegrationEndpoint.PLUGIN).condition)
        assertEquals(100L, restored.checks.getValue(IntegrationEndpoint.PLUGIN).lastReadyAt)
        assertEquals(140L, restored.record(check(140, IntegrationCondition.READY)).checks.getValue(IntegrationEndpoint.PLUGIN).lastReadyAt)
    }
}
