package org.praxisog.hermes

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class) @Config(sdk=[30])
class DeliveryWorkspaceTest {
    private fun waitUntil(condition: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000
        while (!condition()) { check(System.nanoTime() < end) { "Timed out" }; Thread.sleep(10) }
    }
    @Test fun lostAcknowledgementReusesDurableIdentityAfterCoordinatorRestart() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val preferences = context.getSharedPreferences("delivery-retry", Context.MODE_PRIVATE)
        val cache = SessionCache(context, "retry", preferences); cache.clear()
        val reminder = Reminder("a".repeat(32), "Reminder", System.currentTimeMillis()+3600000, "pending", 1)
        cache.updateDelivery { it.reconcile(PlanningSnapshot(1, listOf(reminder), observedAt=1.0), 1000) }
        val operations = CopyOnWriteArrayList<JsonObject>()
        var lostResponse = true
        var completed = false
        val http = HermesClient.defaultHttpClient().newBuilder().retryOnConnectionFailure(false).addInterceptor { chain ->
            val request = chain.request()
            check(request.url.encodedPath.startsWith("/api/bellpull/planning")) { "No inference or unrelated API is allowed" }
            val response = if (request.method == "POST") {
                val buffer = Buffer(); request.body!!.writeTo(buffer)
                operations.add(Json.parseToJsonElement(buffer.readUtf8()).jsonObject)
                completed = true
                if (lostResponse) throw IOException("Fixture lost response after server commit")
                "{}"
            } else Json.encodeToString(PlanningSnapshot(1, listOf(if (completed) reminder.copy(state="completed", revision=2) else reminder), observedAt=2.0))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = HermesClient("http://fixture.invalid", { "fixture" }, httpClient=http)
        var scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        var workspace = DeliveryWorkspace(context, "retry", scope, cache, client, false)
        try {
            waitUntil { workspace.ready && workspace.alertStatus.isNotEmpty() }
            workspace.supported = true
            workspace.action(reminder, "complete", null)
            waitUntil { operations.size == 1 && !workspace.syncing && workspace.problem != null }
            val operation = cache.readDelivery().operations.single()
            assertEquals("completed", cache.readDelivery().reminders().single().state)
            workspace.close(); scope.cancel(); cache.close()
            val restored = SessionCache(context, "retry", preferences)
            assertEquals(operation.operationId, restored.readDelivery().operations.single().operationId)
            lostResponse = false
            scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
            workspace = DeliveryWorkspace(context, "retry", scope, restored, client, true)
            waitUntil { workspace.state.lastSync > 1000 && workspace.state.operations.isEmpty() && !workspace.syncing }
            assertEquals(2, operations.size)
            assertEquals(operations[0], operations[1])
            assertEquals("completed", workspace.state.reminders().single().state)
            restored.clear()
        } finally { workspace.close(); scope.cancel(); client.close() }
    }
}
