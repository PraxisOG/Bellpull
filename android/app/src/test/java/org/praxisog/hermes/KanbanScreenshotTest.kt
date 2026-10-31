package org.praxisog.hermes

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
    private val board = KanbanBoard("a".repeat(32), "default", "Home projects", "b".repeat(64), "Desktop Hermes")
    private val task = KanbanTask("ship_mobile", "Ship the mobile Kanban board", "triage", "c".repeat(64),
        assignee = "work", projectId = "Bellpull", allowedMoves = listOf("blocked"),
        body = "Check the board on the phone, then review queued edits after reconnecting.",
        comments = listOf(KanbanComment(1, "Desktop", "Keep this task on its home board.", now / 1000)))
    private val second = task.copy(id = "release_notes", title = "Write release notes", status = "blocked", allowedMoves = listOf("triage"))
    private fun workspace(boardView: Boolean = false, pending: Boolean = false): KanbanWorkspace {
        val mutation = KanbanMutation("d".repeat(32), board.ownerId, board.id, task.id, task.revision, "comment", comment = "I can test this on the phone.")
        var saved = KanbanLocalState(boards = listOf(board), selectedBoard = board.slug, boardView = boardView,
            snapshots = listOf(KanbanSnapshot(board = board, columns = KANBAN_COLUMNS, tasks = listOf(task, second), total = 2, complete = true, observedAt = now - 120000)),
            details = listOf(KanbanDetail(board = board, task = task, observedAt = now - 120000)),
            pending = if (pending) listOf(KanbanPending(board, mutation, "conflict", "Task changed. Review the current task before applying this comment.")) else emptyList())
        return KanbanWorkspace(scope, object : KanbanCache {
            override fun readKanban() = saved
            override fun writeKanban(value: KanbanLocalState): Boolean { saved = value; return true }
        })
    }
    @After fun close() = scope.cancel()
    private fun render(boardView: Boolean = false, detail: Boolean = false, pending: Boolean = false): KanbanWorkspace {
        val workspace = workspace(boardView, pending)
        if (detail) workspace.open(task.id)
        compose.setContent { HermesTheme(palette = Palette.MAROON) { Surface {
            KanbanScreen(workspace, false, clock = { now })
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
    @Test fun cachedBoardSearchAndOpenTaskKeepOwnershipAndAgeVisible() {
        render()
        compose.onNodeWithText("Desktop Hermes · Board owner").assertIsDisplayed()
        compose.onNodeWithText("Saved snapshot · 2m ago").assertIsDisplayed()
        compose.onNodeWithText("Search loaded tasks").performTextInput("release")
        compose.onNodeWithText(task.title).assertDoesNotExist()
        compose.onNodeWithText(second.title).performClick()
        compose.onNodeWithText("Task release_notes").assertIsDisplayed()
        compose.onNodeWithText("Back to board").performClick()
        compose.onNodeWithText("Search loaded tasks").assertIsDisplayed()
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
