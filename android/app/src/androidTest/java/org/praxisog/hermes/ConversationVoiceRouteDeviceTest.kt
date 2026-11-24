package org.praxisog.hermes

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test

/** Use the disposable stock gateway on port 18646, with its existing phone_fixture chat. */
class ConversationVoiceRouteDeviceTest {
    @Test fun selectsRouteForOpenChatThroughSettings() = DeviceFixtureUi.withWindows {
        val ui = DeviceFixtureUi
        val app = ApplicationProvider.getApplicationContext<Context>()
        val runtime = BellpullRuntime.get(app)
        ui.main { runtime.disconnect() }
        val secure = SecureConnectionStore(app)
        secure.clear()
        val profile = secure.add("Voice fixture", "http://127.0.0.1:18646", "bellpull-stock-device-fixture")
        secure.activate(profile.id)
        val settings = VoiceSettings(app, profile.id)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                ui.awaitMain("Fixture gateway did not connect") { runtime.store != null }
                ui.main { runtime.store?.openSession("phone_fixture") }
                ui.awaitMain("Chat did not open") { runtime.store?.openSessionId == "phone_fixture" }
                if (ui.text("Settings") == null) ui.tap(checkNotNull(ui.find {
                    it.contentDescription?.toString() in setOf("Back to sessions", "Show sessions")
                }))
                ui.tapText("Settings")
                ui.await("Settings did not open") { ui.text("Servers") != null || ui.text("Recent files") != null }
                var scroll = ui.text("Servers") ?: checkNotNull(ui.text("Recent files"))
                while (!scroll.isScrollable && scroll.parent != null) scroll = scroll.parent
                fun reveal(label: String) {
                    repeat(8) {
                        if (ui.text(label) != null) { ui.tapText(label); return }
                        check(scroll.refresh() && scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                            val labels = mutableListOf<String>()
                            ui.find { node -> node.text?.let { labels.add(it.toString()) }; false }
                            "Cannot scroll settings to $label. Visible: $labels"
                        }
                        ui.automation.waitForIdle(300, 3000)
                        android.os.SystemClock.sleep(200)
                    }
                    error("Could not reveal $label")
                }
                reveal("Voice input")
                reveal("Voice for this chat · Profile defaults")
                ui.tapText("Server with on-device fallback")
                ui.await("Route selection did not update") { ui.text("Voice for this chat · Server with on-device fallback") != null }
                val effective = VoiceSettings(app, profile.id).forConversation("phone_fixture")
                assertTrue(effective.transcribeViaServer); assertTrue(effective.allowSystemOutputFallback)
                assertEquals(ConversationVoiceRoute.PROFILE, settings.route("another_fixture_chat"))
                assertFalse(settings.read().transcribeViaServer)
                ui.tapText("Voice for this chat · Server with on-device fallback"); ui.tapText("Profile defaults")
                ui.awaitMain("Reset did not persist") { settings.route("phone_fixture") == ConversationVoiceRoute.PROFILE }
            }
        } finally { ui.main { runtime.disconnect() }; settings.clear(); secure.clear() }
    }
}
