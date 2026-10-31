package org.praxisog.hermes.model

import org.junit.Assert.*
import org.junit.Test

class KanbanTest {
    private val board = KanbanBoard("a".repeat(32), "default", "Tasks", "b".repeat(64), "Desktop")
    private val task = KanbanTask("task_1", "Test", "triage", "c".repeat(64))
    private fun rejects(block: () -> Unit) { try { block(); fail("Invalid Kanban data was accepted") } catch (_: IllegalArgumentException) {} }
    @Test fun invalidPathsOwnerAndExecutionMovesAreRejected() {
        for (slug in listOf("../other", "a/b", "%2f", "", "UPPER")) rejects { board.copy(slug = slug).validated() }
        rejects { board.copy(ownerId = "server-name").validated() }
        rejects { task.copy(allowedMoves = listOf("ready")).validated() }
        rejects { task.copy(id = "../task").validated() }
        rejects { task.copy(status = "invented").validated() }
    }
    @Test fun boundedSnapshotsRejectAmbiguousOrIncompleteClaims() {
        val snapshot = KanbanSnapshot(board = board, columns = KANBAN_COLUMNS, tasks = listOf(task), total = 1, complete = true, observedAt = 1000)
        assertEquals(snapshot, snapshot.validated())
        rejects { snapshot.copy(tasks = listOf(task, task), total = 2).validated() }
        rejects { snapshot.copy(total = 2).validated() }
        rejects { snapshot.copy(observedAt = 0).validated() }
        rejects { snapshot.copy(columns = listOf("todo", "done")).validated() }
    }
    @Test fun savedQueueMustRetainOwnerAndUniqueOperationIdentity() {
        val mutation = KanbanMutation("d".repeat(32), board.ownerId, board.id, task.id, task.revision, "comment", comment = "Pending")
        val pending = KanbanPending(board, mutation, "unknown")
        assertEquals(1, KanbanLocalState(pending = listOf(pending)).validated().pending.size)
        rejects { KanbanLocalState(pending = listOf(pending, pending)).validated() }
        rejects { KanbanLocalState(pending = listOf(pending.copy(mutation = mutation.copy(ownerId = "e".repeat(64))))).validated() }
        rejects { KanbanReceipt(board = board, operationId = mutation.operationId, state = "applied").validated() }
    }
    @Test fun taskFieldsRejectBadValuesButUnfinishedFormsCanBeSaved() {
        val draft = KanbanFormDraft("draft_" + "d".repeat(32), KANBAN_EMPTY_REVISION, priority = "-")
        assertEquals(draft, draft.validated()); assertNull(draft.fields())
        assertEquals("work", draft.copy(title = "Task", assignee = " Work ", priority = "4").fields()!!.assignee)
        rejects { KanbanTaskFields("", "", null, 0).validated() }
        rejects { KanbanTaskFields("Task", "", "../work", 0).validated() }
        rejects { KanbanTaskFields("Task", "x".repeat(16385), null, 0).validated() }
    }
    @Test fun rejectedCreationMayHaveNoNativeTaskButAppliedReceiptMustHaveOne() {
        val receipt = KanbanReceipt(board = board, operationId = "d".repeat(32), state = "conflict", observedAt = 1)
        assertEquals(receipt, receipt.validated())
        rejects { receipt.copy(state = "applied").validated() }
        val fields = KanbanTaskFields("Task", "Description", null, 0)
        val create = KanbanMutation("d".repeat(32), board.ownerId, board.id, "draft_" + "e".repeat(32), KANBAN_EMPTY_REVISION, "create", fields = fields)
        assertEquals(create, create.validated())
        rejects { create.copy(expectedRevision = task.revision).validated() }
        rejects { create.copy(status = "running").validated() }
    }
}
