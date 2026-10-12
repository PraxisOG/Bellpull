package org.praxisog.hermes

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*

internal interface CodingCache {
    fun readCoding(id: String): CodingSnapshot?
    fun writeCoding(value: CodingSnapshot)
}

/** Read-only navigation observer. No run admission, reply, steering or Stop calls. */
internal class CodingActivityWorkspace(private val scope: CoroutineScope, private val client: HermesClient,
    private val cache: CodingCache? = null, supported: Boolean = false,
    private val pollMs: Long = 1000) {
    var supported by mutableStateOf(supported); private set
    var snapshot by mutableStateOf<CodingSnapshot?>(null); private set
    var sessionId by mutableStateOf<String?>(null); private set
    var loading by mutableStateOf(false); private set
    var disconnected by mutableStateOf(false); private set
    var problem by mutableStateOf<String?>(null); private set
    var workspace by mutableStateOf<CodingWorkspaceSnapshot?>(null); private set
    var workspaceLoading by mutableStateOf(false); private set
    var workspaceProblem by mutableStateOf<String?>(null); private set
    var selectedWork by mutableStateOf<String?>(null); private set
    private var generation = 0L
    private var job: Job? = null
    private var workspaceJob: Job? = null
    private var visible = false
    private var closed = false
    fun configure(value: Boolean) {
        if (value == supported) return
        supported = value
        sessionId?.let { observe(it, visible) }
    }
    fun observe(id: String, active: Boolean) {
        if (closed) return
        job?.cancel(); job = null; generation++
        workspaceJob?.cancel(); workspaceLoading = false
        visible = active
        if (sessionId != id) {
            sessionId = id; snapshot = cache?.readCoding(id); disconnected = snapshot != null
            workspaceJob?.cancel(); workspace = null; selectedWork = null; workspaceLoading = false; workspaceProblem = null
        }
        loading = active && supported && snapshot == null
        if (!active || !supported) return
        val token = generation
        job = scope.launch {
            var failures = 0
            while (isActive && !closed && token == generation) {
                val old = snapshot
                val result = withContext(Dispatchers.IO) { client.codingActivity(id, old?.cursor) }
                ensureActive()
                if (token != generation) return@launch
                loading = false
                when (result) {
                    is HermesResult.Ok -> {
                        val combined = runCatching { result.value.merge(old) }.getOrNull()
                        if (combined == null) {
                            problem = "Activity continuity changed. Refresh to reload its snapshot."; disconnected = true; return@launch
                        }
                        snapshot = combined
                        if (old?.cursor != combined.cursor || old?.works != combined.works || old?.gap != combined.gap) cache?.writeCoding(combined)
                        disconnected = false; problem = null; failures = 0
                    }
                    is HermesResult.Err -> {
                        disconnected = true; problem = result.error.toUserMessage(); failures++
                        if (result.error is HermesError.AuthFailed || result.error is HermesError.SessionNotFound ||
                            (result.error as? HermesError.ServerError)?.status in listOf(401,404)) return@launch
                    }
                }
                delay(if (failures == 0) pollMs else minOf(15000, pollMs * (1L shl failures.coerceAtMost(4))))
            }
        }
    }
    fun retry() { snapshot = null; sessionId?.let { observe(it, visible) } }
    fun inspect(workId: String, fileId: String? = null) {
        if (closed || snapshot?.works?.none { it.id == workId } != false) return
        workspaceJob?.cancel(); selectedWork = workId; workspaceLoading = true; workspaceProblem = null
        val token = generation
        workspaceJob = scope.launch {
            val result = withContext(Dispatchers.IO) { client.codingWorkspace(workId, fileId) }
            ensureActive()
            if (closed || token != generation) return@launch
            workspaceLoading = false
            when (result) {
                is HermesResult.Ok -> workspace = result.value
                is HermesResult.Err -> workspaceProblem = result.error.toUserMessage()
            }
        }
    }
    fun closeWorkspace() { workspaceJob?.cancel(); selectedWork = null; workspace = null; workspaceLoading = false; workspaceProblem = null }
    fun close() { closed = true; generation++; job?.cancel(); closeWorkspace() }
}
