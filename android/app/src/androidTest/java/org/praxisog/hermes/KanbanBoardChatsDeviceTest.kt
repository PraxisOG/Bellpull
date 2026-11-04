package org.praxisog.hermes

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.model.*

/** Real touch/navigation using saved synthetic tasks; no account, network or inference. */
@RunWith(AndroidJUnit4::class)
class KanbanBoardChatsDeviceTest {
    private val ui = DeviceFixtureUi
    @Test fun boardPickerAndSingleChatShortcutNavigateWithoutCreatingAChat() = ui.withWindows {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val board = KanbanBoard("a".repeat(32), "default", "Release board", "b".repeat(64), "Fixture")
        val task = KanbanTask("release", "Plan the release", "triage", "c".repeat(64), sessionId = "release-chat")
        val review = task.copy(id = "review", title = "Review the phone layout", sessionId = "review-chat", status = "done")
        fun workspace(tasks: List<KanbanTask>) = KanbanWorkspace(scope, object : KanbanCache {
            override fun readKanban() = KanbanLocalState(boards = listOf(board), selectedBoard = board.slug,
                snapshots = listOf(KanbanSnapshot(board = board, columns = KANBAN_COLUMNS, tasks = tasks, total = tasks.size,
                    complete = true, observedAt = 1000)))
            override fun writeKanban(value: KanbanLocalState) = true
        })
        val multiple = workspace(listOf(task, review, task.copy(id = "notes", title = "Check release notes")))
        val single = workspace(listOf(task))
        var current by mutableStateOf(multiple)
        var selected by mutableStateOf("release-chat")
        var focusTasks by mutableIntStateOf(1)
        var opened: String? = null
        var created = 0
        val sessions = listOf(Session("release-chat", title = "Release planning"), Session("review-chat", title = "Phone layout review"))
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
                scene.onActivity { activity -> activity.setContent { HermesTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    HermesApp(UiState.Ready(sessions), UiState.Ready(listOf(Message(1, role = "assistant", content = "Opened $selected"))),
                        TurnState.Idle, selected, sessions.first { it.id == selected }, 1000.0,
                        { selected = it; opened = it }, {}, { false }, {}, {}, {}, { created++ },
                        kanban = current, foreground = false, focusTasksRevision = focusTasks)
                } } } }
                ui.await("Board chats control missing") { ui.find { it.contentDescription?.toString() == "Choose board chat" } != null }
                ui.screenshot("board-chats-toolbar")
                ui.tap(checkNotNull(ui.find { it.contentDescription?.toString() == "Choose board chat" }))
                ui.await("Board chat chooser missing") { ui.text("Release board chats") != null }
                ui.screenshot("board-chats-picker")
                ui.tapText("Phone layout review")
                ui.awaitMain("Picking a board chat did not navigate") { opened == "review-chat" }
                ui.await("Chat content did not open") { ui.text("Opened review-chat") != null }
                ui.screenshot("board-chat-opened")
                assertEquals(0, created)
                ui.main { current = single; focusTasks++ ; opened = null }
                ui.await("Single-chat control missing") { ui.find { it.contentDescription?.toString() == "Open board chat" } != null }
                ui.tap(checkNotNull(ui.find { it.contentDescription?.toString() == "Open board chat" }))
                ui.awaitMain("Single-chat shortcut did not navigate") { opened == "release-chat" }
                ui.await("Single chat did not open") { ui.text("Opened release-chat") != null }
                assertEquals(0, created)
                assertNull(single.selectedTask)
            }
        } finally { multiple.close(); single.close(); scope.cancel() }
    }
}
