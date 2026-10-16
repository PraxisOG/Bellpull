package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class ConversationFiltersScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = HermesClient("https://fixture.invalid", { "fixture" })
    private val project = ConversationProject("a".repeat(32), "Costumes")
    private val workspace = ProjectWorkspace(scope, client, object : ProjectCache {
        override fun readProjects() = ProjectSnapshot(projects = listOf(project))
    }, false)
    @After fun close() { scope.cancel(); client.close() }
    private val title = "WALL·E shoulder wiring and battery layout"
    private var selectedSource by mutableStateOf<String?>(null)
    private fun show(newChat: Boolean = true) {
        compose.setContent { HermesTheme {
            SessionListScreen(UiState.Ready(listOf(Session("chat", title = title, source = "api_server"))), UiFixtures.NOW, null,
                {}, {}, {}, titleOnly = true, showNewChatButton = newChat, onExpandedSearch = {}, source = selectedSource, onSourceChange = { selectedSource = it },
                projectFilters = { close -> ProjectFilterItems(workspace, emptySet(), emptySet(), { workspace.choose(it); close() }, close) },
                onOrganize = {})
        } }
    }
    @Test fun compactDrawerCover() { show(); compose.onRoot().captureRoboImage() }
    @Test @Config(qualifiers = "w240dp-h900dp-night") fun compactDrawerUnfolded() { show(false); compose.onRoot().captureRoboImage() }
    @Test fun filtersCover() { show(); compose.onNodeWithContentDescription("Filter conversations").performClick(); compose.mainClock.advanceTimeBy(1000); compose.waitForIdle(); captureScreenRoboImage() }
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun filtersUnfoldedDark() { show(); compose.onNodeWithContentDescription("Filter conversations").performClick(); compose.mainClock.advanceTimeBy(1000); compose.waitForIdle(); captureScreenRoboImage() }
    @Test fun filterContainsProjectsAndSourcesAndClosesAfterChoosing() {
        show()
        compose.onNodeWithText("API").assertDoesNotExist()
        val filter = compose.onNodeWithContentDescription("Filter conversations").fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithContentDescription("Search conversations").fetchSemanticsNode().boundsInRoot
        assertTrue(filter.right <= search.left)
        compose.onNodeWithContentDescription("Filter conversations").performClick()
        for (text in listOf("Projects", "Costumes", "Unfiled", "Conversation source", "API chats", "CLI chats", "TUI chats")) compose.onNodeWithText(text).assertIsDisplayed()
        compose.onNodeWithText("CLI chats").performClick()
        assertEquals("cli", selectedSource)
        compose.onNodeWithText("Projects").assertDoesNotExist()
        compose.onNodeWithContentDescription("Filter conversations").performClick()
        compose.onNodeWithText("Costumes").performClick()
        assertEquals(project.id, workspace.selectedScope)
        compose.onNodeWithText("Conversation source").assertDoesNotExist()
    }
    @Test @Config(qualifiers = "w240dp-h900dp") fun compactTitleUsesTwoLinesAndActionsSitAtTheEdge() {
        show(false)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(title).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2, layouts.single().lineCount)
        val actions = compose.onNodeWithTag("chat-actions-chat", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Actions should use the right edge of the row", actions.right > root.right * .90f)
    }
    @Test fun voiceSetupCover() {
        compose.setContent { HermesTheme { VoicePanel(null, VoiceConfiguration(), null, {}, {}, {}) } }
        captureScreenRoboImage()
    }
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun voiceSetupUnfoldedDark() {
        compose.setContent { HermesTheme { VoicePanel(null, VoiceConfiguration(), null, {}, {}, {}) } }
        captureScreenRoboImage()
    }
    private fun configuredVoice(active: Boolean) {
        val config = VoiceConfiguration("https://speech.invalid/v1/audio/transcriptions")
        val voice = if (active) VoiceSession("chat", "fixture", scope, object : VoiceConversation {
            override val selected = true
            override val ready = true
            override fun run() = VoiceRun()
            override fun send(text: String) = false
            override fun cancel() {}
        }, config, object : VoiceOutput {
            override fun prepare(onReady: (String) -> Unit, onError: (String) -> Unit) = onReady("offline fixture voice")
            override fun speak(text: String, started: () -> Unit, complete: () -> Unit) {}
            override fun stop() {}
            override fun close() {}
        }, object : VoiceCapture {
            override fun start(frame: (ShortArray, Int) -> Unit, error: (String) -> Unit) = true
            override fun close() {}
        }).also { it.start() } else null
        try {
            compose.setContent { HermesTheme { VoicePanel(voice, config, null, {}, {}, {}) } }
            compose.waitForIdle(); captureScreenRoboImage()
        } finally { voice?.close() }
    }
    @Test fun voiceReadyCover() = configuredVoice(false)
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun voiceReadyUnfoldedDark() = configuredVoice(false)
    @Test fun voiceListeningCover() = configuredVoice(true)
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun voiceListeningUnfoldedDark() = configuredVoice(true)

}
