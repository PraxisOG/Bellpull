package org.praxisog.hermes

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.praxisog.hermes.model.*

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class KanbanScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val now = 1793491200000L
    private val board = KanbanBoard("a".repeat(32), "default", "Home projects", "b".repeat(64), "Desktop Hermes", canCreate = true, hierarchy = true, canContain = true)
    private val task = KanbanTask("ship_mobile", "Ship the mobile Kanban board", "triage", "c".repeat(64),
        sessionId = "task-chat", assignee = "work", projectId = "Bellpull", canReparent = true, allowedMoves = listOf("blocked"),
        body = "Check the board on the phone, then review queued edits after reconnecting.",
        comments = listOf(KanbanComment(1, "Desktop", "Keep this task on its home board.", now / 1000)))
    private val second = task.copy(id = "release_notes", sessionId = null, title = "Write release notes", status = "blocked", allowedMoves = listOf("triage"))
    private fun workspace(boardView: Boolean = false, pending: Boolean = false, tree: Boolean = false): KanbanWorkspace {
        val mutation = KanbanMutation("d".repeat(32), board.ownerId, board.id, task.id, task.revision, "comment", comment = "I can test this on the phone.")
        val nested = listOf(task.copy(subtaskCount = 1), second.copy(containerId = task.id, subtaskCount = 1)) +
            (1..6).map { level -> task.copy(id = "level_$level", sessionId = null, title = "Nested step $level · review the mobile release", status = if (level == 6) "done" else "triage",
                containerId = if (level == 1) second.id else "level_${level-1}", subtaskCount = if (level == 6) 0 else 1) }
        var saved = KanbanLocalState(boards = listOf(board), selectedBoard = board.slug, boardView = boardView,
            viewMode = if (tree) "Tree" else null,
            expanded = if (tree) mapOf(board.key to nested.map { it.id }.toSet()) else emptyMap(),
            hierarchies = listOf(KanbanHierarchy(board = board, tasks = if (tree) nested else listOf(task, second), total = if (tree) nested.size else 2,
                revision = "f".repeat(64), complete = true, observedAt = now - 120000)),
            snapshots = listOf(KanbanSnapshot(board = board, columns = KANBAN_COLUMNS, tasks = listOf(task, second), total = 2, complete = true, observedAt = now - 120000)),
            details = listOf(KanbanDetail(board = board, task = task, observedAt = now - 120000)),
            pending = if (pending) listOf(KanbanPending(board, mutation, "conflict", "Task changed. Review the current task before applying this comment.")) else emptyList())
        return KanbanWorkspace(scope, object : KanbanCache {
            override fun readKanban() = saved
            override fun writeKanban(value: KanbanLocalState): Boolean { saved = value; return true }
        })
    }
    @After fun close() = scope.cancel()
    private fun render(boardView: Boolean = false, detail: Boolean = false, pending: Boolean = false, tree: Boolean = false, openChat: (String) -> Unit = {}): KanbanWorkspace {
        val workspace = workspace(boardView, pending, tree)
        if (detail) workspace.open(task.id)
        compose.setContent { HermesTheme(palette = Palette.MAROON) { Surface {
            Column { KanbanToolbar(workspace, false, false, {}); KanbanScreen(workspace, false, onOpenSession = openChat, clock = { now }) }
        } } }
        compose.waitForIdle()
        return workspace
    }
    @Test fun listCover() { render(); compose.onRoot().captureRoboImage() }
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun boardUnfolded() { render(boardView = true); compose.onRoot().captureRoboImage() }
    @Test fun detailCover() { render(detail = true); compose.onRoot().captureRoboImage() }
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun conflictUnfolded() { render(detail = true, pending = true); compose.onRoot().captureRoboImage() }
    @Test @Config(qualifiers = "w240dp-h120dp") fun narrowSidebarKeepsEveryDestinationReadable() {
        compose.setContent { HermesTheme(palette = Palette.MAROON) { Surface {
            WorkspaceModes(WorkspaceMode.TASKS, includeTasks = true) {}
        } } }
        WorkspaceMode.entries.forEach { compose.onNodeWithText(it.title).assertIsDisplayed() }
        val projects = compose.onNodeWithText("Projects").fetchSemanticsNode().boundsInRoot
        val settings = compose.onNodeWithText("Settings").fetchSemanticsNode().boundsInRoot
        assertTrue(settings.top >= projects.bottom)
        compose.onRoot().captureRoboImage()
    }
    @Test fun searchAndBoardInformationLiveInTasksMenu() {
        render()
        compose.onNodeWithText("Search tasks").assertDoesNotExist()
        compose.onNodeWithText("Desktop Hermes · Board owner").assertDoesNotExist()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Board information").performClick()
        compose.onNodeWithText("Owner: Desktop Hermes").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Search tasks").performClick()
        compose.onNodeWithText("Search tasks").performTextInput("release")
        compose.onNodeWithText(task.title).assertDoesNotExist()
        compose.onNodeWithText(second.title).performClick()
        compose.onNodeWithText("Task release_notes").assertIsDisplayed()
        compose.onNodeWithText("Back to board").performClick()
        compose.onNodeWithText("Filter: release").assertIsDisplayed()
        compose.onNodeWithText("Clear filter").performClick()
        compose.onNodeWithText(task.title).assertIsDisplayed()
    }
    @Test fun tasksPlusOpensBoardDraftAndNeverCreatesChat() {
        val workspace = render()
        compose.onNodeWithTag("toolbar-new-chat").assertDoesNotExist()
        compose.onNodeWithTag("toolbar-new-task").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); workspace.editorId == "new" }
        compose.onNodeWithText("Create task").assertIsDisplayed()
        assertEquals(board.key + "/new", workspace.formKey)
    }
    @Test fun treeCover() { render(tree = true); compose.onRoot().captureRoboImage() }
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun treeUnfolded() { render(tree = true); compose.onRoot().captureRoboImage() }
    @Test fun treeExpansionSurvivesDetailsAndSearchRetainsAncestors() {
        val workspace = render(tree = true)
        compose.onNodeWithText(second.title).performClick()
        compose.onNodeWithText("Back to board").performClick()
        assertTrue(second.id in workspace.expanded)
        compose.onNodeWithText("Collapse all").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); workspace.expanded.isEmpty() }
        compose.onNodeWithText(second.title).assertDoesNotExist()
        compose.onNodeWithTag("expand-${task.id}").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); task.id in workspace.expanded }
        compose.onNodeWithText(second.title).assertIsDisplayed()
        compose.runOnIdle { workspace.query = "Nested step 6" }
        compose.onNodeWithText(task.title).assertIsDisplayed()
        compose.onNodeWithText(second.title).assertIsDisplayed()
        compose.onNodeWithText("Matching tasks with ancestor context").assertIsDisplayed()
    }
    @Test fun cardsOpenTheirLinkedChatInEveryViewWithoutOpeningDetails() {
        var opened: String? = null
        val workspace = render(openChat = { opened = it })
        for (mode in listOf("List", "Board", "Tree")) {
            compose.onNodeWithText(mode, substring = false).performClick()
            compose.waitUntil(5000) { compose.waitForIdle(); workspace.viewMode == mode }
            compose.onNodeWithTag("kanban-chat-${task.id}").performClick()
            assertEquals("task-chat", opened); assertNull(workspace.selectedTask)
            compose.onNodeWithTag("kanban-chat-${second.id}").assertDoesNotExist()
            opened = null
        }
    }
    @Test fun parentPickerQueuesContainmentWithoutChangingTaskStatus() {
        val workspace = render(detail = true)
        compose.onNodeWithText("Change parent").performClick()
        compose.onNodeWithText("Containing parent").assertIsDisplayed()
        captureScreenRoboImage()
        compose.onNodeWithText(second.title).performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); workspace.state.pending.size == 1 }
        val mutation = workspace.state.pending.single().mutation
        assertEquals("contain", mutation.action); assertEquals(second.id, mutation.containerId)
        assertEquals("triage", workspace.task!!.status)
    }
    @Test fun incompleteTreeAndArchivedToolbarRemainExplicit() {
        val archived = board.copy(archived = true, canCreate = false, canContain = false)
        val missing = task.copy(containerId = "older-parent", subtaskCount = 2)
        val saved = KanbanLocalState(boards = listOf(archived), selectedBoard = archived.slug, viewMode = "Tree",
            snapshots = listOf(KanbanSnapshot(board = archived, columns = KANBAN_COLUMNS, tasks = listOf(missing), total = 600, complete = false, observedAt = now)))
        val workspace = KanbanWorkspace(scope, object : KanbanCache {
            override fun readKanban() = saved
            override fun writeKanban(value: KanbanLocalState) = true
        })
        compose.setContent { HermesTheme { Surface { Column {
            KanbanToolbar(workspace, false, false, {}); KanbanScreen(workspace, false)
        } } } }
        compose.onNodeWithTag("toolbar-new-task").assertIsNotEnabled()
        compose.onNodeWithText("Archived board · read only").assertIsDisplayed()
        compose.onNodeWithText("Containing parent not loaded").assertIsDisplayed()
        compose.onNodeWithText("Subtasks not loaded").assertIsDisplayed()
        compose.onNodeWithText("This board has no tasks.").assertDoesNotExist()
        compose.onRoot().captureRoboImage()
    }
    @Test fun offlineCommentQueuesAndCanBeRecoveredForEditing() {
        val workspace = render(detail = true)
        compose.onNodeWithText("Comment", substring = false).performTextInput("Saved for later")
        compose.onNodeWithText("Add comment").performScrollTo().assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(5000) { compose.waitForIdle(); workspace.state.pending.size == 1 }
        assertEquals("queued", workspace.state.pending.single().state)
        compose.onNodeWithText("Edit comment").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(5000) { compose.waitForIdle(); workspace.state.pending.isEmpty() }
        compose.onNodeWithText("Comment", substring = false).assertTextContains("Saved for later")
    }
    private fun editor() {
        compose.setContent { HermesTheme(palette = Palette.MAROON) {
            KanbanTaskEditor(KanbanFormDraft("draft_" + "e".repeat(32), KANBAN_EMPTY_REVISION, "Plan the mobile release", "Keep the work in triage until reviewed.", "work", "2"),
                true, "Home projects", "Desktop Hermes", true, null, {}, {}, {}, {})
        } }
        compose.waitForIdle(); captureScreenRoboImage()
        compose.onNodeWithText("Create task").assertIsDisplayed().assertIsEnabled()
    }
    @Test fun newTaskEditorCover() = editor()
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun newTaskEditorUnfolded() = editor()
    @Test @Config(qualifiers = "w393dp-h420dp") fun taskEditorKeepsSaveReachableAtKeyboardHeight() = editor()
}
