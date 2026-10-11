package org.praxisog.hermes

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.model.ConversationProject
import java.util.concurrent.atomic.AtomicReference

/** Public Android accessibility/touch APIs: Espresso's legacy InputManager hook fails on API 37. */
@RunWith(AndroidJUnit4::class)
class ProjectsUiDeviceTest {
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun guard() {
        check(ApplicationProvider.getApplicationContext<Context>().packageName == "org.praxisog.hermes.fixture")
    }
    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (!check()) {
            if (SystemClock.elapsedRealtime() >= deadline) throw AssertionError("Fixture UI condition timed out")
            Thread.sleep(50)
        }
    }
    private fun find(check: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (check(node)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .firstNotNullOfOrNull { window -> window.root?.let { visit(it) } }
    }
    private fun text(value: String) = find { it.text?.toString() == value }
    private fun tap(node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
    }
    private fun withWindows(block: () -> Unit) {
        guard()
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        try { block() } finally { automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags } }
    }

    @Test fun editorSaveCanBeTouchedWhileTheKeyboardIsOpen() = withWindows {
        val saved = AtomicReference<ConversationProject>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
            scene.onActivity { activity -> activity.setContent { HermesTheme {
                ProjectEditor(ConversationProject("a".repeat(32), "Before"), true, true, null, saved::set, {})
            } } }
            await { find { it.isEditable } != null }
            val editor = find { it.isEditable }!!
            tap(editor)
            assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Keyboard acceptance") }))
            await { automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
            await {
                val keyboard = automation.windows.first { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                val keyboardBounds = Rect().also(keyboard::getBoundsInScreen)
                val saveBounds = Rect().also { text("Save")?.getBoundsInScreen(it) }
                !saveBounds.isEmpty && saveBounds.bottom <= keyboardBounds.top
            }
            // Real touch proves the button is reachable, unlike a semantics-only click.
            tap(text("Save")!!)
            await { saved.get() != null }
            assertEquals("Keyboard acceptance", saved.get().name)
        }
    }

    @Test fun aboutShowsInstalledVersionAndDistinctPluginStatuses() = withWindows {
        val app = installedAppVersion(ApplicationProvider.getApplicationContext())
        assertEquals("0.4.0", app.name)
        assertEquals(11L, app.code)
        ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
            scene.onActivity { activity -> activity.setContent { HermesTheme {
                AboutDialog(app, listOf(ServerPluginVersion("Current fixture", "https://current.invalid", "0.3.0", 0.0),
                    ServerPluginVersion("Old fixture", "https://old.invalid", "0.2.2", 0.0)), {})
            } } }
            for (label in listOf("Installed app: 0.4.0 (build 11)", "Bellpull plugin: 0.3.0",
                "Meets this app's minimum plugin version (0.3.0).", "Update needed. This app requires plugin 0.3.0 or newer.")) {
                await { text(label) != null }
                text(label)!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            }
        }
    }
}
