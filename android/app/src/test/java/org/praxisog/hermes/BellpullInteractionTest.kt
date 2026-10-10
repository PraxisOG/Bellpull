package org.praxisog.hermes

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.model.Message
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w840dp-h900dp")
class BellpullInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val drafts = ChatDrafts()
    private var id by mutableStateOf(UiFixtures.sessions.first().id)
    private var preferences by mutableStateOf(BellpullPreferences())
    private var sends = 0

    private fun app(history: List<Message> = emptyList()) {
        compose.setContent {
            HermesApp(UiState.Ready(UiFixtures.sessions), UiState.Ready(history), TurnState.Idle,
                id, UiFixtures.sessions.first { it.id == id }, UiFixtures.NOW,
                onOpenSession = { id = it }, onCloseSession = {}, onSend = { sends++ }, onStop = {},
                onRetrySessions = {}, onRetryHistory = {}, onNewSession = {},
                draft = drafts[id], onDraftChange = { drafts.set(id, it) }, preferences = preferences,
                onPreferencesChange = { preferences = it })
        }
    }

    @Test fun draftsSurviveConversationAndWorkspaceSwitches() {
        app()
        compose.onNodeWithContentDescription("Message Hermes").performTextInput("Draft for A")
        compose.onNodeWithText("Widget cold-start timings").performClick()
        compose.onNodeWithContentDescription("Message Hermes").performTextInput("Draft for B")
        compose.onAllNodesWithText("Tailscale routing for the Fold").onFirst().performClick()
        compose.onNodeWithText("Draft for A").assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Collapse thinking by default").assertIsDisplayed()
        compose.onNodeWithText("Chats").performClick()
        compose.onNodeWithText("Draft for A").assertIsDisplayed()
        assertEquals("Draft for B", drafts[UiFixtures.sessions[1].id].text)
        assertEquals(0, sends)
    }

    @Test fun onlyMagnifierOpensConversationSearch() {
        app()
        compose.onNodeWithText("Conversations").assertHasNoClickAction()
        compose.onNodeWithContentDescription("Search conversations").performClick()
        compose.onNodeWithText("Search apps & chats").assertIsDisplayed()
    }

    @Test fun followUpQuotesIntoAnEditableDraftWithoutSending() {
        app(listOf(Message(91, role = "assistant", content = "Use the green wire.")))
        compose.onNodeWithContentDescription("Follow up").performClick()
        compose.onNodeWithText("Quote selection").performClick()
        compose.onNodeWithContentDescription("Message Hermes").assertTextContains("message #91", substring = true)
        compose.onNodeWithContentDescription("Message Hermes").assertTextContains("> Use the green wire.", substring = true)
        compose.onNodeWithContentDescription("Message Hermes").performTextInput("Why?")
        assertEquals(0, sends)
    }

    @Test fun sendDoesNotEraseDraftBeforeServerAcceptance() {
        app()
        compose.onNodeWithContentDescription("Message Hermes").performTextInput("Keep until accepted")
        compose.onNodeWithText("Send").performClick()
        compose.onNodeWithText("Keep until accepted").assertIsDisplayed()
        assertEquals(1, sends)
    }

    @Test fun thinkingDefaultsCollapsedAndLocalExpansionWorks() {
        app(listOf(Message(92, role = "assistant", content = "Answer", reasoningContent = "Recorded reasoning")))
        compose.onNodeWithText("Recorded reasoning").assertDoesNotExist()
        compose.onNodeWithText("Thinking ▾").performClick()
        compose.onNodeWithText("Recorded reasoning").assertIsDisplayed()
    }

    @Test fun themesApplyAndCustomColorValidatesBeforeSaving() {
        app()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Maroon").performClick()
        assertEquals(Palette.MAROON, preferences.palette)
        compose.onNodeWithText("Black").performClick()
        assertEquals(Appearance.DARK, preferences.appearance)
        compose.onNodeWithText("Custom").performClick()
        compose.onNodeWithText("Accent color").performTextReplacement("oops")
        compose.onNodeWithText("Apply color").assertIsNotEnabled()
        compose.onNodeWithText("Accent color").performTextReplacement("336699")
        compose.onNodeWithText("Apply color").performClick()
        assertEquals("336699", preferences.customAccent)
        compose.onNodeWithText("Android wallpaper").performClick()
        assertEquals(Palette.WALLPAPER, preferences.palette)
    }

    @Test @Config(sdk = [29]) fun wallpaperChoiceExplainsOlderAndroidLimit() {
        app()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Android wallpaper").assertIsNotEnabled()
        compose.onNodeWithText("Wallpaper colors require Android 12 or later.").assertIsDisplayed()
    }

    @Test fun presentationPreferencesPersistWithoutCredentials() {
        val context = RuntimeEnvironment.getApplication()
        val settings = BellpullSettings(context)
        settings.update(BellpullPreferences(Appearance.DARK, collapseThinking = false, reduceMotion = true, palette = Palette.CUSTOM, customAccent = "2E5577", denseChatWindow = true))
        assertEquals(settings.value, BellpullSettings(context).value)
    }
}
