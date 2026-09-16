package org.praxisog.hermes

import org.praxisog.hermes.model.HermesError

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
