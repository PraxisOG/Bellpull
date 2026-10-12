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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
        // Dynamic Compose forms reuse virtual nodes; cached text/bounds can target a different control.
        if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache()
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
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        automation.waitForIdle(150, 3000)
        assertTrue("Touch target must still exist after scrolling", node.refresh())
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
        assertEquals("0.5.2", app.name)
        assertEquals(14L, app.code)
        ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
            scene.onActivity { activity -> activity.setContent { HermesTheme {
                AboutDialog(app, listOf(ServerPluginVersion("Current fixture", "https://current.invalid", "0.3.0", 0.0),
                    ServerPluginVersion("Old fixture", "https://old.invalid", "0.2.2", 0.0)), {})
            } } }
            for (label in listOf("Installed app: ${app.name} (build ${app.code})", "Bellpull plugin: 0.3.0",
                "Meets this app's minimum plugin version (0.3.0).", "Update needed. This app requires plugin 0.3.0 or newer.")) {
                await { text(label) != null }
                text(label)!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            }
        }
    }
    @Test fun missingSpeechModelCannotStartVoice() = withWindows {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val lab = SpeechLab(context) { _, _ -> error("Missing models must never enter native code") }
        check(!lab.files.installed(lab.selected)) { "Run this regression in a fresh fixture sandbox without models" }
        val starts = java.util.concurrent.atomic.AtomicInteger()
        ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
            scene.onActivity { activity -> activity.setContent { HermesTheme {
                VoicePanel(null, VoiceConfiguration(), null, {}, { starts.incrementAndGet() }, {}, speechLab = lab)
            } } }
            for (mode in listOf("Hands-free", "Review before sending", "Push to talk")) {
                await { text(mode) != null }
                tap(text(mode)!!)
                await { text("Start voice") != null }
                // Android exposes the text child independently of Compose's disabled semantics.
                // JVM coverage checks those semantics; real touch here must never dispatch Start.
                tap(text("Start voice")!!)
                automation.waitForIdle(150, 3000)
                assertEquals(0, starts.get())
            }
            await { text("Download ${lab.selected.label} in Settings → Voice → Experimental first.") != null }
            assertNull(lab.runtime.loadedModel)
            assertNotNull(text("Voice settings"))
        }
    }
    @Test fun localSpeechSettingsHideRemoteFieldsAndSwitchInstalledModels() = withWindows {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val lab = SpeechLab(context)
        val saved = AtomicReference<VoiceConfiguration>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
            try {
                scene.onActivity { activity -> activity.setContent { HermesTheme { Surface {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        VoiceSettingsEditor(VoiceConfiguration(), true, saved::set, lab)
                    }
                } } } }
                await { text("On device") != null && text(lab.selected.label) != null }
                assertNull(text("Full transcription URL"))
                tap(text(lab.selected.label)!!)
                val small = lab.models.single { it.id == "moonshine-small" }
                val item = "${small.label} · ${formatSpeech(small.downloadBytes / 1_000_000.0)} MB"
                await { text(item) != null }; tap(text(item)!!)
                // Allow checksums/native loading without blocking the app thread.
                val deadline = SystemClock.elapsedRealtime() + 60_000
                while (true) {
                    var done = false
                    scene.onActivity { done = !lab.busy && lab.selectedId == small.id }
                    if (done) break
                    check(SystemClock.elapsedRealtime() < deadline) { "Model selection did not complete" }
                    Thread.sleep(50)
                }
                await { text("Actual backend: CPU (ONNX Runtime)") != null }
                tap(text("Private server")!!)
                await { text("Full transcription URL") != null }
                tap(text("On device")!!)
                await { text("Local speech models") != null }
                assertNull(text("Full transcription URL"))
                // Compose omits fully offscreen children from Android accessibility.
                var scrolls = 0
                while (text("Save voice settings") == null && scrolls++ < 10) {
                    val scroll = find { node -> node.isScrollable && node.actionList.any {
                        it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    } }
                    assertNotNull("Voice settings must be scrollable", scroll)
                    assertTrue(scroll!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                    Thread.sleep(150)
                }
                await { text("Save voice settings") != null }
                text("Save voice settings")!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                await {
                    val bounds = Rect().also { text("Save voice settings")?.getBoundsInScreen(it) }
                    !bounds.isEmpty && bounds.top >= 0 && bounds.bottom <= context.resources.displayMetrics.heightPixels
                }
                tap(text("Save voice settings")!!)
                await { saved.get() != null }
                assertEquals(VoiceRecognition.LOCAL, saved.get().recognition)
                assertTrue(saved.get().endpoint.isBlank())
            } catch (failure: Throwable) {
                automation.takeScreenshot()?.let { bitmap ->
                    java.io.File(context.filesDir, "local-settings-failure.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                val nodes = StringBuilder()
                fun dump(node: AccessibilityNodeInfo) {
                    val bounds = Rect().also(node::getBoundsInScreen)
                    nodes.append("${node.text} / ${node.contentDescription}: $bounds; selected=${node.isSelected}; enabled=${node.isEnabled}; visible=${node.isVisibleToUser}\n")
                    for (i in 0 until node.childCount) node.getChild(i)?.let(::dump)
                }
                automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.forEach { it.root?.let(::dump) }
                java.io.File(context.filesDir, "local-settings-failure.txt").writeText(nodes.toString())
                throw failure
            } finally { lab.runtime.close() }
        }
    }
    @Test fun drawerFiltersAreReachableAndCompactRowsDoNotShowSourceBadges() = withWindows {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
        val client = org.praxisog.hermes.core.HermesClient("https://fixture.invalid", { "fixture" })
        val project = ConversationProject("a".repeat(32), "Costumes")
        val workspace = ProjectWorkspace(scope, client, object : ProjectCache {
            override fun readProjects() = org.praxisog.hermes.model.ProjectSnapshot(projects = listOf(project))
        }, false)
        val source = AtomicReference<String>()
        fun capture(name: String) {
            SystemClock.sleep(500)
            val bitmap = automation.takeScreenshot()!!
            java.io.File(context.getExternalFilesDir(null), name).outputStream().use {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        }
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
                scene.onActivity { activity -> activity.setContent { HermesTheme {
                    androidx.compose.foundation.layout.BoxWithConstraints(androidx.compose.ui.Modifier.safeDrawingPadding()) {
                        val narrow = maxWidth < androidx.compose.ui.unit.Dp(720f)
                        androidx.compose.foundation.layout.Box(if (narrow) androidx.compose.ui.Modifier else
                            androidx.compose.ui.Modifier.width(androidx.compose.ui.unit.Dp(240f))) {
                            SessionListScreen(UiState.Ready(listOf(org.praxisog.hermes.model.Session("chat",
                                title = "WALL·E shoulder wiring and battery layout", source = "api_server"))), 0.0, null,
                                {}, {}, {}, titleOnly = true, showNewChatButton = narrow, onExpandedSearch = {},
                                onSourceChange = source::set,
                                projectFilters = { close -> ProjectFilterItems(workspace, emptySet(), emptySet(), { workspace.choose(it); close() }, close) },
                                onOrganize = {})
                        }
                    }
                } } }
                await { find { it.contentDescription?.toString() == "Filter conversations" } != null }
                assertNull(text("API")); assertNull(text("CLI")); assertNull(text("TUI"))
                capture("drawer-native.png")
                val filter = find { it.contentDescription?.toString() == "Filter conversations" }!!
                val search = find { it.contentDescription?.toString() == "Search conversations" }!!
                val filterBounds = Rect().also(filter::getBoundsInScreen)
                val searchBounds = Rect().also(search::getBoundsInScreen)
                assertTrue(filterBounds.centerX() < searchBounds.centerX())
                tap(filter)
                await { text("CLI chats") != null }
                for (label in listOf("Projects", "Costumes", "Unfiled", "API chats", "CLI chats", "TUI chats")) assertNotNull(label, text(label))
                capture("filters-native.png")
                tap(text("CLI chats")!!); await { source.get() == "cli" }
                await { text("Projects") == null }
            }
        } finally { workspace.close(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); client.close() }
    }

}
