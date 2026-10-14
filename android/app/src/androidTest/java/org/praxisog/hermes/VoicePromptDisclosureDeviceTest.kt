package org.praxisog.hermes

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.model.Message
import java.io.File

/** Public touch APIs on the fixture package; no account, microphone or inference. */
@RunWith(AndroidJUnit4::class)
class VoicePromptDisclosureDeviceTest {
    private val ui = DeviceFixtureUi
    private val words = "Tell me the complete answer, like a podcast."
    private val message = Message(901, role = "user", content = "$VOICE_PRESET\n\n$words")
    private val layout get() = InstrumentationRegistry.getArguments().getString("layout", "unfolded")
    private fun visibleText(value: String) = ui.find { it.text?.toString()?.contains(value) == true }
    private fun screenshot(name: String) {
        ui.automation.waitForIdle(300, 3000)
        val app = ApplicationProvider.getApplicationContext<Context>()
        File(app.filesDir, "voice-disclosure-$layout-$name.png").outputStream().use {
            checkNotNull(ui.automation.takeScreenshot()).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun savedAndPendingVoiceMessagesExpandAndCollapseWithRealTouches() = ui.withWindows {
        for (pending in listOf(false, true)) ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
            scene.onActivity { activity -> activity.setContent { HermesTheme(appearance = Appearance.DARK, palette = Palette.MAROON) {
                Surface(Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.background) {
                    SessionDetailScreen(UiState.Ready(if (pending) emptyList() else listOf(message)), TurnState.Idle,
                        { false }, {}, {}, pendingUserText = message.content.takeIf { pending }, denseChatWindow = true)
                }
            } } }
            ui.await("Voice disclosure") { ui.find { it.contentDescription?.toString() == "Bellpull voice enabled" } != null }
            assertNotNull(ui.text("Bellpull voice enabled ▾"))
            assertNotNull(visibleText(words)); assertNull(visibleText(VOICE_PRESET))
            if (!pending) screenshot("collapsed")
            ui.tapText("Bellpull voice enabled ▾")
            ui.await("Expanded voice instructions") { visibleText(VOICE_PRESET) != null }
            assertNotNull(ui.text("Bellpull voice enabled ▴")); assertNotNull(visibleText(words))
            if (!pending) screenshot("expanded")
            ui.tapText("Bellpull voice enabled ▴")
            ui.await("Instructions collapsed again") { visibleText(VOICE_PRESET) == null }
            assertNotNull(visibleText(words))
        }
    }
    @Test fun editingReceivesOriginalPresetAndWordsAfterDisplayCollapse() = ui.withWindows {
        ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
            scene.onActivity { activity -> activity.setContent { HermesTheme {
                SessionDetailScreen(UiState.Ready(listOf(message)), TurnState.Idle, { false }, {}, {}, editingEnabled = true)
            } } }
            ui.await("User words") { ui.text(words) != null }
            // The merged message node includes the disclosure; press its lower text area.
            val bounds = Rect().also(checkNotNull(ui.text(words))::getBoundsInScreen)
            val start = SystemClock.uptimeMillis()
            fun event(action: Int) = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.top + bounds.height() * .8f, 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            val down = event(MotionEvent.ACTION_DOWN)
            try { assertTrue(ui.automation.injectInputEvent(down, true)) } finally { down.recycle() }
            SystemClock.sleep(800)
            val up = event(MotionEvent.ACTION_UP)
            try { assertTrue(ui.automation.injectInputEvent(up, true)) } finally { up.recycle() }
            ui.await("Edit action") { ui.find { it.contentDescription?.toString() == "Fork & edit" } != null }
            ui.tap(checkNotNull(ui.find { it.contentDescription?.toString() == "Fork & edit" }))
            ui.await("Original editable prompt") { ui.find { it.isEditable && it.text?.toString() == message.content } != null }
            ui.tapText("Cancel")
        }
    }
}
