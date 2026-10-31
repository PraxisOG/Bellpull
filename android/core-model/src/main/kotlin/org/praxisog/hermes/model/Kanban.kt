package org.praxisog.hermes.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

val KANBAN_COLUMNS = listOf("triage", "todo", "scheduled", "ready", "running", "blocked", "review", "done", "archived")
private val hex32 = Regex("[a-f0-9]{32}")
private val hex64 = Regex("[a-f0-9]{64}")
val KANBAN_EMPTY_REVISION = "0".repeat(64)
fun kanbanSlug(value: String) = value.matches(Regex("[a-z0-9][a-z0-9_-]{0,63}"))
fun kanbanTaskId(value: String) = value.matches(Regex("[a-zA-Z0-9_-]{1,128}"))
// Match the native Python contract's Unicode character count and never split a surrogate pair.
fun String.kanbanLength(): Int = codePointCount(0, length)
fun String.kanbanTake(limit: Int): String = substring(0, offsetByCodePoints(0, minOf(limit, kanbanLength())))

@Serializable data class KanbanBoard(
    val id: String, val slug: String, val name: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("owner_name") val ownerName: String,
    val description: String = "", val archived: Boolean = false, val scope: String = "installation",
    @SerialName("can_create") val canCreate: Boolean = false,
) {
    fun validated() = apply { require(hex32.matches(id) && hex64.matches(ownerId) && kanbanSlug(slug)); require(name.isNotBlank() && name.kanbanLength() <= 200 && ownerName.kanbanLength() <= 200 && scope == "installation") }
    val key get() = "$ownerId/$id"
}
@Serializable data class KanbanComment(val id: Long, val author: String, val body: String, @SerialName("created_at") val createdAt: Long)
@Serializable data class KanbanAttempt(val id: Long, val profile: String? = null, val status: String,
    val outcome: String? = null, val summary: String? = null, val error: String? = null,
    @SerialName("started_at") val startedAt: Long, @SerialName("ended_at") val endedAt: Long? = null)
@Serializable data class KanbanFile(val id: Long, val filename: String, val path: String, val size: Long,
    val device: String, val hash: String? = null)
@Serializable data class KanbanTask(
    val id: String, val title: String, val status: String, val revision: String,
    val assignee: String? = null, @SerialName("project_id") val projectId: String? = null,
    @SerialName("session_id") val sessionId: String? = null, val priority: Long = 0,
    @SerialName("current_run_id") val currentRunId: Long? = null,
    @SerialName("allowed_moves") val allowedMoves: List<String> = emptyList(),
    val body: String = "", val result: String = "",
    val comments: List<KanbanComment> = emptyList(), val runs: List<KanbanAttempt> = emptyList(),
    val parents: List<String> = emptyList(), val children: List<String> = emptyList(), val files: List<KanbanFile> = emptyList(),
    @SerialName("details_complete") val detailsComplete: Boolean = true,
    val editable: Boolean = false,
) {
    fun validated() = apply {
        require(kanbanTaskId(id) && hex64.matches(revision) && status in KANBAN_COLUMNS)
        require(title.kanbanLength() <= 500 && body.kanbanLength() <= 16384 && result.kanbanLength() <= 16384)
        require(allowedMoves.size <= 2 && allowedMoves.all { it in listOf("triage", "blocked") })
        require(comments.size <= 50 && runs.size <= 20 && files.size <= 50 && parents.size <= 100 && children.size <= 100)
        require(comments.all { it.body.kanbanLength() <= 16384 })
    }
}
@Serializable data class KanbanBoards(val version: Int = 1, val boards: List<KanbanBoard>, @SerialName("observed_at_ms") val observedAt: Long) {
    fun validated() = apply { require(version == 1 && boards.size <= 100 && observedAt > 0); boards.forEach { it.validated() }; require(boards.map { it.slug }.distinct().size == boards.size) }
}
@Serializable data class KanbanSnapshot(val version: Int = 1, val board: KanbanBoard,
    val columns: List<String>, val tasks: List<KanbanTask>, val total: Int, val complete: Boolean,
    @SerialName("observed_at_ms") val observedAt: Long) {
    fun validated() = apply {
        require(version == 1 && observedAt > 0 && columns == KANBAN_COLUMNS && tasks.size <= 500 && total >= tasks.size)
        require(!complete || total == tasks.size); board.validated(); tasks.forEach { it.validated() }
        require(tasks.map { it.id }.distinct().size == tasks.size)
    }
}
@Serializable data class KanbanDetail(val version: Int = 1, val board: KanbanBoard, val task: KanbanTask,
    @SerialName("observed_at_ms") val observedAt: Long) {
    fun validated() = apply { require(version == 1 && observedAt > 0); board.validated(); task.validated() }
}
@Serializable data class KanbanMutation(
    @SerialName("operation_id") val operationId: String,
    @SerialName("owner_id") val ownerId: String, @SerialName("board_id") val boardId: String,
    @SerialName("task_id") val taskId: String, @SerialName("expected_revision") val expectedRevision: String,
    val action: String, val status: String? = null, val comment: String? = null,
    val fields: KanbanTaskFields? = null,
) {
    fun validated() = apply {
        require(hex32.matches(operationId) && hex64.matches(ownerId) && hex32.matches(boardId) && kanbanTaskId(taskId) && hex64.matches(expectedRevision))
        require((action == "comment" && !comment.isNullOrBlank() && comment.kanbanLength() <= 8000 && status == null && fields == null) ||
            (action == "move" && status in listOf("triage", "blocked") && comment == null && fields == null) ||
            (action in listOf("create", "edit") && fields != null && status == null && comment == null))
        fields?.validated()
        if (action == "create") require(taskId.matches(Regex("draft_[a-f0-9]{32}")) && expectedRevision == KANBAN_EMPTY_REVISION)
    }
}
@Serializable data class KanbanReceipt(val version: Int = 1, val board: KanbanBoard,
    @SerialName("operation_id") val operationId: String, val state: String, val message: String? = null,
    val task: KanbanTask? = null, @SerialName("observed_at_ms") val observedAt: Long = 0) {
    fun validated() = apply {
        require(version == 1 && hex32.matches(operationId) && state in listOf("applied", "conflict", "not_found")); board.validated()
        if (state != "not_found") require(observedAt > 0)
        if (state == "applied") require(task != null)
        task?.validated()
    }
}
@Serializable data class KanbanPending(val board: KanbanBoard, val mutation: KanbanMutation,
    val state: String = "queued", val problem: String? = null)
@Serializable data class KanbanTaskFields(val title: String, val body: String, val assignee: String?, val priority: Int) {
    fun validated() = apply {
        require(title.isNotBlank() && title.kanbanLength() <= 500 && body.kanbanLength() <= 16384)
        require(assignee == null || kanbanSlug(assignee))
    }
}
/** Keep the opened revision with the draft so background refresh cannot authorize overwriting newer edits. */
@Serializable data class KanbanFormDraft(val taskId: String, val revision: String,
    val title: String = "", val body: String = "", val assignee: String = "", val priority: String = "0") {
    fun fields(): KanbanTaskFields? = runCatching {
        KanbanTaskFields(title.trim(), body, assignee.trim().lowercase().ifBlank { null }, priority.toInt()).validated()
    }.getOrNull()
    fun validated() = apply {
        require(kanbanTaskId(taskId) && hex64.matches(revision) && title.kanbanLength() <= 500 && body.kanbanLength() <= 16384 && assignee.length <= 64 && priority.length <= 12)
    }
}
@Serializable data class KanbanLocalState(val boards: List<KanbanBoard> = emptyList(),
    val snapshots: List<KanbanSnapshot> = emptyList(), val details: List<KanbanDetail> = emptyList(),
    val pending: List<KanbanPending> = emptyList(), val drafts: Map<String, String> = emptyMap(),
    val selectedBoard: String? = null, val boardView: Boolean = false,
    val forms: Map<String, KanbanFormDraft> = emptyMap()) {
    fun validated() = apply {
        require(boards.size <= 100 && snapshots.size <= 10 && details.size <= 10 && pending.size <= 100)
        boards.forEach { it.validated() }; snapshots.forEach { it.validated() }; details.forEach { it.validated() }
        pending.forEach {
            it.board.validated(); it.mutation.validated()
            require(it.state in listOf("queued", "unknown", "conflict") && it.board.id == it.mutation.boardId && it.board.ownerId == it.mutation.ownerId)
        }
        require(pending.map { it.mutation.operationId }.distinct().size == pending.size)
        require(drafts.values.all { it.kanbanLength() <= 8000 })
        require(forms.size <= 100); forms.values.forEach { it.validated() }
    }
}
