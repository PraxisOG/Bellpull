package org.praxisog.hermes

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.model.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class DenseChatScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val user = Message(1, role = "user", content = "Keep it simple, with a little greenery.")
    private val bot = Message(2, role = "assistant", content = "One trailing plant on the shelf would add life without making the corner feel crowded.")
    private val prefs = mutableStateOf(BellpullPreferences(palette = Palette.MAROON, denseChatWindow = true, reduceMotion = true))
    private var read: Message? = null
    private var branch: Message? = null
    private var edit: Pair<Message, String>? = null
    private var viewed: Artifact? = null
    private var downloaded: Artifact? = null
    private var sends = 0
    private lateinit var back: OnBackPressedDispatcher
    private val file = Artifact("f".repeat(32), UiFixtures.sessions[0].id, "maroon.png", "image/png", 180,
        "0".repeat(64), UiFixtures.NOW, "agent")
    private fun app(history: List<Message> = listOf(user, bot), files: List<Artifact> = emptyList()) {
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            HermesApp(UiState.Ready(UiFixtures.sessions), UiState.Ready(history), TurnState.Idle,
                UiFixtures.sessions[0].id, UiFixtures.sessions[0], UiFixtures.NOW, {}, {}, { sends++ }, {}, {}, {}, {},
                preferences = prefs.value, onPreferencesChange = { prefs.value = it },
                onReadMessage = { read = it }, onBranchMessage = { branch = it }, branchingEnabled = true,
                editingEnabled = true, onForkEdit = { message, text -> edit = message to text },
                artifacts = files, onViewArtifact = { viewed = it }, onDownloadArtifact = { downloaded = it })
        }
    }
    private fun popup(id: Int) = compose.onNodeWithTag("bubble-$id").performTouchInput { longClick() }
    private fun action(label: String) = compose.onNodeWithContentDescription(label).performClick()
    private fun menu(label: String) {
        action("More options")
        compose.onNode(hasText(label) and hasAnyAncestor(isPopup())).performClick()
    }
    private fun capture() { compose.mainClock.advanceTimeBy(500); compose.waitForIdle(); captureScreenRoboImage() }
    private fun clipboard() = (RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .primaryClip!!.getItemAt(0).text.toString()
    private fun dense() {
        app()
        compose.onNodeWithText("You").assertDoesNotExist()
        compose.onNodeWithText("Hermes").assertDoesNotExist()
        compose.onNodeWithContentDescription("Copy message").assertDoesNotExist()
        compose.onNodeWithText("Follow up").assertDoesNotExist()
        capture()
    }
    @Test fun denseCover() = dense()
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun denseUnfoldedDark() = dense()
    @Test fun userActionRowCover() {
        app(); popup(1)
        listOf("Copy message", "Select text", "Follow up", "Fork & edit").forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed() }
        capture()
        action("Copy message"); assertEquals(user.content, clipboard())
        compose.onNodeWithTag("message-action-popup").assertDoesNotExist()
    }
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun botActionRowUnfolded() {
        app(); popup(2)
        listOf("Copy message", "Select text", "Read message", "Branch into new chat", "Follow up", "Share message")
            .forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed() }
        capture()
        action("Read message"); assertEquals(bot, read)
        popup(2); action("Branch into new chat"); assertEquals(bot, branch)
    }
    @Test fun selectionCopiesOnlyTheChosenRangeAndCannotEdit() {
        app(); popup(2); action("Select text")
        compose.onNodeWithTag("selected-message-text").performTextInputSelection(TextRange(4, 18))
        capture()
        compose.onNodeWithText("Copy selection").performClick()
        assertEquals("trailing plant", clipboard())
        compose.onNodeWithText(bot.content!!).assertIsDisplayed()
        assertEquals(0, sends)
    }
    // Quote submission is covered on Android in DenseChatDeviceTest and separately
    // by BellpullInteractionTest; this checks the dense menu's exact prompt target.
    @Test @Config(qualifiers = "w840dp-h900dp") fun userFollowUpAndForkEditKeepTheirOriginalTarget() {
        app(); popup(1); action("Follow up")
        compose.onNode(hasText(user.content!!) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        popup(1); action("Fork & edit")
        compose.onNodeWithText("Edited prompt").performTextReplacement("Add a fern.")
        compose.onNodeWithText("Create edited branch").performClick()
        assertEquals(user to "Add a fern.", edit); assertEquals(0, sends)
    }
    @Test fun toggleRestoresNamesAndPermanentActions() {
        app(); menu("Settings")
        compose.onNodeWithText("Display").performClick()
        compose.onNodeWithContentDescription("Dense chat window").performScrollTo().performClick()
        assertFalse(prefs.value.denseChatWindow)
        compose.runOnUiThread { back.onBackPressed() }
        compose.onNodeWithText("You").assertIsDisplayed()
        compose.onNodeWithText("Hermes").assertIsDisplayed()
        compose.onNodeWithContentDescription("Select text").assertIsDisplayed()
    }
    private fun settingsBack() {
        val history = (1..24).map { Message(it, role = if (it % 2 == 0) "assistant" else "user", content = "Saved message $it") }
        app(history)
        compose.onNodeWithContentDescription("Message Hermes").performTextInput("An unsent thought")
        compose.onNodeWithTag("transcript").performTouchInput { swipeDown() }
        compose.onNodeWithTag("transcript").performScrollToIndex(0)
        compose.onNodeWithTag("message-1").assertIsDisplayed()
        menu("Settings")
        compose.onNodeWithText("Appearance").assertIsDisplayed()
        compose.runOnUiThread { back.onBackPressed() }
        compose.onNodeWithTag("message-1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Message Hermes").assertTextContains("An unsent thought")
        assertEquals(0, sends)
    }
    @Test fun settingsBackRestoresCoverChatDraftAndScroll() = settingsBack()
    @Test @Config(qualifiers = "w840dp-h900dp") fun settingsBackRestoresUnfoldedChatDraftAndScroll() = settingsBack()
    private fun conversationsBack(wide: Boolean) {
        app()
        if (!wide) action("Back to sessions")
        compose.onNodeWithText("Conversations").assertIsDisplayed()
        compose.runOnUiThread { back.onBackPressed() }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Conversations").assertDoesNotExist()
        compose.onNodeWithText(bot.content!!).assertIsDisplayed()
        // With the panel closed, Back must be able to leave rather than reopening it.
        assertFalse(back.hasEnabledCallbacks())
    }
    @Test fun backClosesCoverConversationsBeforeLeavingApp() = conversationsBack(false)
    @Test @Config(qualifiers = "w840dp-h900dp") fun backClosesUnfoldedConversationsBeforeLeavingApp() = conversationsBack(true)
    private fun artifacts() {
        app(files = listOf(file)); menu("View artifacts")
        compose.onNodeWithText("Files in this conversation").assertIsDisplayed()
        capture()
        val inList = hasAnyAncestor(hasTestTag("conversation-artifacts"))
        compose.onNode(hasContentDescription("Download maroon.png") and inList).performClick()
        assertEquals(file, downloaded)
        compose.onNode(hasContentDescription("View maroon.png") and inList).performClick()
        assertEquals(file, viewed)
        compose.onNodeWithTag("conversation-artifacts").assertDoesNotExist()
    }
    @Test fun artifactsFromOverflowCover() = artifacts()
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun artifactsFromOverflowUnfolded() = artifacts()
    @Test fun artifactsEmptyStateIsReachable() {
        app(); menu("View artifacts")
        compose.onNodeWithText("No saved artifacts in this conversation yet.").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText(bot.content!!).assertIsDisplayed()
    }
    @Test fun pendingAndStreamingBubblesStayDenseAndCopyASnapshot() {
        var turn by mutableStateOf<TurnState>(TurnState.Streaming("First draft", canStop = true,
            parts = listOf(LivePart.Prose("p", "First draft"))))
        compose.setContent { HermesTheme { SessionDetailScreen(UiState.Ready(emptyList()), turn, {}, {}, {},
            pendingUserText = "Pending prompt", denseChatWindow = true) } }
        compose.onNodeWithText("You").assertDoesNotExist(); compose.onNodeWithText("Hermes").assertDoesNotExist()
        popup(Int.MIN_VALUE)
        compose.runOnIdle { turn = TurnState.Streaming("First draft is growing", canStop = true,
            parts = listOf(LivePart.Prose("p", "First draft is growing"))) }
        action("Copy message"); assertEquals("First draft", clipboard())
        compose.onNodeWithTag("stream-tail").assertIsDisplayed()
    }
}
