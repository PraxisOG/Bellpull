package org.praxisog.hermes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URI
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*

/** Only the separate sandbox and stock-device-fixture.py --audit-repair may run this. */
@RunWith(AndroidJUnit4::class)
class AuditRepairDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val origin = "http://127.0.0.1:18646"
    private val key = "bellpull-stock-device-fixture"
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(check: () -> Boolean) {
        val until = System.nanoTime() + 25_000_000_000L
        var ready = false
        while (!ready && System.nanoTime() < until) { main { ready = check() }; if (!ready) Thread.sleep(50) }
        assertTrue("Device fixture condition timed out", ready)
    }

    @Test fun initialStreamRetryAndRestartAfterCompactionReconcileWithoutDuplicateInference() {
        check(context.packageName == "org.praxisog.hermes.fixture") { "Use the separate device fixture application ID" }
        val secure = SecureConnectionStore(context)
        main { BellpullRuntime.get(context).disconnect() }
        secure.clear()
        val profile = secure.add("Audit fixture", origin, key)
        secure.activate(profile.id)
        val cache = SessionCache(context, profile.id)
        cache.rememberSession("phone_stream")
        val client = HermesClient(origin, { key })
        fun control(advance: Boolean = false): JsonObject {
            val request = URI(origin + if (advance) "/fixture/advance" else "/fixture/state").toURL().openConnection() as HttpURLConnection
            request.connectTimeout = 5000; request.readTimeout = 5000
            request.setRequestProperty("Authorization", "Bearer $key")
            if (advance) request.requestMethod = "POST"
            return try {
                assertEquals(200, request.responseCode)
                request.inputStream.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
            } finally { request.disconnect() }
        }
        assertEquals("idle", control()["phase"]!!.jsonPrimitive.content)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val drafts = ChatDrafts()
        val runs = mutableListOf<DurableRuns>()
        fun manager() = DurableRuns(scope, client, cache, drafts, 86400, pollMs = 250).also(runs::add)
        try {
            assertTrue(client.capabilities() is HermesResult.Ok)
            val previous = (client.completeHistory("phone_stream") as HermesResult.Ok).value.data.map { it.id }.toSet()
            val first = manager()
            main { first.send("phone_stream", "Device compaction prompt", previous) }
            await { (first.states["phone_stream"]?.turn as? TurnState.Streaming)?.reasoning?.contains("First live update") == true }
            control(true)
            await { (first.states["phone_stream"]?.turn as? TurnState.Streaming)?.reasoning?.length ?: 0 > 500 }
            control(true)
            await { (first.states["phone_stream"]?.turn as? TurnState.Streaming)?.text?.contains("read the fixture file") == true }
            control(true)
            await { (first.states["phone_stream"]?.turn as? TurnState.Streaming)?.text?.contains("Live streaming passed") == true }
            control(true)
            await { first.states["phone_stream"]?.submission?.status == "completed" &&
                (first.states["phone_stream"]?.submission?.historyChecks ?: 0) > 0 }
            val compacted = (client.completeHistory("phone_stream") as HermesResult.Ok).value.data
            assertFalse(compacted.any { it.content == "Device compaction prompt" })
            assertFalse(compacted.any { it.content?.startsWith("Live streaming passed") == true })
            main { assertTrue(first.states["phone_stream"]!!.busy); first.close() }
            val recovered = manager()
            main { recovered.restore(); assertTrue(recovered.states["phone_stream"]!!.busy) }
            control(true) // Final transcript transaction becomes visible after native completion.
            await { recovered.states["phone_stream"]?.submission?.historySynced == true }
            main { assertFalse(recovered.states["phone_stream"]!!.busy); assertTrue(recovered.states["phone_stream"]!!.turn is TurnState.Idle) }
            val state = control()
            assertEquals(1, state["runs"]!!.jsonPrimitive.int)
            assertEquals(1, state["event_failures"]!!.jsonPrimitive.int)
            assertTrue(state["event_requests"]!!.jsonPrimitive.int >= 2)
            assertTrue((client.completeHistory("phone_stream") as HermesResult.Ok).value.data.any {
                it.content?.startsWith("Live streaming passed") == true })
            lateinit var store: HermesStore
            main { store = HermesStore(scope, client, durableRetention = 86400, journal = cache); store.startRecovery(); store.openSession("phone_stream") }
            try {
                await { store.history is UiState.Ready }
                main { assertNull(store.pendingUserText); assertTrue(store.turn is TurnState.Idle) }
            } finally { main { store.close() } }
        } finally {
            main { runs.forEach { it.close() }; scope.cancel(); cache.close() }
            client.close()
        }
    }
}
