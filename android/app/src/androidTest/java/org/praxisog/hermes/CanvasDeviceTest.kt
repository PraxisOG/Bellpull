package org.praxisog.hermes

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
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
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

/** Reuses the desktop integration conversation; no inference or production app data. */
class CanvasDeviceTest {
    @Test fun sameConversationRoundTripTouchEditReconnectAndUndo() {
        val ui = DeviceFixtureUi
        val app = ApplicationProvider.getApplicationContext<Context>()
        check(app.packageName == "org.praxisog.hermes.canvasfixture")
        val fixture = Json.parseToJsonElement(File(app.filesDir, "canvas-fixture.json").readText()).jsonObject
        val origin = fixture.getValue("url").jsonPrimitive.content
        val token = fixture.getValue("token").jsonPrimitive.content
        val session = fixture.getValue("sessionId").jsonPrimitive.content
        val canvasId = fixture.getValue("canvasId").jsonPrimitive.content
        val delayDrop = AtomicBoolean(false)
        val client = HermesClient(origin, { token }, httpClient = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val delay = delayDrop.get() && chain.request().method == "POST" && chain.request().url.encodedPath.endsWith("/canvas")
            if (delay) Thread.sleep(400)
            val response = chain.proceed(chain.request())
            if (delay) Thread.sleep(400)
            response
        }.build())
        fun <T> ok(value: HermesResult<T>): T = when (value) { is HermesResult.Ok -> value.value; is HermesResult.Err -> error(value.error.toString()) }
        val initial = ok(client.canvas(session))
        val supported = ok(client.capabilities()).features["bellpull_canvas"] == true
        assertTrue("Shared canvas capability must reach the actual app runtime", supported)
        assertEquals(canvasId, initial.document?.id)
        assertEquals(3, initial.document!!.content.nodes.size)
        assertEquals(2, initial.document!!.content.edges.size)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val cache = SessionCache(app, "shared-canvas-device")
        lateinit var workspace: CanvasWorkspace
        val flags = ui.automation.serviceInfo.flags
        ui.automation.serviceInfo = ui.automation.serviceInfo.apply { this.flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        try {
            ui.main { workspace = CanvasWorkspace(scope, client, cache, supported); workspace.visible = true; workspace.observe(session, true) }
            ui.awaitMain("Phone did not receive existing desktop canvas") { workspace.connected && workspace.snapshot?.document?.id == canvasId }
            ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
                scene.onActivity { activity -> activity.setContent { HermesTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    CanvasScreen(workspace, Modifier.fillMaxSize())
                } } } }
                ui.await("Canvas scene missing") { ui.find { it.contentDescription?.toString() == "Drawing canvas" } != null }
                ui.tapText("Fit")
                Thread.sleep(650)
                val bounds = Rect().also(checkNotNull(ui.find { it.contentDescription?.toString() == "Drawing canvas" })::getBoundsInScreen)
                val nodes = initial.document!!.content.nodes
                val minX = nodes.minOf { it.x }; val minY = nodes.minOf { it.y }
                val width = nodes.maxOf { it.x + it.width } - minX; val height = nodes.maxOf { it.y + it.height } - minY
                val zoom = min((bounds.width() - 60) / max(width, 1.0), (bounds.height() - 60) / max(height, 1.0)).coerceIn(.15, 3.0)
                val first = nodes.first()
                val x = bounds.left + (bounds.width() - width * zoom) / 2 + (first.x + first.width / 2 - minX) * zoom
                val y = bounds.top + (bounds.height() - height * zoom) / 2 + (first.y + first.height / 2 - minY) * zoom
                // Verify pixels during an intentionally slow network receipt. The
                // object must stay under the finger and must not replay its move.
                val dragTime = SystemClock.uptimeMillis()
                fun dragEvent(action: Int, atY: Double) {
                    val event = MotionEvent.obtain(dragTime, SystemClock.uptimeMillis(), action, x.toFloat(), atY.toFloat(), 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue(ui.automation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
                val direction = if (bounds.bottom-y >= y-bounds.top) 1 else -1
                val room = if (direction == 1) bounds.bottom-y else y-bounds.top
                val distance = min(330.0, room - first.height * zoom / 2 - 30)
                val droppedY = y + direction * distance
                assertTrue(distance > first.height * zoom / 2 + 45)
                dragEvent(MotionEvent.ACTION_DOWN, y)
                for (step in 1..12) { dragEvent(MotionEvent.ACTION_MOVE, y + direction * distance * step / 12); Thread.sleep(25) }
                Thread.sleep(80)
                val color = when (first.color) { "blue" -> 0xffdfe8f1.toInt(); "rose" -> 0xffead8de.toInt(); "sage" -> 0xffe0eadf.toInt(); "white" -> 0xffffffff.toInt(); else -> 0xffeee7cc.toInt() }
                fun droppedPixels(): Int {
                    val bitmap = checkNotNull(ui.automation.takeScreenshot())
                    return try { var matching = 0
                        for (dx in -30..30 step 3) for (dy in -18..18 step 3) if (bitmap.getPixel(x.toInt() + dx, droppedY.toInt() + dy) == color) matching++
                        matching
                    } finally { bitmap.recycle() }
                }
                val reference = droppedPixels(); assertTrue("Finger did not drag the object", reference > 100)
                delayDrop.set(true); dragEvent(MotionEvent.ACTION_UP, droppedY)
                repeat(12) { assertTrue("Object snapped back while saving", droppedPixels() >= reference * .8); Thread.sleep(70) }
                ui.awaitMain("Drag did not commit") { !workspace.busy && workspace.snapshot?.document?.revision == initial.document!!.revision + 1 }
                delayDrop.set(false)
                ui.tapText("Undo")
                ui.awaitMain("Drag undo failed") { !workspace.busy && workspace.snapshot?.document?.revision == initial.document!!.revision + 2 }
                Thread.sleep(500)
                val down = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue(ui.automation.injectInputEvent(event, true)) } finally { event.recycle() }
                    Thread.sleep(80)
                }
                ui.tapText("Properties")
                ui.await("Object text editor missing") { ui.find { it.isEditable && it.text?.toString()?.contains(first.label) == true } != null }
                checkNotNull(ui.find { it.isEditable && it.text?.toString()?.contains(first.label) == true }).performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Phone edit") })
                ui.tapText("Save")
                ui.awaitMain("Touch edit was not committed") { !workspace.busy && workspace.snapshot?.document?.content?.nodes?.first()?.label == "Phone edit" }
                val afterPhone = ok(client.canvas(session)).document!!
                assertEquals("Phone edit", afterPhone.content.nodes.first().label)
                val updated = ok(client.canvasEdit(session, buildJsonObject {
                    put("action", "edit"); put("operation_id", UUID.randomUUID().toString()); put("canvas_id", canvasId); put("base_revision", afterPhone.revision)
                    put("operations", buildJsonArray { add(buildJsonObject { put("type", "update-node"); put("id", nodes[1].id)
                        put("changes", buildJsonObject { put("label", "Desktop reply"); put("y", nodes[1].y + 90) }) }) })
                })).document!!
                ui.awaitMain("Phone did not receive remote edit over SSE") { workspace.snapshot?.document?.revision == updated.revision }
                ui.screenshot("canvas-phone-roundtrip")
                ui.main { workspace.close(); workspace = CanvasWorkspace(scope, client, cache, true) }
                ui.main { workspace.observe(session, false) }
                ui.main { assertFalse(workspace.connected); assertEquals(updated.revision, workspace.snapshot?.document?.revision); workspace.observe(session, true) }
                ui.awaitMain("Cached conversation did not reconnect") { workspace.connected }
                ui.main { workspace.mutate("undo") }
                ui.awaitMain("Shared undo failed") { !workspace.busy && workspace.snapshot?.document?.revision == updated.revision + 1 }
                assertEquals(nodes[1].label, ok(client.canvas(session)).document!!.content.nodes[1].label)
                ui.main { workspace.mutate("redo") }
                ui.awaitMain("Shared redo failed") { !workspace.busy && workspace.snapshot?.document?.revision == updated.revision + 2 }
                assertEquals("Desktop reply", ok(client.canvas(session)).document!!.content.nodes[1].label)
            }
            // Keep the separate preview connected for manual inspection; primary app is untouched.
            SecureConnectionStore(app).apply { if (active() == null) save(origin, token) }
            ActivityScenario.launch(MainActivity::class.java).use {
                ui.tapText("Shared canvas integration check")
                ui.await("Conversation menu missing") { ui.find { it.packageName?.toString() == app.packageName && it.contentDescription?.toString() == "More options" } != null }
                ui.tap(checkNotNull(ui.find { it.packageName?.toString() == app.packageName && it.contentDescription?.toString() == "More options" }))
                ui.tapText("Canvas")
                ui.await("Canvas is not accessible from the actual conversation screen") { ui.find {
                    it.packageName?.toString() == app.packageName && it.text?.toString()?.startsWith("Shared with this conversation") == true
                } != null }
                fun sceneWidth() = Rect().also(checkNotNull(ui.find { it.contentDescription?.toString() == "Drawing canvas" })::getBoundsInScreen).width()
                val dockWidth = sceneWidth()
                ui.tapText("Full screen")
                ui.await("Full-screen canvas did not expand") { sceneWidth() > dockWidth * 1.5 }
                ui.screenshot("canvas-phone-full-screen")
                ui.tapText("Exit full screen")
                ui.await("Canvas did not return to the side panel") { abs(sceneWidth() - dockWidth) < 10 }
                ui.tapText("Full screen")
                ui.await("Full screen did not reopen") { sceneWidth() > dockWidth * 1.5 }
                ui.automation.executeShellCommand("input keyevent KEYCODE_BACK").close()
                ui.await("Back did not leave full-screen canvas") { abs(sceneWidth() - dockWidth) < 10 }
                ui.screenshot("canvas-conversation-preview")
            }
            File(app.getExternalFilesDir(null), "canvas-device-result.json").writeText(buildJsonObject {
                put("passed", true); put("sessionId", session); put("canvasId", canvasId); put("touchEdit", true); put("remoteEdit", true); put("reconnect", true); put("undoRedo", true)
            }.toString())
        } finally {
            ui.main { workspace.close() }; scope.cancel()
            ui.automation.serviceInfo = ui.automation.serviceInfo.apply { this.flags = flags }
        }
    }
}
