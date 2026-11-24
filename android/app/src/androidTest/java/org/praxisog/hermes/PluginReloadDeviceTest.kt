package org.praxisog.hermes

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

/** Run only against tools/plugin-reload-device-fixture.py over an ADB reverse. */
class PluginReloadDeviceTest {
    private val origin = "http://127.0.0.1:18659"
    private val token = "bellpull-reload-device-fixture"
    private fun control(action: String): JsonObject {
        val request = URI("$origin/fixture/reload").toURL().openConnection() as HttpURLConnection
        request.connectTimeout = 5000; request.readTimeout = 10000
        request.setRequestProperty("Authorization", "Bearer $token")
        request.setRequestProperty("Content-Type", "application/json")
        request.requestMethod = "POST"; request.doOutput = true
        request.outputStream.use { it.write("""{"action":"$action"}""".toByteArray()) }
        return try {
            assertEquals(200, request.responseCode)
            request.inputStream.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        } finally { request.disconnect() }
    }
    private fun <T> ok(result: HermesResult<T>): T = when (result) {
        is HermesResult.Ok -> result.value
        is HermesResult.Err -> error(result.error.toString())
    }

    @Test fun integrationStatusShowsPluginOutageAndRecoveryWhileGatewayRemainsAvailable() {
        val ui = DeviceFixtureUi
        ui.guard()
        val app = ApplicationProvider.getApplicationContext<Context>()
        val runtime = BellpullRuntime.get(app)
        val secure = SecureConnectionStore(app)
        ui.main { runtime.disconnect() }
        secure.clear()
        val profile = secure.add("Integration fixture", origin, token)
        secure.activate(profile.id)
        ui.withWindows {
            try {
                ActivityScenario.launch(MainActivity::class.java).use {
                    ui.awaitMain("Fixture profile did not connect") { runtime.store != null }
                    if (ui.text("Settings") == null) ui.find {
                        it.contentDescription?.toString() in setOf("Back to sessions", "Show sessions")
                    }?.let(ui::tap)
                    ui.tapText("Settings")
                    ui.tapText("Servers")
                    ui.tapText("Integration status")
                    ui.tapText("Check now")
                    ui.awaitMain("Initial status did not arrive") {
                        runtime.integrationHealth.checks[IntegrationEndpoint.READINESS] != null &&
                            runtime.integrationHealth.checks[IntegrationEndpoint.MODEL_ROUTE]?.condition == IntegrationCondition.PROVIDER_UNAVAILABLE &&
                            runtime.integrationHealth.checks[IntegrationEndpoint.PLUGIN]?.condition == IntegrationCondition.READY
                    }
                    control("disable")
                    ui.tapText("Check now")
                    ui.awaitMain("Plugin outage hidden behind healthy native HTTP") {
                        val checks = runtime.integrationHealth.checks
                        checks[IntegrationEndpoint.GATEWAY]?.condition == IntegrationCondition.READY &&
                            checks[IntegrationEndpoint.PLUGIN]?.httpStatus == 503
                    }
                    ui.await("503 missing on screen") { ui.find { it.text?.toString()?.contains("HTTP 503") == true } != null }
                    val outage = runtime.integrationHealth.checks.getValue(IntegrationEndpoint.PLUGIN)
                    assertNotNull(outage.lastReadyAt)
                    val reopened = SessionCache(app, profile.id)
                    try {
                        assertEquals(outage, reopened.readIntegrationHealth().checks[IntegrationEndpoint.PLUGIN])
                    } finally { reopened.close() }
                    control("enable")
                    ui.tapText("Check now")
                    ui.awaitMain("Plugin did not recover") {
                        runtime.integrationHealth.checks[IntegrationEndpoint.PLUGIN]?.condition == IntegrationCondition.READY
                    }
                    assertEquals(0, control("inspect")["runs"]!!.jsonPrimitive.int)
                }
                val rejected = HermesClient(origin, { "expired-fixture-key" })
                try {
                    assertTrue(rejected.checkIntegrations().all {
                        it.condition == IntegrationCondition.DENIED && it.httpStatus == 401
                    })
                } finally { rejected.close() }
            } finally {
                try { control("enable") } finally { ui.main { runtime.disconnect() } }
            }
        }
    }

    @Test fun wirelessReloadRetainsBoardAndRunAndHonorsDisable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName in setOf("org.praxisog.hermes.fixture", "org.praxisog.hermes.canvasfixture"))
        val ui = DeviceFixtureUi
        val flags = ui.automation.serviceInfo.flags
        ui.automation.serviceInfo = ui.automation.serviceInfo.apply {
            this.flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val cache = SessionCache(context, "reload-device-" + UUID.randomUUID())
        val workspace = KanbanWorkspace(scope, cache)
        val client = HermesClient(origin, { token })
        fun pluginReady() = (client.capabilities() as? HermesResult.Ok)?.value?.features?.get("bellpull_kanban") == true
        fun refresh() {
            ui.main { workspace.refresh() }
            ui.awaitMain("Board did not recover after plugin reload") { !workspace.busy && workspace.fresh && workspace.snapshot != null }
        }
        try {
            ui.await("Reload fixture is unavailable") { pluginReady() }
            assertTrue(ok(client.capabilities()).features["bellpull_kanban"] == true)
            val session = ok(client.createSession("Wireless reload check"))
            val requestId = UUID.randomUUID().toString()
            val accepted = ok(client.submitRun(session.id, "Wait through reload", requestId))
            ui.await("Fixture run did not start") { control("inspect")["runs"]!!.jsonPrimitive.int == 1 }
            ui.main { workspace.configure(client, true) }
            refresh()
            val taskId = workspace.snapshot!!.tasks.single().id
            ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
                scene.onActivity { activity -> activity.setContent { HermesTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    KanbanScreen(workspace, foreground = false)
                } } } }
                ui.await("Task missing on phone") { ui.text("Reload keeps this task") != null }
                repeat(3) {
                    control("reload")
                    ui.await("Capabilities did not recover") { pluginReady() }
                    refresh()
                    assertEquals(taskId, workspace.snapshot!!.tasks.single().id)
                    assertEquals(accepted.runId, ok(client.submitRun(session.id, "Wait through reload", requestId)).runId)
                    assertEquals(1, control("inspect")["runs"]!!.jsonPrimitive.int)
                }
                ui.screenshot("plugin-reload-board-recovered")
                control("disable")
                assertFalse("Disabled plugin still advertises Tasks", pluginReady())
                assertTrue(client.kanbanBoards() is HermesResult.Err)
                ui.main { workspace.refresh() }
                ui.awaitMain("Disabled integration appeared fresh") { !workspace.busy && workspace.problem != null }
                assertEquals(taskId, workspace.snapshot!!.tasks.single().id)
                ui.screenshot("plugin-reload-disabled")
                control("enable")
                ui.await("Re-enabled integration unavailable") { pluginReady() }
                refresh()
                assertEquals(taskId, workspace.snapshot!!.tasks.single().id)
                ui.screenshot("plugin-reload-enabled")
            }
            control("release")
            ui.await("Original run did not finish") { ok(client.runStatus(accepted.runId)).status == "completed" }
            assertEquals(accepted.runId, ok(client.submitRun(session.id, "Wait through reload", requestId)).runId)
            assertEquals(1, control("inspect")["runs"]!!.jsonPrimitive.int)
        } finally {
            runCatching { control("release") }
            ui.main { workspace.close(); cache.close() }
            scope.cancel()
            ui.automation.serviceInfo = ui.automation.serviceInfo.apply { this.flags = flags }
        }
    }
}
