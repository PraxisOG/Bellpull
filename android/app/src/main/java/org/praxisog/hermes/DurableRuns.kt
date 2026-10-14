package org.praxisog.hermes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import java.util.UUID

internal interface RunJournal {
    fun readRuns(): List<RunSubmission>
    /** Must reach durable storage before returning true. */
    fun writeRun(value: RunSubmission): Boolean
    fun pruneRuns(beforeEpochSeconds: Double) {}
}

/** Ambiguous delivery and unsynced history have no automatic expiry. */
internal fun canPruneRun(record: RunSubmission, beforeEpochSeconds: Double): Boolean =
    record.terminal && (record.historySynced || record.trackingDismissed) && record.createdAt < beforeEpochSeconds

internal const val RUN_JOURNAL_RETENTION_SECONDS = 30 * 24 * 60 * 60

internal data class RunPresentation(
    val submission: RunSubmission,
    val turn: TurnState = TurnState.Streaming(""),
    val approval: RunApproval? = null,
    val notice: String? = null,
    val checking: Boolean = false,
) {
    val busy: Boolean get() = !submission.terminal ||
        (submission.runId != null && !submission.historySynced && !submission.trackingDismissed)
    val label: String? get() = when {
        approval != null -> "Needs approval"
        submission.status == "review" || submission.historyNeedsReview -> "Needs review"
        submission.status == "submitting" -> "Sending"
        submission.status == "reconnecting" -> "Reconnecting"
        submission.status == "failed" || submission.status == "interrupted" -> "Failed"
        busy -> if (submission.stopRequested) "Stopping" else "Running"
        else -> null
    }
}

/** Run ownership is per conversation and independent of selection, Activity, and SSE. */
internal class DurableRuns(
    private val scope: CoroutineScope,
    private val client: HermesClient,
    private val journal: RunJournal,
    private val drafts: ChatDrafts,
    private val retentionSeconds: Long,
    private val clock: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val pollMs: Long = 2_000,
    private val retryMs: Long = 5_000,
    private val maxHistoryChecks: Int = 12,
) {
    var interactiveApprovals = false
    val states = mutableStateMapOf<String, RunPresentation>()
    private val jobs = mutableMapOf<String, Job>()
    private val streams = java.util.concurrent.ConcurrentHashMap<String, HermesClient.Streaming>()
    var onHistory: (String, List<Message>) -> Unit = { _, _ -> }
    var onCompleted: (String, Message?) -> Unit = { _, _ -> }
    var onAttention: (String, String) -> Unit = { _, _ -> }
    var onChanged: (String) -> Unit = {}
    var onAccepted: (RunSubmission) -> Unit = {}
    var onPromptDelivered: (String, String) -> Unit = { _, _ -> }
    var storageError by mutableStateOf<String?>(null)
        private set
    @Volatile private var closed = false

    fun restore() {
        journal.pruneRuns(clock() - RUN_JOURNAL_RETENTION_SECONDS)
        journal.readRuns().sortedBy { it.createdAt }.forEach { record ->
            states[record.sessionId] = RunPresentation(record, terminalTurn(record),
                notice = if (record.historyNeedsReview) historyReviewNotice else null)
        }
        states.values.toList().forEach { state ->
            if ((!state.submission.terminal || !state.submission.historySynced) &&
                !state.submission.historyNeedsReview && !state.submission.trackingDismissed)
                track(state.submission.sessionId, fresh = false)
        }
    }

    fun send(sessionId: String, text: String, previousIds: Set<Int>, bellpull: BellpullRunOptions? = null): Boolean {
        if (text.isBlank() || states[sessionId]?.busy == true || closed) return false
        val record = RunSubmission("bellpull-${UUID.randomUUID()}", sessionId, text, clock(), drafts.revision(sessionId), previousIds,
            interactiveApprovals = interactiveApprovals, bellpull = bellpull)
        states[sessionId] = RunPresentation(record, TurnState.Streaming("", statusLabel = "Sending…"))
        onChanged(sessionId)
        track(sessionId, fresh = true)
        return true
    }

    fun retry(sessionId: String) {
        if (closed || jobs[sessionId]?.isActive == true) return
        track(sessionId, fresh = false, resetHistoryChecks = true)
    }

    fun observe(runId: String, sessionId: String, readThrough: Int) {
        val current = states[sessionId]
        if (current?.submission?.runId == runId) { retry(sessionId); return }
        scope.launch {
            val status = io { client.runStatus(runId) }
            if (status !is HermesResult.Ok || status.value.sessionId != sessionId) return@launch
            val active = states[sessionId]
            val created = status.value.createdAt ?: return@launch
            if (active != null && (active.busy || active.submission.createdAt >= created)) return@launch
            val record = RunSubmission("observed:$runId", sessionId, "", created, -1, setOf(readThrough),
                runId = runId, status = status.value.status, error = status.value.error,
                completionOutput = status.value.output, serverCreatedAt = created)
            save(record)
            states[sessionId] = RunPresentation(record, terminalTurn(record, status.value.output.orEmpty()))
            onChanged(sessionId)
            track(sessionId, fresh = false)
        }
    }

    /** Only an unsent, definitively rejected request can be dismissed without ambiguity. */
    fun dismissRejected(sessionId: String) {
        val state = states[sessionId] ?: return
        if (state.submission.status != "rejected") return
        states.remove(sessionId)
        onChanged(sessionId)
    }

    fun abandonAfterReview(sessionId: String) {
        val state = states[sessionId] ?: return
        if ((state.submission.status != "review" && !state.submission.historyNeedsReview) || jobs[sessionId]?.isActive == true) return
        scope.launch {
            if (state.submission.historyNeedsReview) {
                val record = state.submission.copy(trackingDismissed = true, historyNeedsReview = false)
                if (save(record)) update(sessionId) { it.copy(submission = record, turn = TurnState.Idle, notice = null, approval = null) }
                return@launch // An admitted prompt is never restored for accidental resubmission.
            }
            val record = state.submission.copy(status = "abandoned", historySynced = true)
            if (!save(record)) return@launch
            if (drafts[sessionId].text.isBlank()) drafts.set(sessionId,
                androidx.compose.ui.text.input.TextFieldValue(record.text, androidx.compose.ui.text.TextRange(record.text.length)))
            update(sessionId) { it.copy(submission = record, turn = TurnState.Idle, notice = null, approval = null) }
        }
    }

    private fun track(sessionId: String, fresh: Boolean, resetHistoryChecks: Boolean = false) {
        if (jobs[sessionId]?.isActive == true || closed) return
        jobs[sessionId] = scope.launch {
            var record = states[sessionId]?.submission ?: return@launch
            if (resetHistoryChecks && record.terminal) {
                record = record.copy(historyChecks = 0, historyNeedsReview = false)
                if (!save(record)) return@launch
                update(sessionId) { it.copy(submission = record, notice = null) }
            }
            if (fresh && !save(record)) {
                update(sessionId) { it.copy(submission = record.copy(status = "rejected"),
                    turn = TurnState.Failed(HermesError.BadRequest("Could not save this request. Nothing was sent.", null)),
                    notice = "Could not save this request. Nothing was sent.") }
                return@launch
            }
            if (record.terminal) {
                reconcileHistory(sessionId)
                return@launch
            }
            var attempt = 0
            while (isActive && record.runId == null) {
                // Wall-clock reversal also fails closed. A new key is never minted here.
                val age = clock() - record.createdAt
                if (age < 0 || age >= retentionSeconds - 60) {
                    update(sessionId) { it.copy(submission = it.submission.copy(status = "review"),
                        notice = "Delivery is unresolved and the safe retry window has expired. Review this chat on Hermes before sending again.",
                        turn = TurnState.Streaming("", statusLabel = "Delivery needs review")) }
                    return@launch
                }
                val accepted = io { client.submitRun(record.sessionId, record.text, record.requestId, record.interactiveApprovals, record.bellpull) }
                when (accepted) {
                    is HermesResult.Ok -> {
                        record = record.copy(runId = accepted.value.runId, status = accepted.value.status)
                        save(record)
                        update(sessionId) { it.copy(submission = record, notice = null,
                            turn = TurnState.Streaming("", canStop = true, statusLabel = "Running…")) }
                        drafts.accepted(sessionId, record.draftRevision)
                        onPromptDelivered(sessionId, record.text)
                        onAccepted(record)
                    }
                    is HermesResult.Err -> {
                        val error = accepted.error
                        if (error is HermesError.ServerError && error.code == "idempotency_key_conflict") {
                            val review = record.copy(status = "review", error = "The saved request conflicts with Hermes. Review this chat before sending again.")
                            save(review)
                            update(sessionId) { it.copy(submission = review, notice = review.error) }
                            return@launch
                        }
                        val rejected = error is HermesError.AuthFailed || error is HermesError.BadRequest ||
                            error is HermesError.SessionNotFound ||
                            error is HermesError.ServerError && error.status in listOf(403, 404, 409)
                        if (rejected) {
                            record = record.copy(status = "rejected", error = error.toUserMessage(), historySynced = true)
                            save(record)
                            update(sessionId) { it.copy(submission = record, turn = TurnState.Failed(error), notice = record.error) }
                            return@launch
                        }
                        update(sessionId) { it.copy(notice = "Delivery not confirmed. Checking the same request; your text is saved.",
                            submission = it.submission.copy(status = "reconnecting"),
                            turn = TurnState.Streaming("", statusLabel = "Checking delivery…")) }
                        delay((retryMs * (1L shl attempt.coerceAtMost(3))).coerceAtMost(60_000))
                        attempt++
                    }
                }
            }
            val runId = record.runId ?: return@launch
            var streamGap = false
            val liveJob = launch {
                val live = LiveTranscript()
                val prose = StringBuilder()
                val reasoning = StringBuilder()
                var commentaryBoundary = false
                var lastSequence: Int? = null
                var retrying = !fresh
                var unsafeReplay = false
                var reconnectAttempt = 0
                var consumedUnsequenced = false
                val uiContext = currentCoroutineContext()
                try { while (isActive && states[sessionId]?.submission?.terminal == false) {
                    val streamResult = io { client.runEvents(runId, lastEventId = lastSequence, onStart = {
                        streams[runId] = it
                        if (closed || !uiContext.isActive || states[sessionId]?.submission?.terminal != false)
                            streams.remove(runId)?.cancel()
                    }) { event ->
                        // Apply each frame before reading the next one. The cursor must only
                        // advance after its text has reached the UI, including before EOF.
                        runBlocking { withContext(uiContext) {
                            val current = states[sessionId] ?: return@withContext
                            if (current.submission.runId != runId || current.submission.terminal) return@withContext
                            if (event is StreamEvent.Unknown && event.event == "replay.truncated") unsafeReplay = true
                            val seq = event.seq
                            if (seq != null && lastSequence != null && seq <= lastSequence!!) return@withContext
                            if (lastSequence != null && (seq == null || seq != lastSequence!! + 1) ||
                                retrying && lastSequence == null && seq != 0) unsafeReplay = true
                            if (unsafeReplay) { streamGap = true; return@withContext }
                            if (seq == null) consumedUnsequenced = true
                            reconnectAttempt = 0
                            live.accept(event)
                            if (event is StreamEvent.AssistantDelta && event.delta.isNotEmpty()) {
                                if (commentaryBoundary && prose.isNotEmpty()) prose.append("\n\n")
                                commentaryBoundary = false
                                prose.append(event.delta)
                            }
                            if (event is StreamEvent.AssistantCommentary && !event.alreadyStreamed && event.text.isNotBlank()) {
                                if (prose.isNotEmpty()) prose.append("\n\n")
                                prose.append(event.text)
                                commentaryBoundary = true
                            }
                            if (event is StreamEvent.ToolProgress && event.toolName == "_thinking") reasoning.append(event.delta.orEmpty())
                            lastSequence = seq
                            streamGap = false
                            update(sessionId) { it.copy(turn = TurnState.Streaming(prose.toString(),
                                canStop = true, stopRequested = it.submission.stopRequested,
                                reasoning = reasoning.toString(), parts = live.parts,
                                statusLabel = if (it.approval != null) "Waiting for approval…" else "Working…")) }
                        } }
                    } }
                    streams.remove(runId)
                    if (unsafeReplay || consumedUnsequenced) break
                    if (states[sessionId]?.submission?.terminal != false) break
                    if (streamResult is HermesResult.Err && !recoverableStreamError(streamResult.error)) break
                    streamGap = true
                    // No event has been consumed after an initial connection failure.
                    // Retry that subscription without inventing a run or an SSE cursor.
                    retrying = !fresh || lastSequence != null
                    delay((retryMs * (1L shl reconnectAttempt.coerceAtMost(3))).coerceAtMost(60_000))
                    reconnectAttempt++
                } } finally { streams.remove(runId); streamGap = true }
            }
            while (isActive) {
                when (val result = io { client.runStatus(runId) }) {
                    is HermesResult.Ok -> {
                        val status = result.value
                        val current = states[sessionId]?.submission ?: return@launch
                        record = current.copy(status = status.status, error = status.error,
                            completionOutput = status.output ?: current.completionOutput,
                            serverCreatedAt = status.createdAt ?: current.serverCreatedAt)
                        if (current != record) save(record)
                        val oldApproval = states[sessionId]?.approval?.requestId
                        update(sessionId) { it.copy(submission = record,
                            approval = status.approval?.takeIf { approval -> approval.requestId.isNotBlank() },
                            notice = if (streamGap && !status.isTerminal && status.approval == null)
                                "Hermes is working. Live text is unavailable; the saved reply will appear when ready." else null,
                            turn = if (status.isTerminal) terminalTurn(record, status.output.orEmpty())
                                else (it.turn as? TurnState.Streaming ?: TurnState.Streaming("")).copy(
                                    canStop = true, stopRequested = record.stopRequested,
                                    statusLabel = if (status.approval != null) "Waiting for approval…" else "Working…")) }
                        status.approval?.let { approval ->
                            if (approval.requestId.isNotBlank() && oldApproval != approval.requestId)
                                onAttention(sessionId, "approval:${approval.requestId}")
                        }
                        if (status.isTerminal) {
                            liveJob.cancel()
                            streams.remove(runId)?.cancel()
                            if (status.status in setOf("failed", "interrupted")) onAttention(sessionId, "run:$runId")
                            reconcileHistory(sessionId)
                            return@launch
                        }
                    }
                    is HermesResult.Err -> {
                        val missing = (result.error as? HermesError.ServerError)?.code == "run_not_found"
                        update(sessionId) { it.copy(notice = if (missing)
                            "Hermes no longer has this run's status. Review its saved history; this request will not be resubmitted."
                            else "Connection lost. Hermes may still be working. Reconnecting…") }
                        if (missing) {
                            liveJob.cancel()
                            streams.remove(runId)?.cancel()
                            val review = states[sessionId]!!.submission.copy(status = "review")
                            save(review)
                            update(sessionId) { it.copy(submission = review) }
                            syncHistory(sessionId, settle = false)
                            return@launch
                        }
                        delay(retryMs)
                    }
                }
                delay(pollMs)
            }
        }
    }

    private val historyReviewNotice = "Hermes finished, but its saved reply could not be matched. Check status to retry, or review this chat and stop tracking. This run will not be submitted again."

    private fun recoverableStreamError(error: HermesError): Boolean = when (error) {
        is HermesError.Unreachable -> error.kind !in setOf(HermesError.Unreachable.Kind.TLS, HermesError.Unreachable.Kind.INTERRUPTED)
        is HermesError.RateLimited, is HermesError.StreamTruncated -> true
        is HermesError.ServerError -> error.status >= 500
        is HermesError.UnparseableError -> error.status >= 500
        else -> false
    }

    private suspend fun reconcileHistory(sessionId: String) {
        var record = states[sessionId]?.submission ?: return
        // Older journals may contain only terminal status. Recover the server's output,
        // never POST the admitted request again, even if that status has since expired.
        if (record.status == "completed" && record.completionOutput == null && record.runId != null) {
            val status = io { client.runStatus(record.runId!!) }
            if (status is HermesResult.Ok && status.value.isTerminal) {
                record = record.copy(completionOutput = status.value.output,
                    serverCreatedAt = status.value.createdAt ?: record.serverCreatedAt)
                save(record)
                update(sessionId) { it.copy(submission = record, turn = terminalTurn(record)) }
            }
        }
        while (currentCoroutineContext().isActive && !syncHistory(sessionId)) {
            val current = states[sessionId]?.submission ?: return
            val next = current.copy(historyChecks = current.historyChecks + 1,
                historyNeedsReview = current.historyChecks + 1 >= maxHistoryChecks)
            save(next)
            update(sessionId) { it.copy(submission = next,
                notice = if (next.historyNeedsReview) historyReviewNotice else it.notice) }
            if (next.historyNeedsReview) return
            delay(retryMs)
        }
    }

    private suspend fun syncHistory(sessionId: String, settle: Boolean = true): Boolean {
        val state = states[sessionId] ?: return true
        val record = state.submission
        val result = io { client.completeHistory(sessionId) }
        if (result !is HermesResult.Ok) {
            update(sessionId) { it.copy(notice = "Work has settled; reconnecting to load the saved reply.") }
            return false
        }
        val messages = result.value.data
        val reply = matchedCompletion(record, messages)
        if (reply != null) drafts.accepted(sessionId, record.draftRevision)
        onHistory(sessionId, messages)
        // Completion status can become visible before the transcript transaction.
        // A successful GET with old rows is not proof the new reply was saved.
        if (settle && record.status == "completed" && reply == null) {
            update(sessionId) { it.copy(notice = "Hermes finished. Waiting for its saved reply…") }
            return false
        }
        if (settle && !record.historySynced) {
            val synced = record.copy(historySynced = true, historyNeedsReview = false)
            if (!save(synced)) return false
            onCompleted(sessionId, reply)
            journal.pruneRuns(clock() - RUN_JOURNAL_RETENTION_SECONDS)
            update(sessionId) { it.copy(submission = synced, notice = null,
                turn = if (record.status == "completed" && reply != null)
                    if (record.stopRequested) TurnState.StoppedByUser("") else TurnState.Idle
                    else reconcileDurableTurn(it.turn, messages, record)) }
        }
        return true
    }

    fun stop(sessionId: String) {
        val state = states[sessionId] ?: return
        val runId = state.submission.runId ?: return
        if (!state.busy || state.checking) return
        update(sessionId) { it.copy(checking = true) }
        scope.launch {
            when (val result = io { client.stopRun(runId) }) {
                is HermesResult.Ok -> {
                    val current = states[sessionId] ?: return@launch
                    val next = current.submission.copy(stopRequested = true)
                    save(next)
                    update(sessionId) { it.copy(submission = next, checking = false,
                        turn = (it.turn as? TurnState.Streaming ?: TurnState.Streaming("")).copy(stopRequested = true), notice = null) }
                }
                is HermesResult.Err -> update(sessionId) { it.copy(checking = false,
                    notice = "Stop was not confirmed. ${result.error.toUserMessage()}") }
            }
        }
    }

    fun respond(sessionId: String, requestId: String, choice: String) {
        val state = states[sessionId] ?: return
        val runId = state.submission.runId ?: return
        if (state.checking || state.approval?.requestId != requestId || choice !in state.approval.choices || choice !in setOf("once", "deny")) return
        update(sessionId) { it.copy(checking = true) }
        scope.launch {
            // Revalidate after reconnect and bind to the exact request; never resolve_all.
            val latest = io { client.runStatus(runId) }
            if (latest !is HermesResult.Ok || latest.value.approval?.requestId != requestId) {
                update(sessionId) { it.copy(checking = false, approval = null, notice = "This approval is no longer pending. Refreshing…") }
                return@launch
            }
            val result = io { client.respondToApproval(runId, requestId, choice) }
            update(sessionId) { it.copy(checking = false, approval = null,
                notice = if (result is HermesResult.Err) "Decision not confirmed. Checking the request's current state…" else null) }
        }
    }

    fun close() {
        closed = true
        streams.values.forEach { it.cancel() }
        streams.clear()
        jobs.values.forEach { it.cancel() }
    }

    private suspend fun save(record: RunSubmission): Boolean {
        val saved = io { runCatching { journal.writeRun(record) }.getOrDefault(false) }
        storageError = if (saved) null else "Could not save run recovery data to this device."
        return saved
    }
    private fun update(id: String, transform: (RunPresentation) -> RunPresentation) {
        if (closed) return
        states[id]?.let { states[id] = transform(it); onChanged(id) }
    }
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
    private fun terminalTurn(record: RunSubmission, output: String = record.completionOutput.orEmpty()): TurnState = when {
        record.trackingDismissed -> TurnState.Idle
        record.stopRequested || record.status in setOf("cancelled", "stopped") -> TurnState.StoppedByUser(output)
        record.status in setOf("failed", "interrupted", "rejected") -> TurnState.Failed(HermesError.BadRequest(record.error ?: "Run ${record.status}.", null))
        record.terminal -> if (record.historySynced) TurnState.Idle else TurnState.Finished(output)
        else -> TurnState.Streaming("", canStop = record.runId != null, statusLabel = "Recovering run…")
    }
}
