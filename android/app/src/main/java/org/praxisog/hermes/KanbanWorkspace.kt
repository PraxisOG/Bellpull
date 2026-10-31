package org.praxisog.hermes

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import java.util.UUID

internal interface KanbanCache {
    fun readKanban(): KanbanLocalState
    fun writeKanban(value: KanbanLocalState): Boolean
}

/** One durable queue per saved connection. Unknown operations retain their exact payload. */
internal class KanbanWorkspace(private val scope: CoroutineScope, private val cache: KanbanCache) {
    private val initial = runCatching { cache.readKanban().validated() }
    var state by mutableStateOf(initial.getOrDefault(KanbanLocalState()))
        private set
    var supported by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var fresh by mutableStateOf(false)
        private set
    var problem by mutableStateOf<String?>(if (initial.isFailure) "Saved Tasks could not be read. Recovery data is preserved; edits are disabled." else null)
        private set
    var selectedTask by mutableStateOf<String?>(null)
        private set
    var editorId by mutableStateOf<String?>(null)
        private set
    private var client: HermesClient? = null
    private var closed = false
    private val lock = Mutex()
    private var syncJob: Job? = null
    private var refreshRequested = false
    val board get() = state.boards.firstOrNull { it.slug == state.selectedBoard }
    val snapshot get() = board?.let { b -> state.snapshots.firstOrNull { it.board.key == b.key } }
    val detail get() = board?.let { b -> state.details.firstOrNull { it.board.key == b.key && it.task.id == selectedTask } }
    val task get() = detail?.task ?: visibleTasks.firstOrNull { it.id == selectedTask }
    val visibleTasks get() = state.pending.filter { it.board.key == board?.key && it.mutation.action == "create" }.mapNotNull { item ->
        item.mutation.fields?.let { fields -> KanbanTask(item.mutation.taskId, fields.title, "triage", KANBAN_EMPTY_REVISION,
            assignee = fields.assignee, priority = fields.priority.toLong(), body = fields.body) }
    } + snapshot?.tasks.orEmpty()
    val formKey get() = editorId?.let { id -> board?.let { "${it.key}/$id" } }
    val form get() = formKey?.let { state.forms[it] }
    fun key(board: KanbanBoard, task: String) = "${board.key}/$task"
    fun pending(task: String) = state.pending.firstOrNull { it.board.key == board?.key && it.mutation.taskId == task }
    fun draft(task: String) = board?.let { state.drafts[key(it, task)].orEmpty() }.orEmpty()

    fun configure(client: HermesClient?, available: Boolean) {
        if (closed || initial.isFailure) return
        this.client = client
        supported = available
        fresh = false
        if (!available) problem = "This connection does not advertise native Kanban. Saved tasks remain readable."
    }

    private suspend fun save(change: (KanbanLocalState) -> KanbanLocalState): Boolean {
        if (closed || initial.isFailure) return false
        val value = change(state)
        val saved = withContext(Dispatchers.IO) {
            try { cache.writeKanban(value.validated()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        }
        if (closed) return false
        if (saved) state = value else problem = "Could not save Tasks on this phone. No new operation was sent."
        return saved
    }

    private fun local(change: (KanbanLocalState) -> KanbanLocalState, after: () -> Unit = {}) {
        if (closed) return
        scope.launch { lock.withLock { if (save(change)) after() } }
    }

    fun choose(slug: String) {
        if (state.boards.none { it.slug == slug }) return
        selectedTask = null; editorId = null; fresh = false
        local({ it.copy(selectedBoard = slug) }) { refresh() }
    }
    fun view(board: Boolean) = local({ it.copy(boardView = board) })
    fun open(id: String) { if (!kanbanTaskId(id)) return; selectedTask = id; refresh() }
    fun review(item: KanbanPending) {
        if (state.boards.none { it.key == item.board.key }) return
        local({ it.copy(selectedBoard = item.board.slug) }) { selectedTask = item.mutation.taskId; fresh = false; refresh() }
    }
    fun back() { selectedTask = null }
    fun startForm(taskId: String? = null) {
        val board = board ?: return
        if (board.archived || (taskId == null && !board.canCreate)) return
        val current = taskId?.let { id -> detail?.task?.takeIf { it.id == id && it.editable } }
        if (taskId != null && current == null) return
        val key = "${board.key}/${taskId ?: "new"}"
        if (key !in state.forms && state.forms.size >= 100) { problem = "Too many saved task drafts. Discard a draft before starting another."; return }
        val draft = if (current == null) KanbanFormDraft("draft_" + UUID.randomUUID().toString().replace("-", ""), KANBAN_EMPTY_REVISION)
            else KanbanFormDraft(current.id, current.revision, current.title, current.body, current.assignee.orEmpty(), current.priority.toString())
        var opened = false
        local({ old ->
            if (this.board?.key != board.key) old
            else if (key !in old.forms && old.forms.size >= 100) {
                problem = "Too many saved task drafts. Discard a draft before starting another."
                old
            } else {
                opened = true
                old.copy(forms = old.forms + (key to (old.forms[key] ?: draft)))
            }
        }) { if (opened) editorId = taskId ?: "new" }
    }
    fun saveForm(draft: KanbanFormDraft) {
        if (editorId == null) return
        val key = formKey ?: return
        local({ old ->
            val saved = old.forms[key]
            if (saved?.taskId != draft.taskId || saved.revision != draft.revision) old
            else old.copy(forms = old.forms + (key to draft.validated()))
        })
    }
    fun closeForm(discard: Boolean = false) {
        val key = formKey
        if (discard && key != null) local({ it.copy(forms = it.forms - key) }) { editorId = null }
        else editorId = null
    }
    fun submitForm(draft: KanbanFormDraft) {
        if (editorId == null || form?.taskId != draft.taskId || form?.revision != draft.revision) return
        val board = board ?: return
        val key = formKey ?: return
        val fields = draft.fields() ?: return
        val create = editorId == "new"
        if (board.archived || (create && !board.canCreate) || pending(draft.taskId) != null || state.pending.size >= 100) return
        val mutation = KanbanMutation(UUID.randomUUID().toString().replace("-", ""), board.ownerId, board.id,
            draft.taskId, draft.revision, if (create) "create" else "edit", fields = fields).validated()
        var queued = false
        local({ old ->
            if (old.forms[key]?.taskId != draft.taskId || old.forms[key]?.revision != draft.revision ||
                old.pending.any { it.board.key == board.key && it.mutation.taskId == draft.taskId }) old
            else if (old.pending.size >= 100) {
                problem = "The pending edit queue is full. Your task draft is saved."
                old.copy(forms = old.forms + (key to draft))
            } else {
                queued = true
                old.copy(forms = old.forms - key, pending = old.pending + KanbanPending(board, mutation))
            }
        }) { if (queued) { editorId = null; selectedTask = draft.taskId; refresh() } }
    }
    fun editPending(item: KanbanPending) {
        if (item.state !in listOf("queued", "conflict") || item.board.key != board?.key) return
        val fields = item.mutation.fields ?: return
        val id = if (item.mutation.action == "create") "new" else item.mutation.taskId
        val key = "${item.board.key}/$id"
        if (key !in state.forms && state.forms.size >= 100) { problem = "Too many saved task drafts. Discard a draft first."; return }
        val draft = KanbanFormDraft(item.mutation.taskId, item.mutation.expectedRevision, fields.title, fields.body, fields.assignee.orEmpty(), fields.priority.toString())
        var opened = false
        local({ old ->
            // Delivery may acquire the lock before this tap. Never remove an
            // admitted operation using the queued item captured by the UI.
            val current = old.pending.firstOrNull { it.mutation.operationId == item.mutation.operationId }
            if (current == null || current.state !in listOf("queued", "conflict") || current.mutation != item.mutation || board?.key != item.board.key) old
            else if ((key !in old.forms && old.forms.size >= 100) || old.forms[key]?.let { it.taskId != draft.taskId } == true) {
                problem = "Finish or discard another saved task draft before editing this one."
                old
            } else {
                opened = true
                old.copy(pending = old.pending - current, forms = old.forms + (key to draft))
            }
        }) { if (opened) editorId = id }
    }
    fun draft(id: String, text: String) {
        val board = board ?: return
        if (text.kanbanLength() > 8000) return
        local({ it.copy(drafts = it.drafts + (key(board, id) to text)) })
    }

    fun comment(id: String, text: String = draft(id)) = enqueue(id, "comment", null, text)
    fun move(id: String, target: String) = enqueue(id, "move", target, null)
    private fun enqueue(id: String, action: String, target: String?, text: String?) {
        val board = board ?: return
        val task = detail?.task?.takeIf { it.id == id } ?: snapshot?.tasks?.firstOrNull { it.id == id } ?: return
        if (board.archived || pending(id) != null) return
        if (action == "comment" && text.isNullOrBlank()) return
        if (action == "move" && target !in task.allowedMoves) return
        val mutation = KanbanMutation(UUID.randomUUID().toString().replace("-", ""), board.ownerId, board.id,
            id, task.revision, action, target, text).validated()
        var queued = false
        local({ old ->
            if (old.pending.any { it.board.key == board.key && it.mutation.taskId == id }) old
            else if (old.pending.size >= 100) {
                problem = "The pending edit queue is full. Resolve an existing edit before adding another."
                old.copy(drafts = if (action == "comment") old.drafts + (key(board, id) to text.orEmpty()) else old.drafts)
            } else {
                queued = true
                old.copy(pending = old.pending + KanbanPending(board, mutation),
                    drafts = if (action == "comment") old.drafts - key(board, id) else old.drafts)
            }
        }) { if (queued) refresh() }
    }

    fun discard(operation: String, edit: Boolean = false) {
        local({ old ->
            val item = old.pending.firstOrNull { it.mutation.operationId == operation && it.state in listOf("queued", "conflict") }
            if (item == null) old else old.copy(pending = old.pending - item,
                drafts = if (edit && item.mutation.comment != null) old.drafts + (key(item.board, item.mutation.taskId) to item.mutation.comment.orEmpty()) else old.drafts)
        })
    }

    /** An explicit review creates a new intent; it cannot change an unknown request. */
    fun applyReviewed(operation: String) {
        val item = state.pending.firstOrNull { it.mutation.operationId == operation && it.state == "conflict" } ?: return
        if (!fresh || board?.key != item.board.key || board?.archived != false) return
        val current = detail
        val create = item.mutation.action == "create"
        if (create) { if (board?.canCreate != true) return }
        else {
            if (current == null || current.board.key != item.board.key || current.task.id != item.mutation.taskId) return
            if (item.mutation.action == "move" && item.mutation.status !in current.task.allowedMoves) return
            if (item.mutation.action == "edit" && !current.task.editable) return
        }
        local({ old -> old.copy(pending = old.pending.map { if (it.mutation.operationId != operation) it else
            it.copy(mutation = it.mutation.copy(operationId = UUID.randomUUID().toString().replace("-", ""),
                expectedRevision = if (create) KANBAN_EMPTY_REVISION else current!!.task.revision), state = "queued", problem = null) }) }) { refresh() }
    }

    fun refresh() {
        if (closed || !supported || client == null) return
        refreshRequested = true
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            do {
                refreshRequested = false
                lock.withLock {
                    if (closed) return@withLock
                    busy = true
                    try { synchronize(checkNotNull(client)) }
                    finally { busy = false }
                }
            } while (refreshRequested && !closed)
        }
    }

    private fun failure(error: HermesError) {
        fresh = false
        problem = (error as? HermesError.ServerError)?.message ?: error.toUserMessage()
    }

    private fun unavailable(error: HermesError) = error is HermesError.ServerError && error.status in listOf(404, 410)

    private suspend fun synchronize(api: HermesClient) {
        val listing = withContext(Dispatchers.IO) { api.kanbanBoards() }
        if (closed) return
        if (listing is HermesResult.Err) { failure(listing.error); return }
        val boards = (listing as HermesResult.Ok).value.boards
        if (!save { it.copy(boards = boards, selectedBoard = it.selectedBoard?.takeIf { name -> boards.any { b -> b.slug == name } } ?: boards.firstOrNull()?.slug) }) return
        val currentBoard = board ?: return
        val loaded = withContext(Dispatchers.IO) { api.kanbanBoard(currentBoard.slug) }
        if (closed) return
        if (loaded is HermesResult.Err) { failure(loaded.error); return }
        val snapshot = (loaded as HermesResult.Ok).value
        if (snapshot.board.key != currentBoard.key) { problem = "Board identity changed. Refresh before editing."; fresh = false; return }
        if (!save { it.copy(snapshots = (listOf(snapshot) + it.snapshots.filterNot { s -> s.board.key == currentBoard.key }).take(10)) }) return
        fresh = true; problem = null
        // Reconcile all boards' queues against the authoritative board list.
        for (pending in state.pending.toList()) {
            if (closed) return
            if (pending.state == "conflict") continue
            val owner = boards.firstOrNull { it.key == pending.board.key && it.slug == pending.board.slug }
            if (owner == null) {
                update(pending, if (pending.state == "queued") "conflict" else "unknown", "Original board is unavailable. This edit cannot move to another board.")
                continue
            }
            var shouldSend = pending.state == "queued"
            if (!shouldSend) {
                val receipt = withContext(Dispatchers.IO) { api.kanbanReceipt(owner.slug, pending.mutation.operationId) }
                if (closed) return
                if (receipt is HermesResult.Err) {
                    if (unavailable(receipt.error)) {
                        if (!update(pending, "unknown", "The original task or receipt is unavailable. Delivery remains unresolved.")) return
                        continue
                    }
                    failure(receipt.error); return
                }
                val value = (receipt as HermesResult.Ok).value
                if (value.board.key != owner.key || (pending.mutation.action != "create" && value.task != null && value.task?.id != pending.mutation.taskId)) {
                    problem = "Operation receipt belongs to a different board or task."; return
                }
                if (value.state != "not_found") { accept(pending, value); continue }
                // Atomic, retained receipts make an identical retry safe. Its
                // original revision stays intact; the server resolves conflicts.
                shouldSend = true
            } else if (pending.mutation.action == "create") {
                if (!owner.canCreate) { update(pending, "conflict", "This board does not currently allow task creation."); continue }
            } else {
                val latest = withContext(Dispatchers.IO) { api.kanbanTask(owner.slug, pending.mutation.taskId) }
                if (closed) return
                if (latest is HermesResult.Err) {
                    if (unavailable(latest.error)) {
                        if (!update(pending, "conflict", "The original task is no longer available. This unsent edit was kept for review.")) return
                        continue
                    }
                    failure(latest.error); return
                }
                val value = (latest as HermesResult.Ok).value
                if (value.board.key != owner.key || value.task.revision != pending.mutation.expectedRevision ||
                    (pending.mutation.action == "edit" && !value.task.editable)) {
                    update(pending, "conflict", "Task changed. Open it and review this edit."); continue
                }
            }
            if (shouldSend) {
                if (!update(pending, "unknown", "Checking delivery with Hermes…")) return
                val receipt = withContext(Dispatchers.IO) { api.kanbanMutation(owner.slug, pending.mutation) }
                if (closed) return
                when (receipt) {
                    is HermesResult.Ok -> accept(pending, receipt.value)
                    is HermesResult.Err -> {
                        if (unavailable(receipt.error)) {
                            if (!update(pending, "unknown", "The original task is unavailable. Delivery remains unresolved; its operation is retained.")) return
                        } else { failure(receipt.error); return }
                    }
                }
            }
        }
        // Read current state after receipts. An old receipt must not overwrite a
        // newer desktop edit with the historic task embedded in that receipt.
        val refreshed = withContext(Dispatchers.IO) { api.kanbanBoard(currentBoard.slug) }
        if (closed) return
        if (refreshed is HermesResult.Err) { failure(refreshed.error); return }
        val current = (refreshed as HermesResult.Ok).value
        if (current.board.key != currentBoard.key) { fresh = false; problem = "Board was replaced. Refresh to load its new identity."; return }
        save { it.copy(snapshots = (listOf(current) + it.snapshots.filterNot { s -> s.board.key == currentBoard.key }).take(10)) }
        selectedTask?.takeUnless { id -> pending(id)?.mutation?.action == "create" }?.let { id ->
            val detail = withContext(Dispatchers.IO) { api.kanbanTask(currentBoard.slug, id) }
            if (closed) return
            if (detail is HermesResult.Err) failure(detail.error)
            else {
                val value = (detail as HermesResult.Ok).value
                if (value.board.key == currentBoard.key) save { it.copy(details = (listOf(value) + it.details.filterNot { d -> d.board.key == value.board.key && d.task.id == id }).take(10)) }
            }
        }
    }

    private suspend fun update(item: KanbanPending, status: String, message: String?) = save { old ->
        old.copy(pending = old.pending.map { if (it.mutation.operationId == item.mutation.operationId) it.copy(state = status, problem = message) else it })
    }
    private suspend fun accept(item: KanbanPending, receipt: KanbanReceipt) {
        if (receipt.state == "applied") {
            if (save { it.copy(pending = it.pending.filterNot { p -> p.mutation.operationId == item.mutation.operationId }) } &&
                item.mutation.action == "create" && selectedTask == item.mutation.taskId) selectedTask = receipt.task?.id
        }
        else update(item, "conflict", receipt.message ?: "Review this edit against the current task.")
    }
    fun close() { closed = true; syncJob?.cancel(); client = null }
}
