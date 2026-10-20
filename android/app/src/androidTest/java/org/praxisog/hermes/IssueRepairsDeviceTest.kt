package org.praxisog.hermes

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Synthetic content only; touches use Android input injection, not semantic clicks. */
@RunWith(AndroidJUnit4::class)
class IssueRepairsDeviceTest {
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun await(check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (!check()) {
            check(SystemClock.elapsedRealtime() < end) { "Fixture UI condition timed out" }
            Thread.sleep(60)
        }
    }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        automation.clearCache()
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .firstNotNullOfOrNull { it.root?.let(::visit) }
    }
    private fun text(value: String) = find { it.text?.toString() == value }
    private fun touch(node: AccessibilityNodeInfo, hold: Long = 0) {
        val bounds = Rect().also(node::getBoundsInScreen)
        val start = SystemClock.uptimeMillis()
        fun send(action: Int) {
            val e = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try { assertTrue(automation.injectInputEvent(e, true)) } finally { e.recycle() }
        }
        send(MotionEvent.ACTION_DOWN)
        if (hold > 0) Thread.sleep(hold)
        send(MotionEvent.ACTION_UP)
        automation.waitForIdle(100, 2000)
    }
    private fun fixture(block: () -> Unit) {
        check(ApplicationProvider.getApplicationContext<Context>().packageName == "org.praxisog.hermes.fixture")
        val flags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        try { block() } finally { automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags } }
    }
    @Test fun tallResponseSupportsDisclosureBodyTapAndNativeTextSelection() = fixture {
        ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
            scene.onActivity { activity -> activity.setContent { HermesTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 72.dp)) {
                    MessageBubble(Message(99, role = "assistant", content = "Synthetic response"), true, {}) {
                        Column {
                            Text("Ordinary synthetic prose")
                            ThinkingBlock("Reasoning text for native selection", true)
                            Spacer(Modifier.height(1800.dp))
                        }
                    }
                }
            } } }
            await { text("Thinking ▾") != null }
            touch(text("Thinking ▾")!!)
            await { text("Reasoning text for native selection") != null }
            touch(text("Reasoning text for native selection")!!)
            await { text("Reasoning text for native selection") == null }
            touch(text("Thinking ▾")!!)
            await { text("Reasoning text for native selection") != null }
            touch(text("Reasoning text for native selection")!!, 800)
            await { find { it.text?.toString().equals("Copy", ignoreCase = true) } != null }
            touch(find { it.text?.toString().equals("Copy", ignoreCase = true) }!!)
            await { text("Reasoning text for native selection") != null }
            scene.onActivity { activity ->
                val copied = activity.getSystemService(android.content.ClipboardManager::class.java)
                    .primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                assertTrue(copied.isNotBlank() && "Reasoning text for native selection".contains(copied))
            }
            assertNull(find { it.contentDescription?.toString() == "Copy message" })
            // Dismiss selection by tapping the disclosure, then check ordinary message actions.
            touch(text("Thinking ▴")!!)
            await { text("Reasoning text for native selection") == null }
            touch(text("Ordinary synthetic prose")!!, 800)
            await { find { it.contentDescription?.toString() == "Copy message" } != null }
        }
    }

    @Test fun unfiledPinButtonWorksByTouchAndPersistsAcrossRecreation() = fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val saved = AtomicReference(ProjectSnapshot())
        val posts = AtomicInteger()
        val json = Json { encodeDefaults = true }
        val cache = object : ProjectCache {
            override fun readProjects() = saved.get()
            override fun writeProjects(value: ProjectSnapshot): Boolean { saved.set(value); return true }
        }
        val client = HermesClient("https://fixture.invalid", { "fixture" },
            httpClient = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
                val request = chain.request()
                if (request.method == "POST") {
                    val data = Json.parseToJsonElement(okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
                    assertEquals("pin", data["op"]?.jsonPrimitive?.content ?: data["operation"]?.jsonPrimitive?.content)
                    posts.incrementAndGet()
                    saved.set(ProjectSnapshot(revision = saved.get().revision + 1,
                        memberships = listOf(ProjectMembership("pin-fixture", pinned = data["pinned"]!!.jsonPrimitive.content == "true"))))
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(json.encodeToString(saved.get()).toResponseBody("application/json".toMediaType())).build()
            }.build())
        val workspaces = mutableListOf<ProjectWorkspace>()
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
                fun content() = scene.onActivity { activity ->
                    val workspace = ProjectWorkspace(scope, client, cache, true, true).also { workspaces += it; it.refresh() }
                    activity.setContent { HermesTheme {
                        var organize by remember { mutableStateOf(false) }
                        val chat = Session("pin-fixture", title = "Synthetic pin chat")
                        Column(Modifier.padding(top = 72.dp)) {
                            SessionRow(chat, 0.0, false, pinned = "pin-fixture" in workspace.pinnedIds(listOf(chat)),
                                onOrganize = { organize = true }, onClick = {})
                        }
                        if (organize) ProjectChatDialog(workspace, chat) { organize = false }
                    } }
                }
                content()
                await { find { it.contentDescription?.toString() == "Organize Synthetic pin chat" } != null }
                touch(find { it.contentDescription?.toString() == "Organize Synthetic pin chat" }!!)
                await { text("Pin conversation")?.isEnabled == true && workspaces.last().canPin("pin-fixture") }
                touch(text("Pin conversation")!!)
                await { text("Unpin conversation") != null && posts.get() == 1 }
                touch(text("Close")!!)
                await { text("Pinned") != null }
                scene.recreate()
                content()
                await { text("Pinned") != null }
                touch(find { it.contentDescription?.toString() == "Organize Synthetic pin chat" }!!)
                await { text("Unpin conversation") != null && workspaces.last().canPin("pin-fixture") }
                touch(text("Unpin conversation")!!)
                await { text("Pin conversation") != null && posts.get() == 2 }
                touch(text("Close")!!)
                await { text("Pinned") == null }
            }
        } finally { workspaces.forEach { it.close() }; scope.cancel(); client.close() }
    }
}
