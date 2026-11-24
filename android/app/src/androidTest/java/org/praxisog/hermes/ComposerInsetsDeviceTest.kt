package org.praxisog.hermes

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.HermesResult
import kotlin.math.abs
import kotlin.math.roundToInt

/** Run against tools/stock-device-fixture.py --projects --port 18665 with adb reverse. */
class ComposerInsetsDeviceTest {
    private val ui = DeviceFixtureUi
    @Test fun backAndSendRestoreComposerInBothWindowModes() = ui.withWindows {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = BellpullSettings(context)
        val original = settings.value
        val secure = SecureConnectionStore(context)
        val runtime = BellpullRuntime.get(context)
        ui.main { runtime.disconnect() }
        secure.clear()
        val origin = "http://127.0.0.1:18665"
        val token = "bellpull-stock-device-fixture"
        secure.activate(secure.add("Composer fixture", origin, token).id)
        val client = HermesClient(origin, { token })
        try {
            var immersiveBottom: Int? = null
            for (immersive in listOf(true, false)) {
                settings.update(original.copy(immersiveMode = immersive))
                val chat = (client.createSession() as HermesResult.Ok).value
                ActivityScenario.launch(MainActivity::class.java).use { scene ->
                    lateinit var activity: MainActivity
                    scene.onActivity { activity = it }
                    ui.awaitMain("Fixture disconnected") { runtime.store != null }
                    ui.main { runtime.store!!.openSession(chat.id) }
                    ui.await("Chat composer missing") { ui.text("Send") != null && ui.find { it.isEditable } != null }
                    ui.awaitMain("Fixture window never gained focus") { activity.hasWindowFocus() }
                    fun sendBounds() = Rect().also(checkNotNull(ui.text("Send"))::getBoundsInScreen)
                    fun imeVisible() = activity.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.ime())
                    ui.automation.waitForIdle(350, 3000)
                    val resting = sendBounds()
                    val tolerance = (4 * context.resources.displayMetrics.density).roundToInt()
                    if (immersive) immersiveBottom = resting.bottom
                    else assertTrue("Resting composer differs between modes", abs(resting.bottom - checkNotNull(immersiveBottom)) <= tolerance)
                    fun showKeyboard() {
                        ui.tap(checkNotNull(ui.find { it.isEditable }))
                        ui.awaitMain("IME did not open") { imeVisible() }
                        ui.await("Send did not rise above keyboard") {
                            val ime = ui.automation.windows.firstOrNull {
                                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
                            } ?: return@await false
                            val top = Rect().also(ime::getBoundsInScreen).top
                            sendBounds().bottom <= top && sendBounds().bottom < resting.bottom - tolerance
                        }
                    }
                    fun assertRestored() {
                        ui.awaitMain("IME did not hide") { !imeVisible() }
                        ui.await("Send stranded after IME dismissal (immersive=$immersive, originally $resting)") {
                            ui.text("Send") != null && abs(sendBounds().bottom - resting.bottom) <= tolerance
                        }
                    }
                    showKeyboard()
                    ui.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    assertRestored()
                    showKeyboard()
                    val field = checkNotNull(ui.find { it.isEditable })
                    assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Fixture turn")
                    }))
                    ui.tapText("Send")
                    assertRestored()
                    assertEquals(chat.id, runtime.store!!.openSessionId)
                }
            }
        } finally {
            client.close()
            settings.update(original)
            ui.main { runtime.disconnect() }
            secure.clear()
        }
    }
}
