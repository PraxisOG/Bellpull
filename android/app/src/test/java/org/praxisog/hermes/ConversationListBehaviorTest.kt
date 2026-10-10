package org.praxisog.hermes

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.model.Session
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class ConversationListBehaviorTest {
    @get:Rule val compose = createComposeRule()
    private var sessions by mutableStateOf((1..30).map { Session("chat-$it", title = "Conversation $it",
        preview = "A saved reply", lastActive = UiFixtures.NOW - it, messageCount = 4, toolCallCount = 1) })
    private var unread by mutableStateOf(emptySet<String>())
    private var completion by mutableStateOf(0L)
    private var selected by mutableStateOf<String?>("chat-12")

    private fun show() {
        compose.setContent {
            HermesTheme {
                SessionListScreen(UiState.Ready(sessions), UiFixtures.NOW, selected, {}, {}, {},
                    onExpandedSearch = {}, unreadIds = unread, completionRevision = completion)
            }
        }
        compose.waitForIdle()
    }
    @Test fun promotedUnreadChatRevealsTheTopInsteadOfKeepingTheOldSelectionAnchor() {
        show()
        compose.onNodeWithTag("conversations-list").performScrollToIndex(20)
        compose.runOnIdle {
            val promoted = sessions.last().copy(lastActive = UiFixtures.NOW + 1)
            sessions = listOf(promoted) + sessions.dropLast(1)
            unread = setOf(promoted.id)
            completion++
        }
        compose.onNodeWithText("Conversation 30").assertIsDisplayed()
        compose.onNodeWithTag("unread-chat-30", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Conversation 12").assertIsNotDisplayed()
    }
    @Test fun anotherCompletionInAnAlreadyFirstUnreadChatAlsoRevealsTheTop() {
        unread = setOf("chat-1")
        show()
        compose.onNodeWithTag("conversations-list").performScrollToIndex(20)
        compose.runOnIdle { completion++ }
        compose.onNodeWithText("Conversation 1").assertIsDisplayed()
    }
    @Test fun serverReorderingWithoutAnUnreadReceiptStillRevealsPromotedChat() {
        show()
        compose.onNodeWithTag("conversations-list").performScrollToIndex(20)
        compose.runOnIdle { sessions = listOf(sessions.last().copy(lastActive = UiFixtures.NOW + 1)) + sessions.dropLast(1) }
        compose.onNodeWithText("Conversation 30").assertIsDisplayed()
    }
    @Test fun metadataRefreshDoesNotPullTheReaderBackToTheTop() {
        show()
        compose.onNodeWithTag("conversations-list").performScrollToIndex(20)
        compose.runOnIdle { sessions = sessions.map { it.copy(messageCount = 5) } }
        compose.onNodeWithText("Conversation 21").assertIsDisplayed()
        compose.onNodeWithText("Conversation 1").assertIsNotDisplayed()
    }
    @Test fun selectingAnOlderChatDoesNotClearItsUnreadDotByItself() {
        unread = setOf("chat-30")
        selected = "chat-30"
        show()
        compose.onNodeWithTag("unread-chat-30", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Conversation 30").performClick()
        compose.onNodeWithTag("unread-chat-30", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Conversations").assertHasNoClickAction()
    }
}
