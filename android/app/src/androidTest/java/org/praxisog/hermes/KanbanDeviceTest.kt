package org.praxisog.hermes

import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import java.net.HttpURLConnection
import java.net.URI

/** Run only against tools/kanban-device-fixture.py and the isolated fixture APK. */
@RunWith(AndroidJUnit4::class)
class KanbanDeviceTest {
    private val origin = "http://127.0.0.1:18658"
    private val token = "bellpull-kanban-device-fixture"
    private val ui = DeviceFixtureUi
    private fun control(action: String = "inspect"): JsonObject {
        val request = URI("$origin/fixture/kanban").toURL().openConnection() as HttpURLConnection
        request.connectTimeout = 5000; request.readTimeout = 5000
        request.setRequestProperty("Authorization", "Bearer $token")
        request.setRequestProperty("Content-Type", "application/json")
        request.requestMethod = "POST"; request.doOutput = true
        request.outputStream.use { it.write("""{"action":"$action"}""".toByteArray()) }
        return try {
            assertEquals(200, request.responseCode)
            request.inputStream.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        } finally { request.disconnect() }
    }

    @Test fun mainNavigationShowsBoardsAndTodayOpensTheSameTask() = ui.withWindows {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ui.main { BellpullRuntime.get(context).disconnect() }
        val secure = SecureConnectionStore(context)
        secure.clear()
        val profile = secure.add("Kanban fixture", origin, token)
        secure.activate(profile.id)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                ui.tapText("Tasks")
                ui.await("Main activity did not load native tasks") { ui.text("Ship the mobile board") != null }
                ui.screenshot("kanban-main-list-unfolded")
                ui.tapText("Board")
                ui.await("Column view missing") { ui.text("Triage · 0") != null || ui.text("Triage · 1") != null }
                ui.screenshot("kanban-main-board-unfolded")
                ui.tapText("Default")
                ui.tapText("Garden projects · Kanban fixture desktop")
                ui.await("Board selector did not switch") { ui.text("This board has no tasks.") != null }
                ui.tapText("Garden projects")
                ui.tapText("Default · Kanban fixture desktop")
                ui.awaitMain("Default board did not return") { BellpullRuntime.get(context).kanban?.board?.slug == "default" }
                ui.tapText("Today")
                repeat(12) {
                    if (ui.text("Ship the mobile board") == null) ui.scroll(true)
                }
                ui.tapText("Ship the mobile board")
                ui.await("Today did not open task details") { ui.text("Back to board") != null && ui.text("Ship the mobile board") != null }
                ui.screenshot("kanban-main-today-task-unfolded")
            }
        } finally {
            ui.main { BellpullRuntime.get(context).disconnect() }
            secure.clear()
        }
    }

    @Test fun nativeBoardTouchOfflineRestartLostResponseAndConcurrentEdit() = ui.withWindows {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val client = HermesClient(origin, { token }, httpClient = HermesClient.defaultHttpClient().newBuilder().retryOnConnectionFailure(false).build())
        val capabilities = (client.capabilities() as HermesResult.Ok).value
        assertTrue(capabilities.features["bellpull_kanban"] == true)
        var cache = SessionCache(context, "kanban-device-fixture")
        var workspace = KanbanWorkspace(scope, cache)
        val id = control()["task_id"]!!.jsonPrimitive.content
        fun sync() {
            ui.main { workspace.refresh() }
            ui.awaitMain("Kanban did not sync") { !workspace.busy && workspace.fresh && workspace.snapshot != null }
        }
        fun awaitIdle() = ui.awaitMain("Kanban queue did not settle") { !workspace.busy }
        try {
            ui.main { workspace.configure(client, true) }
            sync()
            ui.main { workspace.open(id) }
            ui.awaitMain("Task details missing") { workspace.detail != null && !workspace.busy }
            ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
                scene.onActivity { activity -> activity.setContent { HermesTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    KanbanScreen(workspace, foreground = false)
                } } } }
                ui.await("Task title missing") { ui.text("Ship the mobile board") != null }
                ui.screenshot("kanban-detail-unfolded")
                ui.tapText("Move to blocked")
                ui.awaitMain("Native move failed") { workspace.state.pending.isEmpty() && workspace.detail?.task?.status == "blocked" }
                assertEquals("blocked", control()["task"]!!.jsonObject["status"]!!.jsonPrimitive.content)
                ui.main { workspace.configure(null, false) }
                val field = ui.find { it.isEditable } ?: error("Comment field missing")
                assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Queued from the physical phone")
                }))
                ui.tapText("Add comment")
                ui.awaitMain("Offline comment was not saved") { workspace.state.pending.singleOrNull()?.state == "queued" }
                ui.screenshot("kanban-offline-queued-unfolded")
            }
            ui.main {
                workspace.close(); cache.close()
                cache = SessionCache(context, "kanban-device-fixture")
                workspace = KanbanWorkspace(scope, cache)
            }
            assertEquals("queued", workspace.state.pending.single().state)
            assertEquals("Queued from the physical phone", workspace.state.pending.single().mutation.comment)
            val other = SessionCache(context, "kanban-other-connection")
            try { assertTrue(other.readKanban().pending.isEmpty()); assertTrue(other.readKanban().boards.isEmpty()) }
            finally { other.close() }
            ui.main { workspace.configure(client, true) }
            sync()
            ui.awaitMain("Queued edit not applied") { workspace.state.pending.isEmpty() }
            ui.main { workspace.open(id) }
            ui.awaitMain("Details not loaded after restart") { !workspace.busy && workspace.detail?.task?.comments?.any { it.body == "Queued from the physical phone" } == true }

            control("drop")
            ui.main { workspace.comment(id, "Lost response on real transport") }
            ui.awaitMain("Lost response was not retained") { !workspace.busy && workspace.state.pending.singleOrNull()?.state == "unknown" && workspace.problem != null }
            val posts = control()["posts"]!!.jsonPrimitive.int
            ui.main { workspace.close(); cache.close(); cache = SessionCache(context, "kanban-device-fixture"); workspace = KanbanWorkspace(scope, cache); workspace.configure(client, true) }
            sync()
            ui.awaitMain("Unknown edit did not reconcile") { workspace.state.pending.isEmpty() }
            assertEquals(posts, control()["posts"]!!.jsonPrimitive.int)
            assertEquals(1, control()["task"]!!.jsonObject["comments"]!!.jsonArray.count { it.jsonObject["body"]!!.jsonPrimitive.content == "Lost response on real transport" })

            ui.main { workspace.open(id) }
            ui.awaitMain("Latest details not loaded") { !workspace.busy && workspace.detail != null }
            ui.main { workspace.configure(null, false); workspace.comment(id, "Review this stale draft") }
            ui.awaitMain("Offline draft missing") { workspace.state.pending.singleOrNull()?.state == "queued" }
            control("edit")
            ui.main { workspace.configure(client, true) }
            sync()
            ui.awaitMain("Stale draft did not require review") { workspace.state.pending.singleOrNull()?.state == "conflict" }
            assertEquals(posts, control()["posts"]!!.jsonPrimitive.int)
            val operation = workspace.state.pending.single().mutation.operationId
            ui.main { workspace.applyReviewed(operation) }
            ui.awaitMain("Reviewed draft did not apply") { !workspace.busy && workspace.state.pending.isEmpty() }
            assertEquals(0, control()["runs"]!!.jsonPrimitive.int)
        } finally {
            ui.main { workspace.close(); cache.close(); scope.cancel() }
            client.close()
        }
    }
}
