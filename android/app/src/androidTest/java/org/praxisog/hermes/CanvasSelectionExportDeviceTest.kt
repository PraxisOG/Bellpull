package org.praxisog.hermes

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import java.io.File
import java.util.UUID

/** A real stock device fixture on 18646 with its synthetic phone_fixture conversation. */
class CanvasSelectionExportDeviceTest {
    @Test fun selectsInChatRejectsStaleRevisionAndExportsBothFormats() = DeviceFixtureUi.withWindows {
        val ui = DeviceFixtureUi
        val app = ApplicationProvider.getApplicationContext<Context>()
        val runtime = BellpullRuntime.get(app)
        ui.main { runtime.disconnect() }
        val secure = SecureConnectionStore(app)
        val origin = "http://127.0.0.1:18646"
        val token = "bellpull-stock-device-fixture"
        secure.clear(); secure.activate(secure.add("Canvas fixture", origin, token).id)
        val api = HermesClient(origin, { token })
        val chat = "phone_fixture"
        val canvasId = UUID.randomUUID().toString()
        val name = "Mobile-drawing-" + canvasId.take(8)
        fun edit(action: String, fields: JsonObjectBuilder.() -> Unit): CanvasSnapshot {
            val result = api.canvasEdit(chat, buildJsonObject {
                put("action", action); put("canvas_id", canvasId); put("operation_id", UUID.randomUUID().toString()); fields()
            })
            return (result as HermesResult.Ok).value
        }
        try {
            val created = edit("create") { put("name", name) }
            val node = CanvasNode("box", "rectangle", "Selected & shared", 0.0, 0.0, 180.0, 100.0, "rose")
            val populated = edit("edit") {
                put("base_revision", created.document!!.revision)
                put("operations", buildJsonArray { add(buildJsonObject { put("type", "add-node"); put("node", Json.encodeToJsonElement(node)) }) })
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                ui.awaitMain("Gateway did not connect") { runtime.canvas?.supported == true }
                val title = "Stock file sharing fixture"
                ui.awaitMain("Server did not list the chat") {
                    (runtime.store?.sessions as? UiState.Ready)?.value?.any { it.id == chat } == true
                }
                if (ui.text(title) == null) {
                    ui.find { it.contentDescription?.toString() in setOf("Back to sessions", "Show sessions") }?.let(ui::tap)
                }
                ui.tapText(title)
                ui.awaitMain("Chat did not open") { runtime.store?.openSessionId == chat }
                ui.tap(checkNotNull(ui.find { it.contentDescription?.toString() == "More options" }))
                ui.tapText("Canvas")
                val canvas = checkNotNull(runtime.canvas)
                ui.awaitMain("The chat canvas never connected") { canvas.connected && canvas.snapshot?.document?.id == canvasId }
                fun scene() = checkNotNull(ui.find { it.contentDescription?.toString() == "Drawing canvas" })
                ui.tapText("Fit")
                ui.automation.waitForIdle(350, 3000)
                ui.screenshot("canvas-export-main")
                ui.tap(scene())
                ui.awaitMain("The tapped box was not selected") { canvas.selection?.nodes == listOf(node.id) }
                ui.tapText("Close")
                ui.await("Selection lost when closing Canvas") { ui.text("Canvas selection · 1") != null }
                ui.main { assertEquals("Move this box", canvasVisibleText(canvas.prepare(chat, "Move this box")!!)) }
                edit("edit") {
                    put("base_revision", populated.document!!.revision)
                    put("operations", buildJsonArray { add(buildJsonObject {
                        put("type", "update-node"); put("id", node.id); put("changes", buildJsonObject { put("color", "blue") })
                    }) })
                }
                ui.await("Closed panel lost live updates") { ui.text("Review changed or disconnected canvas") != null }
                val draft = "Move this box"
                val field = checkNotNull(ui.find { it.isEditable })
                assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, draft)
                }))
                ui.tapText("Send")
                ui.main {
                    assertNull(canvas.prepare(chat, draft))
                    assertEquals(draft, runtime.store!!.drafts[chat].text)
                    assertTrue(runtime.store!!.turn is TurnState.Idle)
                }
                ui.tapText("Review changed or disconnected canvas")
                ui.tapText("Fit")
                ui.automation.waitForIdle(350, 3000)
                ui.screenshot("canvas-export-main")
                ui.tap(scene())
                ui.awaitMain("Reselect did not update the revision") { canvas.selectionReady }
                for (ext in listOf("svg", "png")) {
                    ui.tapText("Export"); ui.tapText("Upload " + ext.uppercase() + " to conversation")
                    var artifact: Artifact? = null
                    ui.await("Export did not reach the conversation library") {
                        artifact = (api.artifacts(chat) as? HermesResult.Ok)?.value?.data?.find { it.name == "$name.$ext" }
                        artifact != null
                    }
                    val item = checkNotNull(artifact)
                    assertEquals(chat, item.sessionId)
                    val copy = File.createTempFile("export", ".$ext", app.cacheDir)
                    try {
                        assertTrue(api.downloadArtifact(item, copy) {} is HermesResult.Ok)
                        if (ext == "png") assertTrue(BitmapFactory.decodeFile(copy.absolutePath).width > 0)
                        else assertTrue(copy.readText().contains("Selected &amp; shared"))
                    } finally { copy.delete() }
                    ui.tapText("Export"); ui.tapText("Save " + ext.uppercase() + "…")
                    fun saveButton() = ui.find { n ->
                        n.packageName?.toString()?.endsWith("documentsui") == true && n.text?.toString()?.equals("Save", true) == true
                    }
                    ui.await("Android's save picker did not open") { saveButton() != null }
                    ui.tap(checkNotNull(saveButton()))
                    // The disposable emulator starts DocumentsUI in Downloads.
                    val path = "/sdcard/Download/$name.$ext"
                    fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
                        ui.automation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
                    try {
                        ui.await("Downloaded bytes differ from the uploaded drawing") {
                            shell("sha256sum $path").substringBefore(' ') == item.sha256
                        }
                    } finally { shell("rm -f $path") }
                }
                ui.tapText("Close"); ui.tapText("Clear selection")
                ui.main { assertEquals("Done", canvas.prepare(chat, "Done")) }
            }
        } finally { api.close(); ui.main { runtime.disconnect() }; secure.clear() }
    }
}
