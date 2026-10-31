package org.praxisog.hermes

import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class KanbanWorkspaceTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val board = KanbanBoard("b".repeat(32), "default", "Work", "a".repeat(64), "Desktop", canCreate = true)
    private val task = KanbanTask("task-one", "Finish mobile board", "triage", "c".repeat(64), allowedMoves = listOf("blocked"), editable = true)
    private fun snapshot(b: KanbanBoard = board, t: KanbanTask = task) = KanbanSnapshot(board = b, columns = KANBAN_COLUMNS, tasks = listOf(t), total = 1, complete = true, observedAt = 1000)
    private inner class Cache : KanbanCache {
        @Volatile var value = KanbanLocalState(boards = listOf(board), snapshots = listOf(snapshot()), selectedBoard = "default")
        @Volatile var writes = 0
        var writable = true
        override fun readKanban() = value
        override fun writeKanban(value: KanbanLocalState): Boolean { if (!writable) return false; this.value = value; writes++; return true }
    }
    private inner class Host {
        var current = task
        var owner = board
        var offline = false
        var dropNextPost = false
        var missingTasks = emptySet<String>()
        val posted = CopyOnWriteArrayList<KanbanMutation>()
        val calls = CopyOnWriteArrayList<String>()
        val receipts = mutableMapOf<String, KanbanReceipt>()
        val api = HermesClient("https://fixture.invalid/p/work", { "fixture" }, httpClient = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            calls += request.method + " " + request.url.encodedPath
            assertEquals("Bearer fixture", request.header("Authorization"))
            if (offline) throw IOException("Synthetic disconnection")
            val path = request.url.encodedPath
            fun missing() = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(404).message("Not Found")
                .body("""{"error":{"message":"Task no longer exists","code":"not_found"}}""".toResponseBody("application/json".toMediaType())).build()
            if (path.contains("/tasks/") && path.substringAfterLast('/') in missingTasks) return@addInterceptor missing()
            val body = when {
                request.method == "POST" -> {
                    val buffer = Buffer(); request.body!!.writeTo(buffer)
                    val mutation = json.decodeFromString<KanbanMutation>(buffer.readUtf8())
                    if (mutation.taskId in missingTasks) return@addInterceptor missing()
                    posted += mutation
                    val receipt = receipts.getOrPut(mutation.operationId) {
                        current = if (mutation.fields == null) current.copy(revision = "d".repeat(64), comments = listOf(KanbanComment(1, "Bellpull", mutation.comment.orEmpty(), 1)))
                        else mutation.fields!!.let { fields -> current.copy(id = if (mutation.action == "create") "t_native_created" else current.id,
                            title = fields.title, body = fields.body, assignee = fields.assignee, priority = fields.priority.toLong(), revision = "d".repeat(64)) }
                        KanbanReceipt(board = owner, operationId = mutation.operationId, state = "applied", task = current, observedAt = 2000)
                    }
                    if (dropNextPost) { dropNextPost = false; throw IOException("Lost committed response") }
                    json.encodeToString(receipt)
                }
                path.endsWith("/boards") -> json.encodeToString(KanbanBoards(boards = listOf(owner), observedAt = 2000))
                path.contains("/operations/") -> json.encodeToString(receipts[path.substringAfterLast('/')] ?: KanbanReceipt(board = owner, operationId = path.substringAfterLast('/'), state = "not_found"))
                path.contains("/tasks/") -> json.encodeToString(KanbanDetail(board = owner, task = current, observedAt = 2000))
                else -> json.encodeToString(snapshot(owner, current))
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build())
    }
    private fun waitFor(check: () -> Boolean) {
        val until = System.nanoTime() + 10_000_000_000
        while (!check()) { if (System.nanoTime() > until) error("Timed out"); Thread.sleep(10) }
    }
    @After fun cleanup() { scope.cancel() }

    @Test fun offlineDraftAndQueuedCommentSurviveRestartThenSendOnceToTheirOwner() {
        val cache = Cache(); val host = Host()
        var workspace = KanbanWorkspace(scope, cache)
        workspace.configure(host.api, false)
        workspace.comment(task.id, "Keep my exact words")
        waitFor { cache.value.pending.size == 1 }
        val operation = cache.value.pending.single().mutation
        assertTrue(host.calls.isEmpty()); assertEquals("queued", cache.value.pending.single().state)
        workspace.close(); workspace = KanbanWorkspace(scope, cache)
        workspace.configure(host.api, true); workspace.refresh()
        waitFor { host.posted.size == 1 && !workspace.busy && workspace.state.pending.isEmpty() }
        assertEquals(operation, host.posted.single())
        assertTrue(host.calls.all { it.substringAfter(' ').startsWith("/p/work/api/bellpull/kanban/") })
        workspace.close(); host.api.close()
    }

    @Test fun committedLostResponseRecoversByReceiptAfterRestartWithoutAnotherPost() {
        val cache = Cache(); val host = Host(); host.dropNextPost = true
        var workspace = KanbanWorkspace(scope, cache); workspace.configure(host.api, true)
        workspace.comment(task.id, "Only once")
        waitFor { host.posted.size == 1 && !workspace.busy && workspace.problem != null }
        assertEquals("unknown", cache.value.pending.single().state)
        workspace.close(); workspace = KanbanWorkspace(scope, cache); workspace.configure(host.api, true); workspace.refresh()
        waitFor { !workspace.busy && cache.value.pending.isEmpty() }
        assertEquals(1, host.posted.size)
        assertTrue(host.calls.any { it.contains("/operations/") })
        workspace.close(); host.api.close()
    }

    @Test fun unknownAbsentReceiptRetriesTheSameIdentityAndPayload() {
        val cache = Cache(); val host = Host()
        val mutation = KanbanMutation("e".repeat(32), board.ownerId, board.id, task.id, task.revision, "comment", comment = "Exact original")
        cache.value = cache.value.copy(pending = listOf(KanbanPending(board, mutation, "unknown")))
        val workspace = KanbanWorkspace(scope, cache); workspace.configure(host.api, true); workspace.refresh()
        waitFor { host.posted.size == 1 && !workspace.busy && cache.value.pending.isEmpty() }
        assertEquals(mutation, host.posted.single())
        val lookup = host.calls.indexOfFirst { it.contains("/operations/") }
        assertTrue(lookup in 0 until host.calls.indexOfFirst { it.startsWith("POST") })
        workspace.close(); host.api.close()
    }

    @Test fun staleOfflineEditRequiresExplicitReviewAgainstFreshDetail() {
        val cache = Cache(); val host = Host()
        val workspace = KanbanWorkspace(scope, cache); workspace.configure(host.api, false)
        workspace.comment(task.id, "Review me"); waitFor { cache.value.pending.size == 1 }
        val old = cache.value.pending.single().mutation.operationId
        host.current = task.copy(revision = "f".repeat(64))
        workspace.configure(host.api, true); workspace.open(task.id)
        waitFor { !workspace.busy && workspace.detail != null && workspace.state.pending.single().state == "conflict" }
        assertTrue(host.posted.isEmpty())
        workspace.applyReviewed(old)
        waitFor { host.posted.size == 1 && !workspace.busy && workspace.state.pending.isEmpty() }
        assertNotEquals(old, host.posted.single().operationId)
        assertEquals("f".repeat(64), host.posted.single().expectedRevision)
        workspace.close(); host.api.close()
    }

    @Test fun replacedBoardNeverReceivesOldQueuedWork() {
        val cache = Cache(); val host = Host()
        val workspace = KanbanWorkspace(scope, cache); workspace.comment(task.id, "Old board")
        waitFor { cache.value.pending.size == 1 }
        host.owner = board.copy(id = "f".repeat(32))
        workspace.configure(host.api, true); workspace.refresh()
        waitFor { !workspace.busy && cache.value.pending.single().state == "conflict" }
        assertTrue(host.posted.isEmpty())
        assertEquals(board.id, cache.value.pending.single().mutation.boardId)
        workspace.close(); host.api.close()
    }

    @Test fun failedPersistenceAndClosedWorkspaceCannotSend() {
        val cache = Cache(); val host = Host(); cache.writable = false
        val workspace = KanbanWorkspace(scope, cache); workspace.configure(host.api, true)
        workspace.comment(task.id, "Must not escape")
        waitFor { workspace.problem != null }
        assertTrue(host.calls.isEmpty()); assertTrue(cache.value.pending.isEmpty())
        workspace.close(); workspace.refresh(); workspace.comment(task.id, "Closed")
        Thread.sleep(50); assertTrue(host.calls.isEmpty()); host.api.close()
    }

    @Test fun cancellationIsOnlyAvailableForUnsentOrRejectedIntents() {
        val cache = Cache(); val workspace = KanbanWorkspace(scope, cache)
        workspace.comment(task.id, "Editable")
        waitFor { workspace.state.pending.size == 1 }
        workspace.discard(workspace.state.pending.single().mutation.operationId, edit = true)
        waitFor { workspace.state.pending.isEmpty() }
        assertEquals("Editable", workspace.draft(task.id))
        workspace.close()
        val mutation = KanbanMutation("e".repeat(32), board.ownerId, board.id, task.id, task.revision, "comment", comment = "Sent")
        cache.value = cache.value.copy(pending = listOf(KanbanPending(board, mutation, "unknown")))
        val restored = KanbanWorkspace(scope, cache)
        restored.discard(mutation.operationId)
        Thread.sleep(50); assertEquals("unknown", restored.state.pending.single().state)
        restored.close()
    }

    @Test fun unsentCreationAndFormSurviveRestartWithNativeIdentityAdoptedOnlyAfterReceipt() {
        val cache = Cache(); val host = Host()
        var workspace = KanbanWorkspace(scope, cache)
        workspace.startForm(); waitFor { workspace.form != null }
        val form = workspace.form!!.copy(title = "Create while offline", body = "Exact draft", assignee = "work", priority = "5")
        workspace.saveForm(form); waitFor { cache.value.forms.values.single().title == form.title }
        workspace.close(); workspace = KanbanWorkspace(scope, cache)
        workspace.startForm(); waitFor { workspace.form != null }
        assertEquals(form, workspace.form)
        workspace.submitForm(workspace.form!!); waitFor { workspace.state.pending.size == 1 }
        val operation = workspace.state.pending.single().mutation
        assertEquals("create", operation.action); assertEquals(form.taskId, workspace.visibleTasks.first().id)
        assertTrue(host.calls.isEmpty())
        workspace.close(); workspace = KanbanWorkspace(scope, cache)
        workspace.open(form.taskId); workspace.configure(host.api, true); workspace.refresh()
        waitFor { !workspace.busy && workspace.state.pending.isEmpty() && workspace.detail?.task?.id == "t_native_created" }
        assertEquals(operation, host.posted.single()); assertEquals("t_native_created", workspace.selectedTask)
        assertEquals("Exact draft", workspace.task!!.body); workspace.close(); host.api.close()
    }

    @Test fun lostCreateResponseRecoversServerAssignedIdWithoutDuplicateOrDraftLookup() {
        val cache = Cache(); val host = Host(); host.dropNextPost = true
        var workspace = KanbanWorkspace(scope, cache)
        workspace.startForm(); waitFor { workspace.form != null }
        val form = workspace.form!!.copy(title = "Create once")
        workspace.configure(host.api, true); workspace.submitForm(form)
        waitFor { !workspace.busy && workspace.state.pending.singleOrNull()?.state == "unknown" }
        workspace.close(); workspace = KanbanWorkspace(scope, cache)
        workspace.open(form.taskId); workspace.configure(host.api, true); workspace.refresh()
        waitFor { !workspace.busy && workspace.state.pending.isEmpty() && workspace.selectedTask == "t_native_created" }
        assertEquals(1, host.posted.size)
        assertTrue(host.calls.none { it.contains("/tasks/draft_") })
        workspace.close(); host.api.close()
    }

    @Test fun backgroundRefreshCannotReplaceTheVersionCapturedByAnOpenEditor() {
        val cache = Cache(); val host = Host()
        val workspace = KanbanWorkspace(scope, cache)
        workspace.configure(host.api, true); workspace.open(task.id)
        waitFor { !workspace.busy && workspace.detail != null }
        workspace.startForm(task.id); waitFor { workspace.form != null }
        val edited = workspace.form!!.copy(title = "My title")
        host.current = task.copy(title = "Concurrent desktop title", revision = "f".repeat(64))
        workspace.refresh(); waitFor { !workspace.busy && workspace.detail?.task?.title == host.current.title }
        workspace.submitForm(edited)
        waitFor { !workspace.busy && workspace.state.pending.singleOrNull()?.state == "conflict" }
        assertEquals(task.revision, workspace.state.pending.single().mutation.expectedRevision)
        assertTrue(host.posted.isEmpty())
        workspace.applyReviewed(workspace.state.pending.single().mutation.operationId)
        waitFor { !workspace.busy && workspace.state.pending.isEmpty() }
        assertEquals("My title", host.current.title)
        assertEquals("f".repeat(64), host.posted.single().expectedRevision)
        workspace.close(); host.api.close()
    }

    @Test fun staleQueuedEditTapCannotRemoveAnOperationWithUnknownDelivery() {
        val cache = Cache(); val host = Host(); host.dropNextPost = true
        val workspace = KanbanWorkspace(scope, cache)
        workspace.startForm(); waitFor { workspace.form != null }
        workspace.submitForm(workspace.form!!.copy(title = "Keep the delivery receipt"))
        waitFor { workspace.state.pending.size == 1 }
        val queued = workspace.state.pending.single()
        workspace.configure(host.api, true); workspace.refresh()
        waitFor { !workspace.busy && workspace.state.pending.singleOrNull()?.state == "unknown" }
        val writes = cache.writes
        workspace.editPending(queued)
        waitFor { cache.writes > writes }
        assertEquals("unknown", cache.value.pending.single().state)
        assertEquals(queued.mutation, cache.value.pending.single().mutation)
        assertTrue(cache.value.forms.isEmpty())
        workspace.refresh()
        waitFor { !workspace.busy && workspace.state.pending.isEmpty() }
        assertEquals(1, host.posted.size)
        workspace.close(); host.api.close()
    }

    @Test fun deletedTaskDoesNotBlockUnrelatedQueuedEdits() {
        val cache = Cache(); val host = Host(); host.missingTasks = setOf("deleted-task")
        val missing = KanbanMutation("e".repeat(32), board.ownerId, board.id, "deleted-task", task.revision, "comment", comment = "Keep for review")
        val valid = missing.copy(operationId = "f".repeat(32), taskId = task.id, comment = "Still deliver this")
        cache.value = cache.value.copy(pending = listOf(KanbanPending(board, missing), KanbanPending(board, valid)))
        val workspace = KanbanWorkspace(scope, cache); workspace.configure(host.api, true); workspace.refresh()
        waitFor { host.calls.any { it.endsWith("/tasks/deleted-task") } && !workspace.busy }
        assertEquals(listOf(valid), host.posted.toList())
        assertEquals("conflict", workspace.state.pending.single().state)
        assertEquals(missing, workspace.state.pending.single().mutation)
        workspace.close(); host.api.close()
    }

    @Test fun unknownDeletedTaskKeepsItsIdentityWithoutBlockingOtherWork() {
        val cache = Cache(); val host = Host(); host.missingTasks = setOf("deleted-task")
        val missing = KanbanMutation("e".repeat(32), board.ownerId, board.id, "deleted-task", task.revision, "comment", comment = "Unresolved")
        val valid = missing.copy(operationId = "f".repeat(32), taskId = task.id, comment = "Deliver separately")
        cache.value = cache.value.copy(pending = listOf(KanbanPending(board, missing, "unknown"), KanbanPending(board, valid)))
        val workspace = KanbanWorkspace(scope, cache); workspace.configure(host.api, true); workspace.refresh()
        waitFor { host.calls.any { it.startsWith("POST") } && !workspace.busy }
        assertEquals(listOf(valid), host.posted.toList())
        assertEquals("unknown", workspace.state.pending.single().state)
        assertEquals(missing, workspace.state.pending.single().mutation)
        workspace.close(); host.api.close()
    }

    @Test fun rapidQueueActionsRespectCapacityAndRemainReadableAfterRestart() {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val localScope = CoroutineScope(SupervisorJob() + dispatcher)
        val cache = Cache()
        val second = task.copy(id = "task-two")
        cache.value = cache.value.copy(snapshots = listOf(snapshot().copy(tasks = listOf(task, second), total = 2)),
            pending = (1..99).map { index -> KanbanPending(board, KanbanMutation(index.toString(16).padStart(32, '0'),
                board.ownerId, board.id, "queued-$index", task.revision, "comment", comment = "Existing $index")) })
        val workspace = KanbanWorkspace(localScope, cache)
        try {
            runBlocking(dispatcher) {
                workspace.comment(task.id, "Last slot")
                workspace.comment(second.id, "Must retain my draft")
                workspace.view(true)
            }
            waitFor { workspace.state.boardView }
            assertEquals(100, cache.value.pending.size)
            assertEquals("Must retain my draft", workspace.draft(second.id))
            workspace.close()
            val restored = KanbanWorkspace(localScope, cache)
            assertEquals(100, restored.state.pending.size); assertNull(restored.problem)
            restored.close()
        } finally { workspace.close(); localScope.cancel(); dispatcher.close() }
    }

    @Test fun lateFormCallbackCannotRecreateAnAlreadySubmittedDraft() {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val localScope = CoroutineScope(SupervisorJob() + dispatcher)
        val cache = Cache(); val workspace = KanbanWorkspace(localScope, cache)
        try {
            workspace.startForm(); waitFor { workspace.form != null }
            val form = workspace.form!!.copy(title = "Submit once")
            runBlocking(dispatcher) {
                workspace.submitForm(form)
                workspace.saveForm(form.copy(title = "Late callback"))
                workspace.view(true)
            }
            waitFor { workspace.state.boardView }
            assertEquals("Submit once", workspace.state.pending.single().mutation.fields!!.title)
            assertTrue(cache.value.forms.isEmpty())
        } finally { workspace.close(); localScope.cancel(); dispatcher.close() }
    }
}
