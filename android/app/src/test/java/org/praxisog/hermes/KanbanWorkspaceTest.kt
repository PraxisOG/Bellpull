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

class KanbanWorkspaceTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val board = KanbanBoard("b".repeat(32), "default", "Work", "a".repeat(64), "Desktop")
    private val task = KanbanTask("task-one", "Finish mobile board", "triage", "c".repeat(64), allowedMoves = listOf("blocked"))
    private fun snapshot(b: KanbanBoard = board, t: KanbanTask = task) = KanbanSnapshot(board = b, columns = KANBAN_COLUMNS, tasks = listOf(t), total = 1, complete = true, observedAt = 1000)
    private inner class Cache : KanbanCache {
        @Volatile var value = KanbanLocalState(boards = listOf(board), snapshots = listOf(snapshot()), selectedBoard = "default")
        var writable = true
        override fun readKanban() = value
        override fun writeKanban(value: KanbanLocalState): Boolean { if (!writable) return false; this.value = value; return true }
    }
    private inner class Host {
        var current = task
        var owner = board
        var offline = false
        var dropNextPost = false
        val posted = CopyOnWriteArrayList<KanbanMutation>()
        val calls = CopyOnWriteArrayList<String>()
        val receipts = mutableMapOf<String, KanbanReceipt>()
        val api = HermesClient("https://fixture.invalid/p/work", { "fixture" }, httpClient = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            calls += request.method + " " + request.url.encodedPath
            assertEquals("Bearer fixture", request.header("Authorization"))
            if (offline) throw IOException("Synthetic disconnection")
            val path = request.url.encodedPath
            val body = when {
                request.method == "POST" -> {
                    val buffer = Buffer(); request.body!!.writeTo(buffer)
                    val mutation = json.decodeFromString<KanbanMutation>(buffer.readUtf8())
                    posted += mutation
                    val receipt = receipts.getOrPut(mutation.operationId) {
                        current = current.copy(revision = "d".repeat(64), comments = listOf(KanbanComment(1, "Bellpull", mutation.comment.orEmpty(), 1)))
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
}
