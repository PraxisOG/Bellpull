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
    @Test fun offlineReceiptReturnsOnceWithPermissionOffAndPlanningUnavailable() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val preferences = context.getSharedPreferences("run-receipt-reconnect", Context.MODE_PRIVATE)
        val cache = SessionCache(context, "receipts", preferences)
        cache.clear()
        val requests = CopyOnWriteArrayList<Long>()
        val online = java.util.concurrent.atomic.AtomicBoolean(false)
        val accepted = java.util.concurrent.atomic.AtomicBoolean(false)
        val journal = "a".repeat(64)
        val id = "b".repeat(64)
        val http = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            check(request.method == "GET" && request.url.encodedPath == "/api/bellpull/notifications/history")
            val after = request.url.queryParameter("after")!!.toLong()
            requests.add(after)
            if (!online.get()) throw IOException("Fixture offline")
            val revision = if (accepted.get()) 4L else 2L
            val state = if (accepted.get()) """"dispatch":"transport_accepted","accepted_at":45.0""" else """"dispatch":"pending""""
            val event = """{"event_id":"$id","revision":$revision,"source":"hermes.run","run_id":"run_1","session_id":"chat","status":"failed","severity":"error","occurred_at":30.0,$state}"""
            val events = if (revision > after) event else ""
            val body = """{"version":1,"journal_id":"$journal","after":$after,"events":[$events],"next_cursor":${maxOf(revision,after)},"has_more":false}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = HermesClient("http://fixture.invalid", { "test-only" }, httpClient = http)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var workspace = DeliveryWorkspace(context, "receipts", scope, cache, client, false, true)
        try {
            waitUntil { workspace.notificationHistoryProblem != null }
            assertEquals(0L, cache.readDelivery().notificationCursor)
            online.set(true)
            workspace.refresh()
            waitUntil { cache.readDelivery().notificationCursor == 2L }
            workspace.mark("run:run_1", true)
            waitUntil { cache.readDelivery().entries.single().dismissedAt != null }
            workspace.close()
            accepted.set(true)
            workspace = DeliveryWorkspace(context, "receipts", scope, cache, client, false, true)
            waitUntil { cache.readDelivery().notificationCursor == 4L }
            val item = cache.readDelivery().entries.single()
            assertNotNull(item.dismissedAt)
            assertEquals("transport_accepted", item.runReceipt?.dispatch)
            workspace.refresh()
            waitUntil { 4L in requests }
            assertEquals(1, cache.readDelivery().entries.size)
        } finally { workspace.close(); scope.cancel(); client.close(); cache.clear(); cache.close() }
    }
    @Test fun healthyServicePollChecksForRemoteChangesWhileWakeHintsAndFailedPollsAreThrottled() {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = context.getSharedPreferences("service-poll", Context.MODE_PRIVATE)
        val cache = SessionCache(context, "poll", prefs)
        val count = java.util.concurrent.atomic.AtomicInteger()
        val online = java.util.concurrent.atomic.AtomicBoolean(true)
        val http = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            check(request.method == "GET" && request.url.encodedPath == "/api/bellpull/planning")
            count.incrementAndGet()
            if (!online.get()) throw IOException("Fixture offline")
            val body = Json.encodeToString(PlanningSnapshot(1, emptyList(), observedAt = 1.0))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = HermesClient("http://fixture.invalid", { "test-only" }, httpClient = http)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val workspace = DeliveryWorkspace(context, "poll", scope, cache, client, true)
        try {
            waitUntil { workspace.hasSynced && !workspace.syncing }
            val first = count.get()
            workspace.tick()
            assertEquals(first, count.get())
            online.set(false)
            workspace.tick(fromServiceTimer = true)
            waitUntil { workspace.problem != null && !workspace.syncing }
            val failed = count.get()
            assertTrue(failed > first)
            workspace.tick(fromServiceTimer = true)
            assertFalse(workspace.syncing)
            assertEquals(failed, count.get())
            online.set(true)
            workspace.refresh()
            waitUntil { workspace.problem == null && !workspace.syncing && count.get() > failed }
        } finally { workspace.close(); scope.cancel(); client.close(); cache.clear(); cache.close() }
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
