package org.praxisog.hermes

import android.content.Context
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import java.net.HttpURLConnection
import java.net.URI

/** Requires a fresh tools/coding-device-fixture.py and reverse TCP 18648. */
@RunWith(AndroidJUnit4::class)
class CodingActivityDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val origin = "http://127.0.0.1:18648"
    private val key = "bellpull-coding-device-fixture"
    private fun control(action: String? = null): JsonObject {
        val request = URI(origin + "/fixture/coding" + (action?.let { "/$it" } ?: "")).toURL().openConnection() as HttpURLConnection
        request.connectTimeout = 5000; request.readTimeout = 5000
        request.setRequestProperty("Authorization", "Bearer $key")
        if (action != null) { request.requestMethod = "POST"; request.doOutput = true; request.outputStream.close() }
        return try {
            assertEquals(200, request.responseCode)
            request.inputStream.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        } finally { request.disconnect() }
    }
    private fun withCache(id: String, block: (SessionCache) -> Unit) {
        val cache = SessionCache(app, id)
        try { block(cache) } finally { cache.close() }
    }
    @Test fun liveChildrenReconnectDraftRestartAndCompressionNeverReadmitTheRun() = DeviceFixtureUi.withWindows {
        val ui = DeviceFixtureUi
        val runtime = BellpullRuntime.get(app)
        ui.main { runtime.disconnect() }
        val secure = SecureConnectionStore(app)
        secure.clear()
        val profile = secure.add("OpenCode device fixture", origin, key)
        secure.activate(profile.id)
        val client = HermesClient(origin, { key })
        val capabilities = (client.capabilities() as HermesResult.Ok).value
        assertTrue(capabilities.features["bellpull_coding_activity"] == true)
        assertTrue(capabilities.features["bellpull_planning"] == true)
        withCache(profile.id) { cache ->
            cache.writeCapabilities(capabilities.raw.toString())
            cache.rememberSession("phone_coding")
        }
        assertEquals(0, control()["admissions"]!!.jsonPrimitive.int)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scene ->
                ui.awaitMain("Fixture chat did not load") { runtime.store?.openSessionId == "phone_coding" && runtime.store?.history is UiState.Ready }
                ui.await("Chat navigation did not render") { ui.text("Activity") != null || ui.text("OpenCode device check") != null }
                if (ui.text("Activity") == null) ui.tapText("OpenCode device check")
                ui.main { runtime.store!!.send("Run the deterministic coding device fixture.") }
                ui.tapText("Activity")
                ui.awaitMain("Native OpenCode children were not observed") { runtime.coding?.snapshot?.works?.size == 2 && runtime.coding?.snapshot?.rows?.size == 2 }
                ui.main {
                    assertTrue(runtime.coding!!.snapshot!!.works.all { it.state == "running" })
                    assertEquals(setOf("cli_json", "terminal_text"), runtime.coding!!.snapshot!!.works.map { it.transport }.toSet())
                    runtime.store!!.updateDraft(TextFieldValue("Preserve this follow-up draft"))
                }
                ui.tapText("▸ OpenCode reasoning")
                ui.screenshot("phase6-after-reasoning-tap")
                ui.await("Reasoning expansion failed") { ui.text("Checking the deterministic fixture workspace.") != null }
                ui.screenshot("phase6-live")
                val work = runtime.coding!!.snapshot!!.works.first { it.transport == "cli_json" }
                ui.main { runtime.coding!!.inspect(work.id) }
                ui.awaitMain("Workspace files did not load") { runtime.coding?.workspace?.files?.any { it.path == "example.txt" } == true }
                ui.tapText("example.txt")
                ui.screenshot("phase6-after-file-tap")
                ui.awaitMain("Read-only diff did not load") { if (runtime.coding?.workspaceProblem != null) throw AssertionError(runtime.coding?.workspaceProblem)
                    runtime.coding?.workspace?.diff?.contains("+After fixture edit") == true }
                ui.screenshot("phase6-diff")
                ui.tapText("Close")
                // Exercise both destinations through the same application-owned runtime/client.
                ui.await("Workspace navigation did not return after closing the diff") {
                    ui.text("Today") != null || ui.find { it.contentDescription?.toString() == "Back to sessions" } != null
                }
                if (ui.text("Today") == null) ui.tap(checkNotNull(ui.find { it.contentDescription?.toString() == "Back to sessions" }))
                ui.tapText("Today")
                ui.awaitMain("Combined planning did not load") { runtime.planning?.snapshot != null && runtime.planning?.loading == false }
                ui.main { runtime.planning!!.action("reminders",buildJsonObject {
                    put("operation_id",java.util.UUID.randomUUID().toString().replace("-",""))
                    put("action","create"); put("title","Review ongoing OpenCode work")
                    put("due_ms",System.currentTimeMillis()+3600000)
                }) }
                ui.awaitMain("Reminder mutation interrupted the coding run") {
                    runtime.planning?.snapshot?.reminders?.any { it.title=="Review ongoing OpenCode work" } == true &&
                        runtime.store?.runs?.states?.get("phone_coding")?.busy == true
                }
                ui.screenshot("combined-today-during-coding")
                ui.await("Chat navigation did not render from Today") {
                    ui.text("Chats") != null || ui.find { it.contentDescription?.toString() == "Back to sessions" } != null
                }
                if (ui.text("Chats") == null) ui.tap(checkNotNull(ui.find { it.contentDescription?.toString() == "Back to sessions" }))
                ui.tapText("Chats")
                ui.awaitMain("Activity or draft did not survive Today navigation") {
                    runtime.coding?.snapshot?.works?.size == 2 && runtime.store?.draft?.text=="Preserve this follow-up draft"
                }
                ui.await("Activity did not return") { ui.text("OpenCode · Running") != null }
                assertEquals(1,control()["admissions"]!!.jsonPrimitive.int)
                // A background/foreground transition must resume observation, not admission.
                scene.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                control("advance")
                scene.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                ui.awaitMain("Foreground did not catch up") { runtime.coding?.snapshot?.rows?.any { it.detail == "Device output step 1" } == true }
                control("offline")
                ui.awaitMain("Disconnected activity was not identified") { runtime.coding?.disconnected == true }
                ui.await("Saved-state warning missing") { ui.find { it.text?.contains("Disconnected · showing saved activity") == true } != null }
                ui.screenshot("phase6-disconnected")
                scene.recreate()
                ui.awaitMain("Draft was lost during Activity recreation") { runtime.store?.draft?.text == "Preserve this follow-up draft" }
                control("online")
                ui.awaitMain("Activity did not reconnect automatically") { runtime.coding?.disconnected == false }
                ui.main { runtime.store!!.openSession("phone_coding_other") }
                ui.awaitMain("Other conversation did not open") { runtime.store?.openSessionId == "phone_coding_other" && runtime.store?.history is UiState.Ready }
                ui.tapText("Activity")
                ui.awaitMain("Other chat was not isolated") { runtime.coding?.sessionId == "phone_coding_other" && runtime.coding?.snapshot?.works?.isEmpty() == true }
                ui.main { runtime.store!!.openSession("phone_coding") }
                ui.awaitMain("Original conversation did not open") { runtime.store?.openSessionId == "phone_coding" && runtime.store?.history is UiState.Ready }
                ui.tapText("Activity")
                ui.awaitMain("Original activity/draft did not return") { runtime.coding?.snapshot?.works?.size == 2 && runtime.store?.draft?.text == "Preserve this follow-up draft" }
                control("advance")
                ui.awaitMain("Child exits were not observed") { runtime.coding?.snapshot?.works?.all { it.state == "completed" } == true }
                // Children can finish while the coordinating run remains active.
                ui.main { assertTrue(runtime.store!!.runs!!.states["phone_coding"]!!.busy) }
                control("finish")
                ui.awaitMain("Final history did not reconcile") { runtime.store?.runs?.states?.get("phone_coding")?.busy == false }
                assertEquals(1, control()["admissions"]!!.jsonPrimitive.int)
                ui.main { assertEquals(runtime.coding!!.snapshot!!.rows.size, runtime.coding!!.snapshot!!.rows.map { it.workId to it.key }.distinct().size) }
            }
            // Reopen encrypted cache after disconnect, independent of the application singleton.
            ui.main { runtime.disconnect() }
            withCache(profile.id) { cache ->
                assertEquals("Preserve this follow-up draft", cache.readDrafts()["phone_coding"]!!.text)
                assertEquals(2, cache.readCoding("phone_coding")!!.works.size)
            }
            withCache("different-coding-device-profile") { assertNull(it.readCoding("phone_coding")) }
            control("compact")
            val tip = (client.codingActivity("phone_coding_tip") as HermesResult.Ok).value
            assertEquals(2, tip.works.size)
            assertTrue((client.codingActivity("phone_coding_branch") as HermesResult.Ok).value.works.isEmpty())
            assertEquals(1, control()["admissions"]!!.jsonPrimitive.int)
        } finally {
            control("online"); control("finish")
            ui.main { runtime.disconnect() }
            client.close()
        }
    }
}
