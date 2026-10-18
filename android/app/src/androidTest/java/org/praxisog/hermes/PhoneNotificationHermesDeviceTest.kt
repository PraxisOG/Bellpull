package org.praxisog.hermes

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.HermesResult
import java.io.File
import java.io.FileInputStream

/** Explicit opt-in live check. The temporary private config is supplied only for an authorized run. */
@RunWith(AndroidJUnit4::class)
class PhoneNotificationHermesDeviceTest {
    @Test fun capturedNotificationReachesLocalHermesAndItsAnswerReturnsToTheApp() = DeviceFixtureUi.withWindows {
        val ui = DeviceFixtureUi
        ui.guard()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configFile = File(context.filesDir, "phase12-live-config.json")
        assumeTrue("Live Hermes config was not explicitly supplied", configFile.isFile)
        val config = JSONObject(configFile.readText())
        check(configFile.delete())
        require(config.getString("url") == "http://127.0.0.1:18642")
        val client = HermesClient(config.getString("url"), { config.getString("key") })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val profile = "phase12-live-fixture"
        val cache = SessionCache(context, profile)
        val repo = PhoneNotificationRepository.get(context)
        val component = ComponentName(context, PhoneContextListenerService::class.java).flattenToString()
        var store: HermesStore? = null
        var sessionId: String? = null
        fun shell(command: String) = ui.automation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().readText()
        }
        fun idle() = runBlocking { repo.awaitIdle() }
        fun <T> requireOk(result: HermesResult<T>): T = when (result) {
            is HermesResult.Ok -> result.value
            is HermesResult.Err -> error("Live Hermes request failed: ${result.error}")
        }
        try {
            val capabilities = requireOk(client.capabilities())
            assertTrue("Local Bellpull plugin not loaded", capabilities.features["bellpull_persistent_artifacts"] == true)
            client.configureCapabilities(capabilities)
            val session = requireOk(client.createSession("Bellpull Phase 12 synthetic acceptance"))
            sessionId = session.id
            idle(); repo.clear(); idle()
            shell("cmd notification allow_listener $component")
            repo.capture(true); idle(); requestPhoneNotificationBinding(context)
            ui.await("Live fixture listener connected") { repo.state.value.connected }
            // One-token shell arguments avoid UiAutomation's non-shell quoting semantics.
            shell("cmd notification post -t Alice-phase12-live bellpull-phase12-live Meeting-location-is-Cobalt-Orchard")
            ui.await("Live synthetic notification captured") { repo.state.value.records.any { it.title == "Alice-phase12-live" } }
            repo.share(profile, true); idle()
            cache.clear()
            ui.main {
                store = HermesStore(scope, client, cache = cache, durableRetention = capabilities.durableRunRetention, journal = cache).also {
                    it.phoneNotificationContext = { query -> repo.contextFor(profile, query) }
                    it.openSession(session.id)
                }
            }
            val current = checkNotNull(store)
            ui.awaitMain("Disposable Hermes session loaded") { current.history is UiState.Ready }
            ui.main { assertTrue(current.send("Which meeting location did Alice announce? Answer with the location only, using the supplied phone notification context. Do not use tools or access other data.")) }
            ui.await("Real Hermes answer returned", 120_000) {
                var done = false
                ui.main { done = (current.history as? UiState.Ready)?.value.orEmpty().any { it.role == "assistant" && !it.content.isNullOrBlank() } }
                done
            }
            val messages = (current.history as UiState.Ready).value
            val reply = messages.last { it.role == "assistant" }.content.orEmpty()
            assertTrue("Hermes did not use the synthetic notification", reply.contains("Cobalt", true) && reply.contains("Orchard", true))
            assertTrue(messages.any { it.role == "user" && it.content.orEmpty().contains(PHONE_CONTEXT_START) })
            assertTrue("This context-only test must not execute tools", messages.none { it.role == "tool" || !it.toolCalls.isNullOrEmpty() })
            ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
                scene.onActivity { activity -> activity.setContent { HermesTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    SessionDetailScreen(UiState.Ready(messages), TurnState.Idle, { false }, {}, {}, denseChatWindow = true)
                } } } }
                ui.await("Hermes reply visible") { ui.find { it.text?.contains("Cobalt", true) == true } != null }
                ui.screenshot("phase12-live-hermes")
            }
            ui.instrumentation.sendStatus(0, Bundle().apply {
                putString("phase12_live_hermes", "Android listener → encrypted history → app selection → native Hermes run → saved answer → app display; synthetic location verified; no tools executed")
            })
        } finally {
            ui.main {
                repo.state.value.records.filter { it.title == "Alice-phase12-live" }.forEach {
                    PhoneContextListenerService.fixtureInstance?.cancelNotification(it.sourceKey)
                }
                store?.close()
            }
            scope.cancel(); repo.capture(false); repo.clear(); idle()
            shell("cmd notification disallow_listener $component")
            sessionId?.let { id ->
                val request = okhttp3.Request.Builder().url(config.getString("url") + "/api/sessions/$id")
                    .header("Authorization", "Bearer " + config.getString("key")).delete().build()
                HermesClient.defaultHttpClient().newCall(request).execute().use { response ->
                    check(response.isSuccessful || response.code == 404) { "Could not remove the synthetic Hermes conversation" }
                }
            }
            client.close(); cache.clear(); cache.close(); configFile.delete()
        }
    }
}
