package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.praxisog.hermes.model.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class KanbanBoardChatsTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val board = KanbanBoard("a".repeat(32), "default", "Mobile release", "b".repeat(64), "Desktop", hierarchy = true)
    private val first = KanbanTask("release", "Ship the mobile release", "triage", "c".repeat(64), sessionId = "chat-one")
    private val second = first.copy(id = "review", title = "Review the phone layouts", containerId = first.id, sessionId = "chat-two", status = "done")
    private val sessions = listOf(Session("chat-one", title = "Release planning"), Session("chat-two", title = "Phone UI review"))
    @After fun close() = scope.cancel()
    private fun workspace(tasks: List<KanbanTask>, complete: Boolean = true, archived: Boolean = false): KanbanWorkspace {
        val selected = board.copy(archived = archived)
        val total = tasks.size + if (complete) 0 else 1
        val saved = KanbanLocalState(boards = listOf(selected), selectedBoard = selected.slug,
            snapshots = listOf(KanbanSnapshot(board = selected, columns = KANBAN_COLUMNS, tasks = tasks.take(500), total = total,
                complete = complete && tasks.size <= 500, observedAt = 1000)),
            hierarchies = if (complete) listOf(KanbanHierarchy(board = selected, tasks = tasks, total = total,
                revision = "d".repeat(64), complete = true, observedAt = 1000)) else emptyList())
        return KanbanWorkspace(scope, object : KanbanCache {
            override fun readKanban() = saved
            override fun writeKanban(value: KanbanLocalState) = true
        })
    }
    private fun render(workspace: KanbanWorkspace, onOpen: (String) -> Unit = {}) {
        compose.setContent { HermesTheme(palette = Palette.MAROON) { Surface { Column {
            KanbanToolbar(workspace, false, false, {}, onOpen, sessions)
            KanbanScreen(workspace, false, onOpen)
        } } } }
    }
    @Test fun groupsExplicitChatLinksAndCountsEachTaskOnce() {
        val tasks = listOf(first, first, second, first.copy(id = "shared", sessionId = " chat-one "),
            first.copy(id = "none", sessionId = null), first.copy(id = "blank", sessionId = " "))
        assertEquals(listOf("chat-one", "chat-two"), kanbanBoardChats(tasks).map { it.sessionId })
        assertEquals(listOf("release", "shared"), kanbanBoardChats(tasks).first().tasks.map { it.id })
    }
    @Test fun oneChatOpensDirectlyFromArchivedSavedBoardRegardlessOfTaskFilter() {
        var opened: String? = null
        val workspace = workspace(listOf(first, first.copy(id = "same-chat")), archived = true)
        workspace.query = "does not match"
        render(workspace) { opened = it }
        compose.onNodeWithTag("kanban-board-chats").performClick()
        assertEquals("chat-one", opened)
        assertNull(workspace.selectedTask)
        compose.onNodeWithText("Mobile release chats").assertDoesNotExist()
    }
    private fun chooser() {
        render(workspace(listOf(first, second, first.copy(id = "check", title = "Check changelog"))))
        compose.onNodeWithTag("kanban-board-chats").performClick()
        compose.onNodeWithText("Release planning").assertIsDisplayed()
        compose.onNodeWithText("Phone UI review").assertIsDisplayed()
        compose.onNode(isDialog()).captureRoboImage()
    }
    @Test fun chatChooserCover() = chooser()
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun chatChooserUnfolded() = chooser()
    @Test fun chooserIncludesOlderTasksBeyondSnapshotAndMissingSessionTitles() {
        val recent = (1..500).map { first.copy(id = "task-$it", sessionId = "chat-one") }
        var opened: String? = null
        render(workspace(recent + second.copy(sessionId = "older-chat"))) { opened = it }
        compose.onNodeWithTag("kanban-board-chats").performClick()
        compose.onNodeWithText("older-chat").assertIsDisplayed()
        compose.onNodeWithTag("board-chat-older-chat").performClick()
        assertEquals("older-chat", opened)
        compose.onNodeWithText("Mobile release chats").assertDoesNotExist()
    }
    @Test fun partialBoardDoesNotPretendOneLoadedLinkIsTheOnlyChat() {
        var opened: String? = null
        render(workspace(listOf(first), complete = false)) { opened = it }
        compose.onNodeWithTag("kanban-board-chats").performClick()
        assertNull(opened)
        compose.onNodeWithText("Only loaded task links are shown. Refresh to look for more.").assertIsDisplayed()
        compose.onNodeWithTag("board-chat-chat-one").performClick()
        assertEquals("chat-one", opened)
    }
    @Test fun emptyUnloadedBoardAndEmptyCompleteBoardExplainDifferentStates() {
        var current by mutableStateOf(workspace(emptyList(), complete = false))
        compose.setContent { HermesTheme { KanbanBoardChatButton(current, sessions) {} } }
        compose.onNodeWithTag("kanban-board-chats").performClick()
        compose.onNodeWithText("No linked chats in the loaded tasks.").assertIsDisplayed()
        compose.runOnIdle { current = workspace(emptyList()) }
        compose.onNodeWithText("No chats are linked to this board's tasks yet.").assertIsDisplayed()
    }
    @Test fun buttonRoutesThroughAppToTheSelectedChat() {
        var opened: String? = null
        var newChats = 0
        val workspace = workspace(listOf(first, second))
        compose.setContent { HermesApp(UiState.Ready(sessions), UiState.Ready(emptyList()), TurnState.Idle,
            sessions.first().id, sessions.first(), 1000.0, { opened = it }, {}, { false }, {}, {}, {}, { newChats++ },
            kanban = workspace, foreground = false, focusTasksRevision = 1) }
        compose.onNodeWithTag("kanban-board-chats").performClick()
        compose.onNodeWithTag("board-chat-chat-two").performClick()
        assertEquals("chat-two", opened); assertEquals(0, newChats)
        compose.onNodeWithTag("toolbar-new-chat").assertExists()
        compose.onNodeWithTag("kanban-board-chats").assertDoesNotExist()
    }
}
