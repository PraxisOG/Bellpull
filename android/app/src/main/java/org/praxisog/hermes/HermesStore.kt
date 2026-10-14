package org.praxisog.hermes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.HermesError
import org.praxisog.hermes.model.HermesResult
import org.praxisog.hermes.model.Message
import org.praxisog.hermes.model.Session
import org.praxisog.hermes.model.StreamEvent

/**
 * Holds the chat screens' state and owns every call into [HermesClient].
 *
 * Deliberately not a cache. "Hermes is the source of truth. The phone overwrites local state
 * from Hermes on sync" — so opening a session always refetches its history, and so does every
 * stream termination. There is no local transcript to reconcile.
 */
internal class HermesStore(
    private val scope: CoroutineScope,
    private val client: HermesClient,
    /** Which `source` to filter the session list by; null lists every source. */
    sessionSource: String? = null,
    /** Null disables caching, which is what the contract tests want. */
    private val cache: SessionSnapshots? = null,
    private val clock: () -> Double = { System.currentTimeMillis() / 1000.0 },
    supportsMessageBranching: Boolean = false,
    val drafts: ChatDrafts = ChatDrafts(),
    supportsPromptEditing: Boolean = false,
    val readState: ConversationReadState = ConversationReadState(),
    durableRetention: Long? = null,
    journal: RunJournal? = null,
) {
    var supportsMessageBranching by mutableStateOf(supportsMessageBranching)
        private set
    var supportsPromptEditing by mutableStateOf(supportsPromptEditing)
        private set
    fun updateCapabilities(value: HermesClient.Capabilities) {
        supportsMessageBranching = value.sessionForkAtMessage
        supportsPromptEditing = value.sessionForkBeforeMessage
        runs?.interactiveApprovals = value.interactiveApprovals
    }
    var sessionSource by mutableStateOf(sessionSource)
        private set
    fun chooseSessionSource(source: String?) {
        if (source == sessionSource) return
        sessionSource = source
        sessions = UiState.Loading
        loadSessions()
    }
    val runs = if (durableRetention != null && journal != null)
        DurableRuns(scope, client, journal, drafts, durableRetention, clock) else null
    var onReply: (String, Message) -> Unit = { _, _ -> }
    var onAttention: (String, String) -> Unit = { _, _ -> }
    var onSessions: (List<Session>) -> Unit = {}
    var defaultModel: () -> org.praxisog.hermes.model.ModelSelection? = { null }
    var newSessionRequest: (String?, org.praxisog.hermes.model.ModelSelection?) -> (suspend () -> HermesResult<Session>) =
        { title, selection -> { io { client.createSession(title, selection) } } }
    val runPresentation: RunPresentation? get() = openSessionId?.let { runs?.states?.get(it) }
    val runLabels: Map<String, String> get() = runs?.states.orEmpty().mapNotNull { (id, value) -> value.label?.let { id to it } }.toMap()

    init {
        runs?.onChanged = { id -> if (id == openSessionId) showDurableRun(id) }
        runs?.onHistory = { id, messages ->
            if (id == openSessionId) {
                history = UiState.Ready(messages)
                pendingUserText = null
            }
        }
        runs?.onCompleted = { id, reply ->
            if (reply != null) {
                discoveredReply(id, reply)
            }
            loadSessions()
        }
        runs?.onAttention = { id, identity -> onAttention(id, identity) }
    }

    fun startRecovery() { runs?.restore() }
    fun discoveredReply(id: String, reply: Message) {
        val old = readState.snapshot()[id]?.unreadReply ?: 0
        readState.completed(id, reply.id)
        if (reply.id > old) {
            completionRevision++
            onReply(id, reply)
        }
    }
    private val legacyRecoveryJobs = mutableMapOf<String, Job>()
    fun close() { runs?.close(); legacyRecoveryJobs.values.forEach { it.cancel() }; legacyRecoveryJobs.clear() }
    fun retryRun() { openSessionId?.let { runs?.retry(it) } }
    fun abandonRunAfterReview() { openSessionId?.let { runs?.abandonAfterReview(it) } }
    fun respondToApproval(requestId: String, choice: String) { openSessionId?.let { runs?.respond(it, requestId, choice) } }
    private fun showDurableRun(id: String) {
        val run = runs?.states?.get(id) ?: return
        turn = run.turn
        val saved = (history as? UiState.Ready)?.value.orEmpty()
        pendingUserText = run.submission.text.takeIf { it.isNotBlank() && run.submission.runId != null &&
            !run.submission.historySynced && !run.submission.trackingDismissed && saved.none { message ->
            message.id !in run.submission.previousIds && message.role == "user" && message.content?.trim() == it.trim()
        } }
    }
    /** A list reveal request, including a new reply in the chat already at index zero. */
    var completionRevision by mutableStateOf(0L)
        private set
    var sessions by mutableStateOf<UiState<List<Session>>>(UiState.Loading)
        private set
    var history by mutableStateOf<UiState<List<Message>>>(UiState.Loading)
        private set
    var turn by mutableStateOf<TurnState>(TurnState.Idle)
        private set
    var openSessionId by mutableStateOf<String?>(null)
        private set

    /** True while a create is in flight, so the button cannot be double-tapped. */
    var creatingSession by mutableStateOf(false)
        private set

    var branchingSession by mutableStateOf(false)
        private set
    var branchError by mutableStateOf<HermesError?>(null)
        private set

    /** Set when a create fails; cleared on the next attempt. */
    var createError by mutableStateOf<HermesError?>(null)
        private set

    /** Non-null when the list on screen is cached rather than live. */
    var offline by mutableStateOf<OfflineState?>(null)
        private set

    var searchQuery by mutableStateOf("")
        private set

    /** Launchable apps, supplied by the Activity — this class stays free of Android types. */
    var apps by mutableStateOf<List<LaunchableApp>>(emptyList())
        private set

    /**
     * Text to seed the composer with. Set when a search is handed to Hermes, so the prompt
     * the user typed in the search field arrives in the new session's composer rather than
     * being sent blind. Cleared once sent.
     */
    var pendingPrompt by mutableStateOf("")
        private set

    /** Accepted prompt shown in the transcript until its server history row arrives. */
    var pendingUserText by mutableStateOf<String?>(null)
        private set

    /** Non-null while a turn is in flight, so a stop has something to target. */
    private var activeRunId: String? = null
    private var stopRequested = false
    @Volatile private var sessionRevision = 0L
    private var historyRequest = 0L
    private var sessionsRequest = 0L
    private var turnHistoryIds = emptySet<Int>()
    private var sentPrompt: String? = null
    private data class SubmittedDraft(val revision: Long, val text: String, val previousIds: Set<Int>)
    private val submittedDrafts = mutableMapOf<String, SubmittedDraft>()
    val draft: androidx.compose.ui.text.input.TextFieldValue
        get() = openSessionId?.let { drafts[it] } ?: androidx.compose.ui.text.input.TextFieldValue()
    fun updateDraft(value: androidx.compose.ui.text.input.TextFieldValue) {
        openSessionId?.let { drafts.set(it, value) }
    }

    val openSession: Session?
        get() = (sessions as? UiState.Ready)?.value?.firstOrNull { it.id == openSessionId }

    // ---- sessions -------------------------------------------------------------

    /**
     * Fetch the session list, falling back to the cache when the fetch fails.
     *
     * The fallback is never silent: it sets [offline], which the list renders as a banner
     * naming both the staleness and which layer failed. A successful fetch overwrites the
     * cache wholesale and clears [offline] — Hermes is the source of truth, so there is no
     * merge and no way for stale local data to win.
     *
     * An empty cache is treated as no cache. Rendering zero cached sessions would read as
     * "you have no sessions", which is a different and wrong claim.
     */
    fun loadSessions() {
        val request = ++sessionsRequest
        scope.launch {
            if (sessions !is UiState.Ready) sessions = UiState.Loading
            val source = sessionSource
            val result = io { client.allSessions(source = source) }
            if (request != sessionsRequest) return@launch
            when (result) {
                is HermesResult.Ok -> {
                    offline = null
                    sessions = UiState.Ready(result.value.data)
                    cache?.write(result.value.data, clock())
                    onSessions(result.value.data)
                }
                is HermesResult.Err -> {
                    val cached = io { cache?.read() }
                    val permitted = cached?.sessions?.filter { source == null || it.source == source }.orEmpty()
                    if (cached != null && permitted.isNotEmpty()) {
                        offline = OfflineState(cached.cachedAtEpochSeconds, result.error)
                        sessions = UiState.Ready(permitted)
                    } else {
                        offline = null
                        sessions = UiState.Failed(result.error)
                    }
                }
            }
        }
    }

    /**
     * `POST /api/sessions`, then open the result.
     *
     * The list is refreshed rather than having the new session spliced in locally: Hermes is
     * the source of truth, and a locally-inserted row would be the one row on screen that
     * never came from the server.
     */
    fun newSession() {
        if (creatingSession) return
        creatingSession = true
        createError = null
        val request = newSessionRequest(null, defaultModel())
        scope.launch {
            when (val result = request()) {
                is HermesResult.Ok -> {
                    creatingSession = false
                    loadSessions()
                    openSession(result.value.id)
                }
                is HermesResult.Err -> {
                    creatingSession = false
                    createError = result.error
                }
            }
        }
    }

    fun branchMessage(message: Message) {
        val sourceId = openSessionId ?: return
        if (!supportsMessageBranching || branchingSession || turn is TurnState.Streaming) return
        val saved = (history as? UiState.Ready)?.value?.firstOrNull { it.id == message.id } ?: return
        if (saved.role != "assistant" || saved.content.isNullOrBlank()) return
        val revision = sessionRevision
        branchingSession = true
        branchError = null
        scope.launch {
            val result = io { client.branchSession(sourceId, saved.id) }
            branchingSession = false
            when (result) {
                is HermesResult.Ok -> {
                    loadSessions()
                    // Navigating during the request must not pull the user out of another chat.
                    if (revision == sessionRevision) openSession(result.value.id)
                }
                is HermesResult.Err -> if (revision == sessionRevision) branchError = result.error
            }
        }
    }

    fun forkAndEdit(message: Message, edited: String) {
        val sourceId = openSessionId ?: return
        if (!supportsPromptEditing || branchingSession || turn is TurnState.Streaming || edited.isBlank()) return
        val saved = (history as? UiState.Ready)?.value?.firstOrNull { it.id == message.id } ?: return
        if (saved.role != "user") return
        val revision = sessionRevision
        branchingSession = true
        branchError = null
        scope.launch {
            val result = io { client.branchBeforeMessage(sourceId, saved.id) }
            branchingSession = false
            when (result) {
                is HermesResult.Ok -> {
                    drafts.set(result.value.id, androidx.compose.ui.text.input.TextFieldValue(
                        edited, androidx.compose.ui.text.TextRange(edited.length)))
                    loadSessions()
                    if (revision == sessionRevision) openSession(result.value.id)
                }
                is HermesResult.Err -> if (revision == sessionRevision) branchError = result.error
            }
        }
    }

    fun dismissBranchError() { branchError = null }

    fun setQuery(value: String) {
        searchQuery = value
    }

    fun updateApps(value: List<LaunchableApp>) {
        apps = value
    }

    /**
     * Turn a search query into a Hermes turn: create a session, open it, and seed the
     * composer with the query.
     *
     * The prompt is *not* sent automatically. The roadmap's accept criterion is that a
     * non-matching query "lands in a new session with the text pre-filled", and that is the
     * right call — a search box is a low-confidence surface, so firing a turn (and its
     * inference cost, and possibly a shell command) off one keystroke too many would be
     * hostile. The user still presses Send.
     */
    fun askHermes(prompt: String) {
        if (prompt.isBlank() || creatingSession) return
        creatingSession = true
        createError = null
        val request = newSessionRequest(prompt.take(60), defaultModel())
        scope.launch {
            when (val result = request()) {
                is HermesResult.Ok -> {
                    creatingSession = false
                    drafts.set(result.value.id, androidx.compose.ui.text.input.TextFieldValue(
                        prompt, androidx.compose.ui.text.TextRange(prompt.length)))
                    pendingPrompt = prompt
                    searchQuery = ""
                    loadSessions()
                    openSession(result.value.id)
                }
                is HermesResult.Err -> {
                    creatingSession = false
                    createError = result.error
                }
            }
        }
    }

    fun openSession(sessionId: String) {
        pendingUserText = null
        sessionRevision++
        historyRequest++
        sentPrompt = null
        branchError = null
        history = UiState.Loading
        openSessionId = sessionId
        turn = TurnState.Idle
        activeRunId = null
        stopRequested = false
        showDurableRun(sessionId)
        refreshHistory()
    }

    fun closeSession() {
        pendingUserText = null
        sessionRevision++
        historyRequest++
        sentPrompt = null
        branchError = null
        openSessionId = null
        history = UiState.Loading
        turn = TurnState.Idle
    }

    fun refreshHistory() {
        val sessionId = openSessionId ?: return
        val revision = sessionRevision
        val request = ++historyRequest
        scope.launch {
            val result = io { client.completeHistory(sessionId) }
            if (revision != sessionRevision || request != historyRequest) return@launch
            when (result) {
                is HermesResult.Ok -> Snapshot.withMutableSnapshot {
                    // Publish the canonical history and remove its temporary stream copy in
                    // one snapshot, so there is no frame with two copies of the answer.
                    submittedDrafts[sessionId]?.let { submitted ->
                        if (result.value.data.any { it.id !in submitted.previousIds && it.role == "user" &&
                                it.content?.trim() == submitted.text.trim() }) {
                            drafts.accepted(sessionId, submitted.revision)
                            submittedDrafts.remove(sessionId)
                        }
                    }
                    history = UiState.Ready(result.value.data)
                    if (runs != null) {
                        showDurableRun(sessionId)
                        val record = runs.states[sessionId]?.submission
                        if (record != null) turn = reconcileDurableTurn(turn, result.value.data, record)
                    }
                    if (result.value.data.any { it.id !in turnHistoryIds && it.role == "user" &&
                            it.content?.trim() == pendingUserText?.trim() }) pendingUserText = null
                    turn = reconcileTurn(turn, result.value.data, turnHistoryIds, sentPrompt)
                }
                is HermesResult.Err -> {
                    // Keep the readable transcript and unsaved stream text on a failed
                    // refresh. Opening an unreadable session still presents its retry UI.
                    if (history !is UiState.Ready) history = UiState.Failed(result.error)
                }
            }
        }
    }

    // ---- turns ----------------------------------------------------------------

    /**
     * Stream one turn.
     *
     * The history refetch sits on *every* termination — clean, stopped, truncated or failed —
     * rather than on `run.completed`. Keying it to the terminal event left a hole: a stream
     * that dropped first would never refetch, so the transcript kept tool previews with no
     * output. Tool results only exist in history anyway, since `tool.completed` carries none.
     */
    fun send(text: String, bellpull: org.praxisog.hermes.model.BellpullRunOptions? = null): Boolean {
        val sessionId = openSessionId ?: return false
        if (text.isBlank() || turn is TurnState.Streaming || branchingSession) return false

        if (runs != null) {
            // Do not submit while the prior canonical history is unknown.
            val saved = (history as? UiState.Ready)?.value ?: return false
            pendingPrompt = ""
            return runs.send(sessionId, text, saved.map { it.id }.toSet(), bellpull)
        }

        val revision = sessionRevision
        legacyRecoveryJobs.remove(sessionId)?.cancel()
        turnHistoryIds = (history as? UiState.Ready)?.value.orEmpty().map { it.id }.toSet()
        sentPrompt = text
        val submitted = SubmittedDraft(drafts.revision(sessionId), text, turnHistoryIds)
        submittedDrafts[sessionId] = submitted
        stopRequested = false
        activeRunId = null
        pendingPrompt = ""
        turn = TurnState.Streaming(text = "", canStop = false)

        scope.launch {
            val accumulated = StringBuilder()
            val reasoning = StringBuilder()
            val live = LiveTranscript()
            var commentaryBoundary = false
            val result = io {
                client.streamTurn(sessionId, text) { event ->
                    // run.started acknowledges this request before prose OR tool output.
                    // Clear only the submitted revision, on the UI scope used by draft persistence.
                    // Even if the reader switched chats, this acknowledgement belongs to this draft.
                    if (event is StreamEvent.RunStarted && !event.runId.isNullOrBlank()) scope.launch {
                        if (submittedDrafts[sessionId] === submitted) {
                            drafts.accepted(sessionId, submitted.revision)
                            submittedDrafts.remove(sessionId)
                            if (revision == sessionRevision && (history as? UiState.Ready)?.value.orEmpty().none {
                                    it.id !in submitted.previousIds && it.role == "user" && it.content?.trim() == text.trim()
                                }) pendingUserText = text
                        }
                    }
                    // A response from a session we left must not overwrite the next chat.
                    if (revision != sessionRevision) return@streamTurn
                    live.accept(event)
                    when (event) {
                        is StreamEvent.RunStarted -> activeRunId = event.runId
                        is StreamEvent.AssistantDelta -> {
                            if (commentaryBoundary && event.delta.isNotEmpty() && accumulated.isNotEmpty()) accumulated.append("\n\n")
                            if (event.delta.isNotEmpty()) commentaryBoundary = false
                            accumulated.append(event.delta)
                            // canStop only once a delta has landed: stopping before the agent
                            // is registered returns 409 run_not_active (Gate 0d).
                            postStreaming(accumulated.toString(), tool = null, canStop = true, parts = live.parts)
                        }
                        is StreamEvent.AssistantCommentary -> if (!event.alreadyStreamed && event.text.isNotBlank()) {
                            if (accumulated.isNotEmpty()) accumulated.append("\n\n")
                            accumulated.append(event.text)
                            commentaryBoundary = true
                            postStreaming(accumulated.toString(), tool = null, canStop = true, parts = live.parts)
                        }
                        is StreamEvent.ToolProgress -> if (event.toolName == "_thinking" && event.delta != null) {
                            reasoning.append(event.delta)
                            postStreaming(accumulated.toString(), tool = null, canStop = true, reasoning = reasoning.toString(), parts = live.parts)
                        }
                        is StreamEvent.ToolStarted -> postStreaming(
                            accumulated.toString(),
                            tool = event.preview ?: event.toolName,
                            toolName = event.toolName,
                            canStop = true, parts = live.parts,
                        )
                        is StreamEvent.ToolCompleted -> postStreaming(
                            accumulated.toString(), tool = null, canStop = true, parts = live.parts,
                        )
                        else -> Unit
                    }
                }
            }

            loadSessions()
            if (result is HermesResult.Ok) scope.launch {
                // Completion belongs to the submitted conversation, even after navigation.
                // Only a fresh saved assistant reply earns an unread dot; tool activity,
                // failed requests and timestamps alone do not.
                val saved = io { client.completeHistory(sessionId) }
                if (saved is HermesResult.Ok) {
                    val fresh = saved.value.data.filter { it.id !in submitted.previousIds }
                    val promptIndex = fresh.indexOfLast { it.role == "user" && it.content?.trim() == submitted.text.trim() }
                    if (promptIndex >= 0) fresh.drop(promptIndex + 1).lastOrNull {
                        it.role == "assistant" && !it.content.isNullOrBlank()
                    }?.let { reply ->
                        readState.completed(sessionId, reply.id)
                        completionRevision++
                    }
                }
            }
            if (revision != sessionRevision) return@launch
            val text = accumulated.toString()
            turn = when {
                // Gate 0d: a stopped turn reports completed:true, interrupted:false, so this
                // can only come from the client knowing it asked.
                stopRequested -> TurnState.StoppedByUser(text)
                result is HermesResult.Ok -> TurnState.Finished(text)
                result is HermesResult.Err && result.error is HermesError.StreamTruncated ->
                    TurnState.Truncated(text, result.error)
                result is HermesResult.Err && text.isNotEmpty() ->
                    TurnState.Truncated(text, result.error)
                result is HermesResult.Err -> TurnState.Failed(result.error)
                else -> TurnState.Finished(text)
            }
            val completedRun = activeRunId
            activeRunId = null
            refreshHistory()
            if (result is HermesResult.Err && result.error is HermesError.StreamTruncated) {
                recoverLegacyReply(sessionId, submitted, completedRun)
            }
        }
        return true
    }

    /** A dropped legacy stream never resubmits the prompt. Keep checking the saved
     * transcript so a later reply appears without tapping the conversation again. */
    private fun recoverLegacyReply(sessionId: String, submitted: SubmittedDraft, runId: String?) {
        legacyRecoveryJobs.remove(sessionId)?.cancel()
        legacyRecoveryJobs[sessionId] = scope.launch {
            while (isActive) {
                delay(2_000)
                val status = runId?.let { io { client.runStatus(it) } }
                val saved = io { client.completeHistory(sessionId) }
                if (saved !is HermesResult.Ok) continue
                val messages = saved.value.data
                val fresh = messages.filter { it.id !in submitted.previousIds }
                val prompt = fresh.indexOfLast { it.role == "user" && it.content?.trim() == submitted.text.trim() }
                val reply = if (prompt < 0) null else fresh.drop(prompt + 1).lastOrNull {
                    it.role == "assistant" && it.toolCalls.isNullOrEmpty() && !it.content.isNullOrBlank()
                }
                val terminal = (status as? HermesResult.Ok)?.value?.isTerminal == true
                if (openSessionId == sessionId && sentPrompt == submitted.text && turnHistoryIds == submitted.previousIds) {
                    Snapshot.withMutableSnapshot {
                        history = UiState.Ready(messages)
                        if (prompt >= 0) pendingUserText = null
                        turn = reconcileTurn(turn, messages, submitted.previousIds, submitted.text)
                    }
                }
                if (reply != null) { discoveredReply(sessionId, reply); loadSessions(); return@launch }
                if (terminal && (status as HermesResult.Ok).value.status != "completed") return@launch
            }
        }
    }

    /**
     * Ask Hermes to stop the current turn.
     *
     * A 409 `run_not_active` is the registration race, not a failure, so it retries briefly
     * instead of surfacing an error. The UI shows "stopping…" from [stopRequested] the moment
     * the user asks, because the server will never tell us the turn was interrupted.
     */
    fun stop() {
        if (runs != null) { openSessionId?.let { runs.stop(it) }; return }
        val runId = activeRunId ?: return
        val streaming = turn as? TurnState.Streaming ?: return
        if (streaming.stopRequested) return

        stopRequested = true
        turn = streaming.copy(stopRequested = true)

        scope.launch {
            repeat(STOP_ATTEMPTS) { attempt ->
                when (val result = io { client.stopRun(runId) }) {
                    is HermesResult.Ok -> return@launch
                    is HermesResult.Err -> {
                        if (result.error !is HermesError.RunNotStoppableYet) return@launch
                        if (attempt < STOP_ATTEMPTS - 1) delay(STOP_RETRY_MS)
                    }
                }
            }
        }
    }

    private fun postStreaming(text: String, tool: String?, canStop: Boolean, toolName: String? = null, reasoning: String? = null, parts: List<LivePart> = emptyList()) {
        // Called from the streaming thread; Compose state writes are safe from any thread and
        // recomposition is scheduled on the main thread.
        val current = turn
        val alreadyStopping = (current as? TurnState.Streaming)?.stopRequested ?: stopRequested
        turn = TurnState.Streaming(
            text = text,
            runningTool = tool,
            runningToolName = toolName,
            reasoning = reasoning ?: (current as? TurnState.Streaming)?.reasoning.orEmpty(),
            parts = parts,
            canStop = canStop,
            stopRequested = alreadyStopping,
        )
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        const val STOP_ATTEMPTS = 4
        const val STOP_RETRY_MS = 250L
    }
}
