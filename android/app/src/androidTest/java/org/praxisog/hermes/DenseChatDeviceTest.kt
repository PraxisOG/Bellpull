package org.praxisog.hermes

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.model.*

/** Synthetic Compose host on the dedicated emulator; never changes server profiles. */
@RunWith(AndroidJUnit4::class)
class DenseChatDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun nativeLongPressSelectionFollowUpAndBack() {
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val user = Message(1, role = "user", content = "Keep a small plant nearby.")
        val bot = Message(2, role = "assistant", content = "A trailing plant works well.")
        var edit: Pair<Message, String>? = null
        var reads = 0
        var sends = 0
        var preferences by mutableStateOf(BellpullPreferences(denseChatWindow = true, reduceMotion = true))
        lateinit var back: OnBackPressedDispatcher
        lateinit var view: android.view.View
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            view = LocalView.current
            HermesApp(UiState.Ready(listOf(Session("synthetic", title = "Dense chat acceptance"))), UiState.Ready(listOf(user, bot)),
                TurnState.Idle, "synthetic", Session("synthetic", title = "Dense chat acceptance"), 0.0,
                {}, {}, { sends++; true }, {}, {}, {}, {}, preferences = preferences, onPreferencesChange = { preferences = it },
                editingEnabled = true, onForkEdit = { message, text -> edit = message to text }, onReadMessage = { reads++ })
        }
        fun popup(id: Int) { compose.onNodeWithTag("bubble-$id").performTouchInput { longClick() } }
        fun action(label: String) { compose.onNodeWithContentDescription(label).performClick() }
        compose.onNodeWithText("You").assertDoesNotExist()
        popup(2); action("Select text")
        compose.onNodeWithTag("selected-message-text").performTextInputSelection(TextRange(2, 16))
        compose.runOnIdle { assertFalse(view.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime())) }
        compose.onNodeWithText("Copy selection").performClick()
        compose.runOnIdle {
            val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("trailing plant", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        popup(2); action("Read message"); assertEquals(1, reads)
        popup(1); action("Follow up")
        compose.onNodeWithText("Quote selection").performClick()
        compose.onNodeWithContentDescription("Message Hermes").assertTextContains("message #1", substring = true)
        popup(1); action("Fork & edit")
        compose.onNodeWithText("Edited prompt").performTextReplacement("Use a fern.")
        compose.onNodeWithText("Create edited branch").performClick()
        assertEquals(user to "Use a fern.", edit)
        compose.onNodeWithText("Cancel").performClick()
        action("More options")
        compose.onNode(hasText("Settings") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("Appearance").assertIsDisplayed()
        compose.runOnUiThread { back.onBackPressed() }
        compose.onNodeWithContentDescription("Message Hermes").assertTextContains("message #1", substring = true)
        if (compose.onAllNodesWithText("Conversations").fetchSemanticsNodes().isEmpty()) action("Back to sessions")
        compose.onNodeWithText("Conversations").assertIsDisplayed()
        compose.runOnUiThread { back.onBackPressed() }
        compose.onNodeWithText("Conversations").assertDoesNotExist()
        compose.onNodeWithText(bot.content!!).assertIsDisplayed()
        assertEquals(0, sends)
    }

    @Test fun oneBubbleKeepsToolDetailsAndWholeResponseSelection() {
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val messages = listOf(Message(1, role = "user", content = "Check a note."),
            Message(2, role = "assistant", content = "I'll check it."),
            Message(3, role = "tool", toolName = "read_file", content = """{"output":"A small fern."}"""),
            Message(4, role = "assistant", content = "A fern will work."))
        compose.setContent { HermesTheme { SessionDetailScreen(UiState.Ready(messages), TurnState.Idle, { false }, {}, {}, denseChatWindow = true) } }
        compose.onAllNodesWithContentDescription("Hermes response").assertCountEquals(1)
        compose.onNode(hasText("Read file") and hasAnyAncestor(hasTestTag("bubble-4"))).performClick()
        compose.onNodeWithText("Copy data").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("I'll check it.").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Select text").performClick()
        compose.onNodeWithTag("selected-message-text").assertTextEquals("I'll check it.\n\nA fern will work.")
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("A fern will work.").assertIsDisplayed()
    }
}
