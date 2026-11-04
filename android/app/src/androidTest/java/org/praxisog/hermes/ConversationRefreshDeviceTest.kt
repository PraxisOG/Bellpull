package org.praxisog.hermes

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.core.HermesClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real swipes through the app/store with a held synthetic HTTP response; no server account. */
@RunWith(AndroidJUnit4::class)
class ConversationRefreshDeviceTest {
    private val ui = DeviceFixtureUi
    @Test fun pullShowsProgressAndNewConversationsWithoutDuplicateRequests() = ui.withWindows {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val http = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val count = requests.incrementAndGet()
            if (count == 2) check(release.await(25, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"data":[{"id":"chat","title":"${if (count == 1) "Saved conversation" else "Refreshed conversation"}","source":"cli"}]}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = HermesClient("http://fixture.invalid", { "fixture" }, httpClient = http)
        val store = HermesStore(scope, client, sessionSource = "cli")
        try {
            ui.main { store.loadSessions() }
            ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
                scene.onActivity { activity -> activity.setContent { HermesTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    HermesApp(store.sessions, UiState.Ready(emptyList()), TurnState.Idle, null, null, 1000.0,
                        {}, {}, { false }, {}, store::refreshSessions, {}, {}, sessionSource = store.sessionSource,
                        onSessionSourceChange = store::chooseSessionSource, refreshingSessions = store.refreshingSessions, foreground = false)
                } } } }
                ui.await("Initial conversations missing") { ui.text("Saved conversation") != null }
                ui.screenshot("conversations-before-refresh")
                pull()
                ui.awaitMain("Pull did not start refresh") { store.refreshingSessions && requests.get() == 2 }
                ui.screenshot("conversations-refreshing")
                pull()
                assertEquals(2, requests.get())
                release.countDown()
                ui.await("Refreshed conversations missing") { ui.text("Refreshed conversation") != null }
                ui.awaitMain("Spinner did not stop") { !store.refreshingSessions }
                assertEquals("cli", store.sessionSource)
                ui.screenshot("conversations-after-refresh")
            }
        } finally { release.countDown(); ui.main { store.close(); scope.cancel() }; client.close() }
    }
    private fun pull() {
        // Short lists have no accessibility scroll action, but still accept pull gestures.
        val node = checkNotNull(ui.find { it.actionList.any { action -> action.label?.toString() == "Refresh conversations" } })
        val bounds = Rect().also(node::getBoundsInScreen)
        val start = SystemClock.uptimeMillis()
        for (step in 0..25) {
            val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 25 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val y = bounds.top + bounds.height() * (.15f + .7f * step / 25)
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(ui.automation.injectInputEvent(event, true)) } finally { event.recycle() }
            SystemClock.sleep(20)
        }
    }
}
