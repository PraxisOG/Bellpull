package org.praxisog.hermes

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import java.util.UUID

/** Navigation and server snapshots only. Project changes never own/cancel runs or drafts. */
internal class ProjectWorkspace(
    private val scope: CoroutineScope,
    private val client: HermesClient,
    private val cache: ProjectCache,
    supported: Boolean,
) {
    var supported by mutableStateOf(supported)
        private set
    var snapshot by mutableStateOf(cache.readProjects() ?: ProjectSnapshot())
        private set
    var selectedScope by mutableStateOf(cache.projectScope())
        private set
    var refreshing by mutableStateOf(false)
        private set
    var mutating by mutableStateOf(false)
        private set
    var fresh by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var pendingChat by mutableStateOf(cache.pendingProjectChat())
        private set
    private var closed = false
    private var revision = 0L
    private var refreshJob: Job? = null
    val selectedProject get() = snapshot.projects.firstOrNull { it.id == selectedScope }
    val scopeTitle get() = when (selectedScope) {
        ALL_PROJECT_CHATS -> "All conversations"
        UNFILED_PROJECT_CHATS -> "Unfiled"
        else -> selectedProject?.name ?: "Project"
    }
    val canEdit get() = supported && fresh && !refreshing && !mutating && !closed

    fun configure(supported: Boolean) {
        if (this.supported == supported) return
        this.supported = supported
        fresh = false
        if (supported) refresh() else { refreshJob?.cancel(); refreshing = false }
    }

    fun choose(value: String) {
        if (value != ALL_PROJECT_CHATS && value != UNFILED_PROJECT_CHATS && snapshot.projects.none { it.id == value }) return
        selectedScope = value
        cache.rememberProjectScope(value)
    }

    fun sessions(all: List<Session>, scopeId: String = selectedScope): List<Session> = all.filter { session ->
        when (scopeId) {
            ALL_PROJECT_CHATS -> true
            UNFILED_PROJECT_CHATS -> snapshot.projectFor(session.id) == null
            else -> snapshot.projectFor(session.id) == scopeId
        }
    }

    fun pinnedIds(all: List<Session>) = all.filter { snapshot.membership(it.id)?.pinned == true }.map { it.id }.toSet()
    fun aliases(id: String): Set<String> = snapshot.membership(id)?.let { (it.sessionIds + it.sessionId).toSet() } ?: setOf(id)
    fun unreadIds(all: List<Session>, unread: Set<String>) = all.filter { aliases(it.id).any(unread::contains) }.map { it.id }.toSet()
    fun runLabels(all: List<Session>, labels: Map<String, String>) = all.mapNotNull { session ->
        aliases(session.id).firstNotNullOfOrNull { labels[it] }?.let { session.id to it }
    }.toMap()

    fun projectIndicators(projectId: String, unread: Set<String>, running: Set<String>): Pair<Int, Int> {
        val members = snapshot.memberships.filter { it.projectId == projectId }
        return members.count { (it.sessionIds + it.sessionId).any(unread::contains) } to
            members.count { (it.sessionIds + it.sessionId).any(running::contains) }
    }

    private fun apply(value: ProjectSnapshot) {
        if (closed) return
        snapshot = value
        fresh = true
        if (!cache.writeProjects(value)) error = "Projects are available, but their local cache could not be saved."
        if (selectedScope !in setOf(ALL_PROJECT_CHATS, UNFILED_PROJECT_CHATS) && value.projects.none { it.id == selectedScope })
            choose(UNFILED_PROJECT_CHATS)
    }

    fun refresh() {
        if (closed || !supported || mutating) return
        val token = ++revision
        refreshJob?.cancel()
        refreshing = true
        refreshJob = scope.launch {
            val result = withContext(Dispatchers.IO) { client.projects() }
            if (closed || token != revision) return@launch
            refreshing = false
            when (result) {
                is HermesResult.Ok -> { error = null; apply(result.value) }
                is HermesResult.Err -> { fresh = false; error = projectMessage(result.error) }
            }
        }
    }

    fun save(project: ConversationProject, creating: Boolean, onDone: () -> Unit) = change(
        ProjectMutation(if (creating) "create" else "update", UUID.randomUUID().toString().replace("-", ""), snapshot.revision,
            projectId = project.id, name = project.name, description = project.description,
            color = project.color, icon = project.icon, archived = if (creating) null else project.archived), onDone)
    fun delete(projectId: String, onDone: () -> Unit) = change(
        ProjectMutation("delete", UUID.randomUUID().toString().replace("-", ""), snapshot.revision, projectId = projectId), onDone)
    fun move(sessionId: String, projectId: String?, onDone: () -> Unit) = change(
        ProjectMutation("move", UUID.randomUUID().toString().replace("-", ""), snapshot.revision, projectId = projectId, sessionId = sessionId), onDone)
    fun pin(sessionId: String, value: Boolean) = change(
        ProjectMutation("pin", UUID.randomUUID().toString().replace("-", ""), snapshot.revision, sessionId = sessionId, pinned = value))

    private fun change(value: ProjectMutation, onDone: () -> Unit = {}) {
        if (!canEdit) return
        mutating = true
        revision++
        refreshJob?.cancel()
        refreshing = false
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { client.mutateProject(value) }
            if (closed) return@launch
            when (result) {
                is HermesResult.Ok -> { apply(result.value); mutating = false; onDone() }
                is HermesResult.Err -> {
                    // Read authoritative state after rejection/uncertain delivery. No stale
                    // optimistic membership is rendered, merged or automatically replayed.
                    val current = withContext(Dispatchers.IO) { client.projects() }
                    if (closed) return@launch
                    if (current is HermesResult.Ok) apply(current.value) else fresh = false
                    error = projectMessage(result.error)
                    mutating = false
                }
            }
        }
    }

    /** Capture scope before the caller launches work; switching folders cannot redirect creation. */
    fun creationRequest(title: String?, selection: ModelSelection?): suspend () -> HermesResult<Session> {
        val project = selectedScope.takeUnless { it in setOf(ALL_PROJECT_CHATS, UNFILED_PROJECT_CHATS) }
        if (project == null) return { withContext(Dispatchers.IO) { client.createSession(title, selection) } }
        return { createChat(project, title, selection) }
    }

    suspend fun retryPending(): HermesResult<Session> {
        val pending = pendingChat ?: return HermesResult.Err(HermesError.BadRequest("There is no pending project chat.", null))
        return createChat(pending.projectId, pending.title, pending.selection)
    }

    fun stopTrackingCreation(): Boolean {
        if (mutating || closed || !cache.writePendingProjectChat(null)) return false
        pendingChat = null
        error = null
        return true
    }

    private suspend fun createChat(projectId: String, title: String?, selection: ModelSelection?): HermesResult<Session> {
        if (!canEdit) return HermesResult.Err(HermesError.BadRequest("Reconnect and refresh projects before starting a project chat.", null))
        val old = pendingChat
        if (old == null && snapshot.projects.firstOrNull { it.id == projectId }?.archived != false)
            return HermesResult.Err(HermesError.BadRequest("Restore this project before starting a new chat.", null))
        if (old != null && (old.projectId != projectId || old.title != title || old.selection != selection))
            return HermesResult.Err(HermesError.BadRequest("A previous project chat needs recovery. Open Projects and retry its creation first.", null))
        val request = old ?: PendingProjectChat("api_" + UUID.randomUUID().toString().replace("-", ""), projectId, snapshot.revision, title, selection)
        if (!cache.writePendingProjectChat(request)) return HermesResult.Err(HermesError.BadRequest("Could not save project chat recovery data.", null))
        pendingChat = request
        mutating = true
        revision++
        refreshJob?.cancel()
        refreshing = false
        val result = withContext(Dispatchers.IO) { client.createProjectChat(request) }
        if (closed) return result
        if (result is HermesResult.Ok) {
            if (cache.writePendingProjectChat(null)) pendingChat = null
            else error = "Chat created, but its recovery record could not be cleared. Retrying uses the same chat."
        } else if (result is HermesResult.Err) {
            error = projectMessage(result.error)
            // A revision conflict happened before reservation/native creation. A fresh
            // user retry may use the latest revision; transport ambiguity retains its ID.
            if ((result.error as? HermesError.ServerError)?.code == "project_revision_conflict") {
                if (cache.writePendingProjectChat(null)) pendingChat = null
            }
        }
        val current = withContext(Dispatchers.IO) { client.projects() }
        if (!closed) {
            if (current is HermesResult.Ok) apply(current.value) else fresh = false
            mutating = false
        }
        return result
    }

    fun close() { closed = true; revision++; refreshJob?.cancel() }
}

private fun projectMessage(error: HermesError) = (error as? HermesError.ServerError)?.message ?: error.toUserMessage()
