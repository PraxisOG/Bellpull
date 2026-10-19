package org.praxisog.hermes

import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.HermesResult
import java.io.File

/** Uses only the disposable package. Live Hermes inference is explicitly opt-in. */
class LocalPromptDeviceTest {
    private val ui = DeviceFixtureUi
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun editedPromptSurvivesRecreationReachesGemmaAndPreservesOversizedDraft() = ui.withWindows {
        val settings = LocalFallbackSettings(context)
        ui.main { settings.update(LocalFallbackConfiguration()) }
        val profile = "local-prompt-fixture"
        val workspace = OfflineWorkspace(context, profile) { null }
        val prompt = "You are a concise local assistant. The user's preferred name is Rowan. When asked for the user's name, answer only Rowan."
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
                fun showSettings() = scene.onActivity { activity -> activity.setContent { HermesTheme {
                    Surface { Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        LocalFallbackSettingsEditor(settings, LocalLabState(scanned = true), {}, {}, {})
                    } }
                } } }
                showSettings()
                ui.await("prompt editor") { ui.find { it.isEditable } != null }
                assertTrue(ui.find { it.isEditable }!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt) }))
                ui.tapText("Save prompt")
                ui.tapText("4K (4,096)")
                assertEquals(prompt, LocalFallbackSettings(context).value.systemPrompt)
                assertEquals(4096, LocalFallbackSettings(context).value.contextTokens)
                scene.recreate(); showSettings()
                ui.await("restored prompt editor") { ui.find { it.isEditable && it.text?.contains("Rowan") == true } != null }
                ui.screenshot("local-prompt-settings")
                ui.await("workspace") { !workspace.state.value.busy }
                workspace.create(null); ui.await("new local chat") { !workspace.state.value.busy }
                workspace.draft("What is my preferred name? Answer only the name."); workspace.send()
                ui.await("custom prompt inference", 120000) { !workspace.state.value.busy }
                assertNull(workspace.state.value.error)
                val answer = workspace.state.value.current!!.turns.last().text
                assertTrue("Local prompt should guide reply", answer.contains("Rowan"))
                assertEquals(4096, LocalLlmLab.get(context).state.value.contextTokens)
                // Exact token accounting rejects oversized personal instructions before changing the chat.
                ui.main { settings.update(settings.value.copy(contextTokens = 2048, systemPrompt = "remember this fact ".repeat(800))) }
                val turns = workspace.state.value.current!!.turns
                workspace.draft("Keep this question if the prompt is too long."); workspace.send()
                ui.await("oversized prompt rejected", 120000) { !workspace.state.value.busy }
                assertTrue(workspace.state.value.error.orEmpty().contains("increase context length"))
                assertEquals(turns, workspace.state.value.current!!.turns)
                assertEquals("Keep this question if the prompt is too long.", workspace.state.value.current!!.draft)
                val encrypted = File(context.applicationInfo.dataDir, "shared_prefs/local-fallback-prompt.xml").readText()
                assertFalse(encrypted.contains("Rowan")); assertFalse(encrypted.contains("remember this fact"))
                File(context.getExternalFilesDir(null), "local-prompt-report.json").writeText(JSONObject()
                    .put("custom_prompt_answer", answer).put("context_tokens", 4096).put("settings_recreated", true)
                    .put("oversized_prompt_preserved_draft_and_history", true).put("prompt_encrypted_at_rest", true).toString(2))
            }
        } finally {
            workspace.close(); SessionCache(context, profile).clear()
            ui.main { settings.update(LocalFallbackConfiguration()) }
        }
    }

    @Test fun settingsButtonAsksLiveHermesAndLeavesTheLocalPromptUnchanged() = ui.withWindows {
        val file = File(context.filesDir, "local-prompt-live-config.json")
        assumeTrue("Explicit local Hermes fixture config required", file.isFile)
        val config = JSONObject(file.readText()); check(file.delete())
        require(config.getString("url") == "http://127.0.0.1:18642")
        val runtime = BellpullRuntime.get(context)
        ui.main { runtime.disconnect() }
        val secure = SecureConnectionStore(context); secure.clear()
        val profile = secure.add("Local Hermes test", config.getString("url"), config.getString("key"))
        secure.activate(profile.id)
        val original = runtime.localSettings.value.systemPrompt
        var created: String? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scene ->
                scene.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                ui.await("connected Hermes", 60000) { runtime.store != null && runtime.store!!.sessions is UiState.Ready }
                ui.tapText("Settings")
                ui.tapText("Local fallback")
                // In the unfolded shell, scroll Settings rather than the independent chat list.
                var settingsScroll = checkNotNull(ui.text("Local fallback"))
                while (!settingsScroll.isScrollable && settingsScroll.parent != null) settingsScroll = settingsScroll.parent
                repeat(8) {
                    if (ui.text("Ask Hermes for a short prompt") == null) {
                        check(settingsScroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                        ui.automation.waitForIdle(300, 3000)
                    }
                }
                ui.tapText("Ask Hermes for a short prompt")
                ui.await("new profile request", 30000) { runtime.store?.openSessionId != null }
                val store = runtime.store!!; created = store.openSessionId
                ui.await("live profile answer", 300000) {
                    val history = (store.history as? UiState.Ready)?.value.orEmpty()
                    history.any { it.role == "assistant" && !it.content.isNullOrBlank() } &&
                        store.turn !is TurnState.Streaming && store.runPresentation?.busy != true
                }
                val history = (store.history as UiState.Ready).value
                assertEquals(1, history.count { it.role == "user" && it.content == OfflinePrompt.HERMES_PROFILE_REQUEST })
                assertEquals(original, runtime.localSettings.value.systemPrompt)
                assertEquals(original, LocalFallbackSettings(context).value.systemPrompt)
                File(context.getExternalFilesDir(null), "local-prompt-live-report.json").writeText(JSONObject()
                    .put("settings_button_created_chat", true).put("user_requests", 1).put("assistant_replied", true)
                    .put("manual_copy_required", true).put("local_prompt_unchanged", true).toString(2))
            }
        } finally {
            // The profile text may contain personal facts: never write it into test artifacts.
            ui.main { runtime.disconnect() }
            created?.let { id ->
                HermesClient.defaultHttpClient().newCall(okhttp3.Request.Builder().url(config.getString("url") + "/api/sessions/$id")
                    .header("Authorization", "Bearer " + config.getString("key")).delete().build()).execute().use {
                    check(it.isSuccessful || it.code == 404) { "Synthetic request chat cleanup failed" }
                }
            }
            SessionCache(context, profile.id).clear(); secure.clear(); file.delete()
        }
    }
}
