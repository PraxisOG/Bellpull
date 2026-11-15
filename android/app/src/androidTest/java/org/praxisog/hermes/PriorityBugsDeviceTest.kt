package org.praxisog.hermes

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class PriorityBugsDeviceTest {
    private val ui = DeviceFixtureUi

    @Test fun savedAndStreamingLinksOpenByRealTouch() = ui.withWindows {
        val opened = CopyOnWriteArrayList<String>()
        var live by mutableStateOf(false)
        val text = "[Guide](https://example.com/guide)\n\nhttps://example.com/help\n\nwww.example.org"
        ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
            scene.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) { opened += uri }
                }) { HermesTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    SessionDetailScreen(UiState.Ready(if (live) emptyList() else listOf(Message(1, role = "assistant", content = text))),
                        if (live) TurnState.Streaming(text) else TurnState.Idle, { false }, {}, {},
                        denseChatWindow = live, reduceMotion = true)
                } } }
            } }
            for (streaming in listOf(false, true)) {
                ui.main { live = streaming }
                for ((label, uri) in listOf("Guide" to "https://example.com/guide",
                    "https://example.com/help" to "https://example.com/help", "www.example.org" to "http://www.example.org")) {
                    val before = opened.size
                    ui.await("Link text is missing: $label") { ui.text(label) != null }
                    ui.tap(checkNotNull(ui.text(label)))
                    ui.await("Link did not open: $label live=$streaming") { opened.size == before + 1 }
                    assertEquals(uri, opened.last())
                }
            }
            ui.screenshot("priority-links-live")
        }
    }

    private fun client() = HermesClient("http://127.0.0.1:18663", { "native-attachment-fixture-key" })
    private fun cache() = SessionCache(ApplicationProvider.getApplicationContext(), "priority-delegation")
    private fun store(scope: CoroutineScope, client: HermesClient, saved: SessionCache): HermesStore {
        val capabilities = (client.capabilities() as HermesResult.Ok).value
        assertTrue(capabilities.features["bellpull_delegation_delivery"] == true)
        return HermesStore(scope, client, cache = saved, journal = saved,
            durableRetention = capabilities.durableRunRetention ?: 86400,
            readState = ConversationReadState(saved.readReceipts())).also { next ->
            next.readState.onChange = saved::writeReceipt
        }
    }

    /** Run first; the harness kills this disposable package before the second method. */
    @Test fun startDelegationBeforeProcessDeath() = ui.withWindows {
        val saved = cache()
        val client = client()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val session = (client.createSession("Delegation process recovery fixture") as HermesResult.Ok).value
        saved.rememberSession(session.id)
        val next = store(scope, client, saved)
        try {
            ui.main { next.openSession(session.id) }
            ui.awaitMain("Initial history did not load") { next.history is UiState.Ready }
            ui.main { next.send("Start synthetic background work") }
            ui.awaitMain("Background dispatch was not persisted") {
                next.delegations?.states?.get(session.id)?.pending?.isNotEmpty() == true
            }
            assertTrue(saved.readDelegations()[session.id]!!.pending.isNotEmpty())
        } finally { ui.main { next.close(); scope.cancel() }; saved.close() }
    }

    @Test fun restoreDelegationAfterProcessDeath() = ui.withWindows {
        val saved = cache()
        val id = checkNotNull(saved.lastSession())
        val client = client()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val next = store(scope, client, saved)
        val received = CopyOnWriteArrayList<Message>()
        next.onReply = { _, message -> received += message }
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
                scene.onActivity { activity ->
                    next.startRecovery(); next.openSession(id)
                    activity.setContent { HermesTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                        SessionDetailScreen(next.history, next.turn, { false }, {}, {}, reduceMotion = true)
                    } } }
                }
                ui.await("Saved background result never appeared", timeout = 60000) {
                    ui.text("The synthetic background task finished successfully.") != null
                }
                ui.awaitMain("Background watch did not settle") { next.delegations?.states?.get(id)?.pending?.isEmpty() == true }
                ui.await("Completion did not produce a reply receipt") { received.any { it.isDelegationDelivery } }
                ui.await("Result not labelled as background output") { ui.text("Background result") != null }
                assertEquals(1, received.count { it.isDelegationDelivery })
                ui.main { next.refreshHistory() }
                Thread.sleep(500)
                assertEquals(1, received.count { it.isDelegationDelivery })
                ui.screenshot("priority-background-recovery")
            }
        } finally { ui.main { next.close(); scope.cancel() }; saved.close() }
    }
}
