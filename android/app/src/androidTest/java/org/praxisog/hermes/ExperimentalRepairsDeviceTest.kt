package org.praxisog.hermes

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Public API 37 input APIs, scoped exclusively to the disposable fixture application. */
@RunWith(AndroidJUnit4::class)
class ExperimentalRepairsDeviceTest {
    private val ui = DeviceFixtureUi
    private val layout get() = InstrumentationRegistry.getArguments().getString("layout", "unfolded")
    private fun tapSwitch(label: String) {
        ui.await("Missing switch: $label") { ui.find { it.contentDescription?.toString() == label } != null }
        repeat(10) {
            val node = checkNotNull(ui.find { it.contentDescription?.toString() == label })
            val bounds = Rect().also(node::getBoundsInScreen)
            var parent = node.parent
            while (parent != null && !parent.isScrollable) parent = parent.parent
            val viewport = parent?.let { Rect().also(it::getBoundsInScreen) }
            if (viewport == null || bounds.top >= viewport.top && bounds.bottom <= viewport.bottom) { ui.tap(node); return }
            check(parent!!.performAction(if (bounds.bottom > viewport.bottom)
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
            ui.automation.waitForIdle(300, 3000)
        }
        error("Switch remains clipped: $label")
    }
    @Test fun directAboutAndIndependentAnnouncementSwitches() = ui.withWindows {
        var config by mutableStateOf(VoiceConfiguration())
        ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
            scene.onActivity { activity -> activity.setContent { HermesTheme {
                SettingsWorkspace(BellpullPreferences(), {}, voiceAnnouncementSettings = {
                    VoiceAnnouncementSettings(config, true) { config = it }
                })
            } } }
            ui.tapText("About")
            ui.await("Direct About dialog") { ui.text("About Bellpull") != null }
            ui.screenshot("repairs-about-$layout")
            ui.tapText("Close")
            ui.tapText("Voice announcements")
            for (label in listOf("Tool use announcements", "Web search announcements", "Thinking announcements", "Working announcements"))
                tapSwitch(label)
            ui.awaitMain("Independent switches") { config.announcements == VoiceAnnouncements(false, false, false, false) }
            tapSwitch("Web search announcements")
            ui.awaitMain("Search independent of tools") { !config.announcements.tools && config.announcements.webSearch }
            ui.screenshot("repairs-announcements-$layout")
        }
    }
    @Test fun acceptedSubmissionClosesComposerFocusAndKeepsMultilineDraft() = ui.withWindows {
        var draft by mutableStateOf(TextFieldValue())
        var accepted = false; var sends = 0
        lateinit var activity: ComponentActivity
        ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
            scene.onActivity { current -> activity = current; current.setContent { HermesTheme {
                SessionDetailScreen(UiState.Ready(emptyList()), TurnState.Idle,
                    onSend = { sends++; accepted }, onStop = {}, onRetry = {}, draft = draft, onDraftChange = { draft = it })
            } } }
            ui.await("Composer") { ui.find { it.isEditable } != null }
            val field = checkNotNull(ui.find { it.isEditable }); ui.tap(field)
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "First line\nSecond line")
            }))
            ui.awaitMain("Keyboard opened") { activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
            ui.tapText("Send")
            ui.await("Rejected send preserves focus") { ui.find { it.isEditable }?.isFocused == true }
            ui.main { accepted = true }
            ui.tapText("Send")
            ui.await("Accepted send clears focus") { ui.find { it.isEditable }?.isFocused == false }
            ui.awaitMain("Accepted send hides keyboard") { activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true }
            ui.main { assertEquals(2, sends); assertEquals("First line\nSecond line", draft.text) }
            ui.screenshot("repairs-composer-$layout")
            ui.tap(checkNotNull(ui.find { it.isEditable }))
            ui.awaitMain("Deliberate tap reopens keyboard") { activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
        }
    }
    @Test fun encryptedAnnouncementChoicesSurviveReloadAndStayWithinProfile() {
        ui.guard()
        val app = ApplicationProvider.getApplicationContext<Context>()
        val one = VoiceSettings(app, "repairs-one"); val two = VoiceSettings(app, "repairs-two")
        try {
            one.save(VoiceConfiguration(announcements = VoiceAnnouncements(false, true, false, true)))
            assertEquals(VoiceAnnouncements(false, true, false, true), VoiceSettings(app, "repairs-one").read().announcements)
            assertEquals(VoiceAnnouncements(), two.read().announcements)
        } finally { one.clear(); two.clear() }
    }
}
