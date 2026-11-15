package org.praxisog.hermes

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.HermesResult
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/** Full MainActivity against the disposable stock host behind a 30-second outage proxy. */
class ConnectionRecoveryDeviceTest {
    private val ui = DeviceFixtureUi
    private val origin = "http://127.0.0.1:18664"
    private val token = "native-attachment-fixture-key"
    private fun control(action: String): JSONObject {
        val connection = URI("$origin/fixture/connection").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = 3000; connection.readTimeout = 3000; connection.doOutput = true
        return try {
            connection.outputStream.use { it.write(JSONObject().put("action", action).toString().toByteArray()) }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    @Test fun outagePreservesRemoteChatAndRunThenRecoversWithoutLocalFallback() = ui.withWindows {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val secure = SecureConnectionStore(context)
        val runtime = BellpullRuntime.get(context)
        ui.main { runtime.disconnect() }
        secure.clear()
        val profile = secure.add("Connection recovery fixture", origin, token)
        secure.activate(profile.id)
        // Even an existing opt-in must not load a model during a short outage.
        runtime.localSettings.update(LocalFallbackConfiguration(autoLoad = true))
        val client = HermesClient(origin, { token })
        val session = (client.createSession("Connection recovery fixture") as HermesResult.Ok).value
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                ui.awaitMain("Fixture did not connect") { runtime.store != null && runtime.connectionHealth.phase == ConnectionHealth.Phase.ONLINE }
                val store = checkNotNull(runtime.store)
                ui.main { store.openSession(session.id); store.refreshSessions() }
                ui.awaitMain("Fixture history did not load") { store.history is UiState.Ready && !store.refreshingSessions }
                control("fail-list")
                ui.main { store.refreshSessions() }
                ui.awaitMain("Synthetic list failure missing") { store.offline != null }
                assertFalse(runtime.connectionHealth.offline)
                ui.main { runtime.retryConnection() }
                ui.awaitMain("Healthy probe did not repair stale list") { store.offline == null && !store.refreshingSessions }

                ui.main { store.send("Keep this one synthetic remote run through the outage") }
                ui.awaitMain("Synthetic run admission missing") { store.runs?.states?.get(session.id)?.submission?.runId != null }
                val runId = store.runs!!.states[session.id]!!.submission.runId
                val outageStart = SystemClock.elapsedRealtime()
                control("outage")
                ui.main { runtime.retryConnection() }
                ui.awaitMain("Outage was not confirmed") { runtime.connectionHealth.offline }
                assertFalse(runtime.connectionHealth.localEligible)
                assertFalse(runtime.localModel.state.value.busy)
                assertFalse(runtime.localModel.state.value.ready)
                assertNull(ui.text("Offline · local model unavailable"))
                val newChat = checkNotNull(ui.find { it.contentDescription?.toString() == "New chat" })
                ui.tap(newChat)
                ui.awaitMain("Remote new-chat failure was not surfaced") { store.createError != null }
                assertTrue(runtime.offlineWorkspace!!.state.value.forks.isEmpty())
                assertEquals(session.id, store.openSessionId)
                control("release")
                ui.await("Connection did not recover", 45000) { runtime.connectionHealth.phase == ConnectionHealth.Phase.ONLINE }
                val recoveredAt = SystemClock.elapsedRealtime()
                ui.await("Original saved reply missing", 20000) {
                    ui.text("The original remote run survived the connection outage.") != null
                }
                assertEquals(runId, store.runs!!.states[session.id]!!.submission.runId)
                assertTrue(runtime.offlineWorkspace!!.state.value.forks.isEmpty())
                assertFalse(runtime.localModel.state.value.ready)
                val stats = control("inspect")
                assertEquals(1, stats.getInt("runs"))
                assertEquals(1, stats.getInt("submissions"))
                File(context.getExternalFilesDir(null), "connection-recovery.json").writeText(JSONObject()
                    .put("outage_ms", 30000).put("connection_recovery_after_outage_ms", recoveredAt - outageStart - 30000)
                    .put("executions", stats.getInt("runs")).put("submissions", stats.getInt("submissions"))
                    .put("local_forks", 0).put("local_model_loaded", false).put("stale_list_recovered", true).toString(2))
                ui.screenshot("connection-recovered")
            }
        } finally {
            runCatching { control("release") }
            ui.main { runtime.disconnect() }
            secure.clear(); SessionCache(context, profile.id).clear(); client.close()
        }
    }
}
