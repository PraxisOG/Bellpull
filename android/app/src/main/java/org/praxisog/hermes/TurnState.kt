package org.praxisog.hermes

import org.praxisog.hermes.model.HermesError

/** Match transport whitespace normalization without matching older repeated prompts. */
internal fun samePrompt(saved: String?, sent: String?): Boolean = saved != null && sent != null &&
    saved.trim().replace(Regex("\\s+"), " ") == sent.trim().replace(Regex("\\s+"), " ")

/**
 * State of the turn currently being streamed.
 *
 * [StoppedByUser] exists because of Gate 0d: a turn stopped through
 * `POST /v1/runs/{id}/stop` comes back reporting `completed: true, partial: false,
 * interrupted: false`, so the server cannot tell us it was stopped. The client has to
 * remember that it asked. Likewise [Truncated] is set from a dropped stream the client
 * observed, not from a server flag.
 */
internal sealed interface TurnState {
    /** Nothing in flight. */
    data object Idle : TurnState

    /**
     * A turn is streaming. [canStop] is false until the first delta arrives, because stop
     * called immediately after `run.started` returns 409 `run_not_active` — the agent is not
     * registered yet (Gate 0d).
     */
    data class Streaming(
        val text: String,
        val runningTool: String? = null,
        val canStop: Boolean = false,
        val stopRequested: Boolean = false,
        val runningToolName: String? = null,
        val reasoning: String = "",
        val parts: List<LivePart> = emptyList(),
        val statusLabel: String = "Writing…",
    ) : TurnState

    /** The user stopped it. Partial text is kept and labelled as partial. */
    data class StoppedByUser(val text: String) : TurnState

    /** The stream dropped. The turn may still be running on Hermes. */
    data class Truncated(val text: String, val error: HermesError) : TurnState

    /** The turn finished normally. */
    data class Finished(val text: String) : TurnState

    /** The request failed before or during the turn, with nothing usable. */
    data class Failed(val error: HermesError) : TurnState
}

/** Only retire stream text after this turn is present in authoritative history. */
internal fun reconcileTurn(
    turn: TurnState,
    messages: List<org.praxisog.hermes.model.Message>,
    previousIds: Set<Int>,
    prompt: String?,
): TurnState {
    if (prompt == null || turn is TurnState.Streaming || turn is TurnState.Idle) return turn
    val fresh = messages.filter { it.id !in previousIds }
    val start = fresh.indexOfLast { it.role == "user" && samePrompt(it.content, prompt) }
    if (start < 0) return turn
    val replies = fresh.drop(start + 1).filter { it.role == "assistant" && !it.content.isNullOrBlank() }
    if (replies.isEmpty()) return turn
    if (turn is TurnState.Finished) return TurnState.Idle

    // After a dropped stream the server may still be writing. Keep any text not yet in
    // history, and always retain the explicit stopped/truncated outcome banner.
    fun normalized(text: String) = text.trim().replace(Regex("\\s+"), " ")
    val saved = normalized(replies.joinToString("\n") { it.content.orEmpty() })
    return when (turn) {
        is TurnState.StoppedByUser -> if (saved.contains(normalized(turn.text))) turn.copy(text = "") else turn
        is TurnState.Truncated -> if (saved.contains(normalized(turn.text))) turn.copy(text = "") else turn
        else -> turn
    }
}

/** A notification-observed run has no phone-authored prompt to match. */
internal fun reconcileDurableTurn(turn: TurnState, messages: List<org.praxisog.hermes.model.Message>,
    record: org.praxisog.hermes.model.RunSubmission): TurnState {
    // A compacted prompt may no longer exist on reopen. Persisted sync evidence,
    // or the exact final row fenced by server admission time, still retires the copy.
    if (turn is TurnState.Finished && (record.historySynced || matchedCompletion(record, messages) != null)) return TurnState.Idle
    if (!record.requestId.startsWith("observed:")) return reconcileTurn(turn, messages, record.previousIds, record.text)
    val output = when (turn) {
        is TurnState.Finished -> turn.text
        is TurnState.StoppedByUser -> turn.text
        else -> return turn
    }
    fun normalized(text: String) = text.trim().replace(Regex("\\s+"), " ")
    val present = output.isBlank() || messages.any { it.role == "assistant" &&
        normalized(it.content.orEmpty()) == normalized(output) }
    if (!present) return turn
    return if (turn is TurnState.StoppedByUser) turn.copy(text = "") else TurnState.Idle
}
