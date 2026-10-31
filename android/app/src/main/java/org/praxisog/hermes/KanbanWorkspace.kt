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
    private var client: HermesClient? = null
    private var closed = false
    private val lock = Mutex()
    private var syncJob: Job? = null
    private var refreshRequested = false
    val board get() = state.boards.firstOrNull { it.slug == state.selectedBoard }
    val snapshot get() = board?.let { b -> state.snapshots.firstOrNull { it.board.key == b.key } }
    val detail get() = board?.let { b -> state.details.firstOrNull { it.board.key == b.key && it.task.id == selectedTask } }
    val task get() = detail?.task ?: snapshot?.tasks?.firstOrNull { it.id == selectedTask }
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
            try { cache.writeKanban(value) }
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
        selectedTask = null; fresh = false
        local({ it.copy(selectedBoard = slug) }) { refresh() }
    }
    fun view(board: Boolean) = local({ it.copy(boardView = board) })
    fun open(id: String) { if (!kanbanTaskId(id)) return; selectedTask = id; refresh() }
    fun review(item: KanbanPending) {
        if (state.boards.none { it.key == item.board.key }) return
        local({ it.copy(selectedBoard = item.board.slug) }) { selectedTask = item.mutation.taskId; fresh = false; refresh() }
    }
    fun back() { selectedTask = null }
    fun draft(id: String, text: String) {
        val board = board ?: return
        if (text.length > 8000) return
        local({ it.copy(drafts = it.drafts + (key(board, id) to text)) })
    }

    fun comment(id: String, text: String = draft(id)) = enqueue(id, "comment", null, text)
    fun move(id: String, target: String) = enqueue(id, "move", target, null)
    private fun enqueue(id: String, action: String, target: String?, text: String?) {
        val board = board ?: return
        val task = detail?.task?.takeIf { it.id == id } ?: snapshot?.tasks?.firstOrNull { it.id == id } ?: return
        if (board.archived || pending(id) != null || state.pending.size >= 100) return
        if (action == "comment" && text.isNullOrBlank()) return
        if (action == "move" && target !in task.allowedMoves) return
        val mutation = KanbanMutation(UUID.randomUUID().toString().replace("-", ""), board.ownerId, board.id,
            id, task.revision, action, target, text).validated()
        local({ old ->
            if (old.pending.any { it.board.key == board.key && it.mutation.taskId == id }) old
            else old.copy(pending = old.pending + KanbanPending(board, mutation),
                drafts = if (action == "comment") old.drafts - key(board, id) else old.drafts)
        }) { refresh() }
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
        val current = detail ?: return
        if (!fresh || current.board.key != item.board.key || current.task.id != item.mutation.taskId || current.board.archived) return
        if (item.mutation.action == "move" && item.mutation.status !in current.task.allowedMoves) return
        local({ old -> old.copy(pending = old.pending.map { if (it.mutation.operationId != operation) it else
            it.copy(mutation = it.mutation.copy(operationId = UUID.randomUUID().toString().replace("-", ""),
                expectedRevision = current.task.revision), state = "queued", problem = null) }) }) { refresh() }
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
                if (receipt is HermesResult.Err) { failure(receipt.error); return }
                val value = (receipt as HermesResult.Ok).value
                if (value.board.key != owner.key || (value.task != null && value.task?.id != pending.mutation.taskId)) {
                    problem = "Operation receipt belongs to a different board or task."; return
                }
                if (value.state != "not_found") { accept(pending, value); continue }
                // Atomic, retained receipts make an identical retry safe. Its
                // original revision stays intact; the server resolves conflicts.
                shouldSend = true
            } else {
                val latest = withContext(Dispatchers.IO) { api.kanbanTask(owner.slug, pending.mutation.taskId) }
                if (closed) return
                if (latest is HermesResult.Err) { failure(latest.error); return }
                val value = (latest as HermesResult.Ok).value
                if (value.board.key != owner.key || value.task.revision != pending.mutation.expectedRevision) {
                    update(pending, "conflict", "Task changed. Open it and review this edit."); continue
                }
            }
            if (shouldSend) {
                if (!update(pending, "unknown", "Checking delivery with Hermes…")) return
                val receipt = withContext(Dispatchers.IO) { api.kanbanMutation(owner.slug, pending.mutation) }
                if (closed) return
                when (receipt) {
                    is HermesResult.Ok -> accept(pending, receipt.value)
                    is HermesResult.Err -> { failure(receipt.error); return }
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
        selectedTask?.let { id ->
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
        if (receipt.state == "applied") save { it.copy(pending = it.pending.filterNot { p -> p.mutation.operationId == item.mutation.operationId }) }
        else update(item, "conflict", receipt.message ?: "Review this edit against the current task.")
    }
    fun close() { closed = true; syncJob?.cancel(); client = null }
}
