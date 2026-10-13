package org.praxisog.hermes.core
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.Assert.*
import org.praxisog.hermes.model.*

class PlanningContractTest {
    @Test fun planningRequestsKeepNativeProfileAndNeverAdmitInference() {
        val calls=mutableListOf<String>()
        val client=HermesClient("https://fixture.invalid/p/work/",{"fixture"},httpClient=HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val request=chain.request(); calls += request.method+" "+request.url.encodedPath
            assertEquals("Bearer fixture",request.header("Authorization"))
            val body=if (request.method=="GET") """{"version":1,"reminders":[],"observed_at":1}""" else "{}"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build())
        try {
            assertTrue(client.planning() is HermesResult.Ok)
            assertTrue(client.planningAction("reminders",buildJsonObject { put("action","create") }) is HermesResult.Ok)
            assertTrue(client.shareCalendar("a".repeat(32),buildJsonObject { put("revoke",true) }) is HermesResult.Ok)
            assertEquals(listOf("GET /p/work/api/bellpull/planning","POST /p/work/api/bellpull/planning/reminders","PUT /p/work/api/bellpull/calendar/"+"a".repeat(32)),calls)
            assertThrows(IllegalArgumentException::class.java) { client.planningAction("../runs",JsonObject(emptyMap())) }
            assertThrows(IllegalArgumentException::class.java) { client.shareCalendar("../root",JsonObject(emptyMap())) }
        } finally { client.close() }
    }
}
