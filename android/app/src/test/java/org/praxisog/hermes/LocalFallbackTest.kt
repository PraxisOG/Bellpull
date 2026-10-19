package org.praxisog.hermes

import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.praxisog.hermes.model.Session
import org.praxisog.hermes.model.OfflineHandoff
import org.praxisog.hermes.model.OfflineHandoffMessage

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class LocalFallbackTest {
    @get:Rule val compose = createComposeRule()
    @Test fun localPromptAndContextCanBeEditedSavedAndRestoredWithoutApplyingHermesReply() {
        val context = RuntimeEnvironment.getApplication()
        val promptPrefs = context.getSharedPreferences("test-local-prompt", 0).apply { edit().clear().commit() }
        val settings = LocalFallbackSettings(context, promptPrefs)
        settings.update(LocalFallbackConfiguration())
        var requests = 0
        compose.setContent { HermesTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            LocalFallbackSettingsEditor(settings, LocalLabState(scanned = true), {}, {}, {}, onAskHermes = { requests++ })
        } } }
        compose.onNodeWithTag("local-system-prompt").performScrollTo().performTextReplacement("Call the user Rowan. Keep replies short.")
        compose.onNodeWithTag("save-local-prompt").performScrollTo().performClick()
        compose.onNodeWithTag("local-context-4096").performScrollTo().performClick()
        compose.runOnIdle {
            val restored = LocalFallbackSettings(context, promptPrefs).value
            assertEquals("Call the user Rowan. Keep replies short.", restored.systemPrompt)
            assertEquals(4096, restored.contextTokens)
        }
        compose.onNodeWithTag("ask-hermes-local-prompt").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, requests); assertEquals("Call the user Rowan. Keep replies short.", settings.value.systemPrompt) }
        compose.onNodeWithTag("local-system-prompt").performScrollTo().performTextReplacement("")
        compose.onNodeWithTag("save-local-prompt").assertIsNotEnabled()
        compose.onNodeWithText("Restore default").performScrollTo().performClick()
        compose.onNodeWithTag("save-local-prompt").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(OfflinePrompt.DEFAULT_SYSTEM_PROMPT, settings.value.systemPrompt) }
    }
    @Test fun disconnectedSettingsCannotStartAProfileRequest() {
        val context = RuntimeEnvironment.getApplication()
        val settings = LocalFallbackSettings(context, context.getSharedPreferences("test-disconnected-prompt", 0))
        compose.setContent { HermesTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            LocalFallbackSettingsEditor(settings, LocalLabState(), {}, {}, {})
        } } }
        compose.onNodeWithTag("ask-hermes-local-prompt").performScrollTo().assertIsNotEnabled()
    }
    @Test fun customSystemPromptReachesInferenceAndCountsAgainstTheContextBudget() {
        val fork = OfflineFork(turns = listOf(OfflineTurn(role = "user", text = "Hello")))
        val custom = "Call the user Rowan. They maintain a community garden."
        val prompt = OfflinePrompt.build(fork, emptyList(), null, true, systemPrompt = custom) { turns -> turns.sumOf { it.text.length } }
        assertTrue(prompt.first().text.startsWith(custom))
        assertTrue(prompt.first().text.contains("search_chat"))
        assertFalse(prompt.first().text.contains("You are Bellpull"))
        val error = runCatching {
            OfflinePrompt.build(fork, emptyList(), null, false, systemPrompt = "profile ".repeat(500)) { turns -> turns.sumOf { it.text.length } }
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("increase context length"))
        assertEquals(1, fork.turns.size)
    }
    @Test fun longPressOffersLocalWithoutCreatingServerChat() {
        var remote = 0; var local = 0
        compose.setContent { HermesTheme { NewChatButton({ remote++ }, false, onNewLocal = { local++ }) } }
        compose.onNodeWithContentDescription("New chat").performTouchInput { longClick() }
        compose.onNodeWithText("New local chat").performClick()
        compose.runOnIdle { assertEquals(0, remote); assertEquals(1, local) }
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.runOnIdle { assertEquals(1, remote) }
    }
    @Test fun bannerShowsActualReadinessAndRetry() {
        var retry = 0
        compose.setContent { HermesTheme { LocalFallbackBanner(false, false, "Import model first", { retry++ }, {}) } }
        compose.onNodeWithText("Offline · local model unavailable").assertIsDisplayed()
        compose.onNodeWithText("Load / retry").performClick()
        compose.runOnIdle { assertEquals(1, retry) }
    }
    @Test fun syncedForkAppearsOnceBeforeAndAfterServerRefresh() {
        val fork = OfflineFork(id = "phone", title = "Saved", remoteId = "remote")
        assertEquals(listOf("local:phone"), mergeLocalSessions(emptyList(), listOf(fork)).map { it.id })
        val server = listOf(Session("source"), Session("remote"))
        assertEquals(server, mergeLocalSessions(server, listOf(fork)))
        assertEquals(3, mergeLocalSessions(server, listOf(fork.copy(remoteId = null))).size)
        val pending = fork.copy(remoteId = null, pending = OfflineHandoff("a".repeat(32), title = "Saved",
            messages = listOf(OfflineHandoffMessage("user", "Question"))))
        val accepted = listOf(Session("bellpull_offline_" + "a".repeat(32)))
        assertEquals(accepted, mergeLocalSessions(accepted, listOf(pending)))
        assertEquals(1, mergeLocalSessions(emptyList(), listOf(pending)).size)
    }
    @Test fun chunkCompactionCoversAllUnicodeTextWithinBudgetAndCanResume() {
        val text = "Important decision: retain Cobalt Orchard. 🤖 中文.\n".repeat(250)
        var offset = 0; val parts = mutableListOf<String>()
        while (offset < text.length) {
            val end = OfflineCompaction.nextEnd(text, offset, "Earlier facts", 2048) { turns -> turns.sumOf { it.text.length } }
            val piece = text.substring(offset, end)
            assertFalse(Character.isHighSurrogate(piece.last()))
            assertFalse(Character.isLowSurrogate(piece.first()))
            assertTrue(OfflineCompaction.prompt("Earlier facts", piece).sumOf { it.text.length } + 384 <= 2048)
            parts.add(piece); offset = end
        }
        assertTrue(parts.size > 3)
        assertEquals(text, parts.joinToString(""))
    }
    @Test fun summaryReplacesCompactedHistoryButDoesNotEraseSavedTurns() {
        val original = listOf(OfflineTurn(role = "user", text = "long archived turn"), OfflineTurn(role = "assistant", text = "archived reply"),
            OfflineTurn(role = "user", text = "What did we decide?"))
        val fork = OfflineFork(turns = original, summary = "Decision: Cobalt Orchard", summarizedTurns = 2)
        val prompt = OfflinePrompt.build(fork, emptyList(), null, false, 4096) { turns -> turns.sumOf { it.text.length } }
        assertTrue(prompt.any { "Decision: Cobalt Orchard" in it.text })
        assertFalse(prompt.any { "archived turn" in it.text })
        assertEquals(original, fork.turns)
    }
}
