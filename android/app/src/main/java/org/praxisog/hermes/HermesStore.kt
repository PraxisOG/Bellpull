package org.praxisog.hermes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private val sessionSource: String? = "api_server",
) {
    var sessions by mutableStateOf<UiState<List<Session>>>(UiState.Loading)
        private set
    var history by mutableStateOf<UiState<List<Message>>>(UiState.Loading)
        private set
    var turn by mutableStateOf<TurnState>(TurnState.Idle)
        private set
    var openSessionId by mutableStateOf<String?>(null)
        private set

    /** Non-null while a turn is in flight, so a stop has something to target. */
    private var activeRunId: String? = null
    private var stopRequested = false

    val openSession: Session?
        get() = (sessions as? UiState.Ready)?.value?.firstOrNull { it.id == openSessionId }

    // ---- sessions -------------------------------------------------------------

    fun loadSessions() {
        scope.launch {
            sessions = UiState.Loading
            sessions = when (val result = io { client.listSessions(source = sessionSource) }) {
                is HermesResult.Ok -> UiState.Ready(result.value.data)
                is HermesResult.Err -> UiState.Failed(result.error)
            }
        }
    }

    fun openSession(sessionId: String) {
        openSessionId = sessionId
        turn = TurnState.Idle
        activeRunId = null
        stopRequested = false
        refreshHistory()
    }

    fun closeSession() {
        openSessionId = null
        history = UiState.Loading
        turn = TurnState.Idle
    }

    fun refreshHistory() {
        val sessionId = openSessionId ?: return
        scope.launch {
            history = when (val result = io { client.messages(sessionId) }) {
                is HermesResult.Ok -> UiState.Ready(result.value.data)
                is HermesResult.Err -> UiState.Failed(result.error)
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
    fun send(text: String) {
        val sessionId = openSessionId ?: return
        if (text.isBlank() || turn is TurnState.Streaming) return

        stopRequested = false
        activeRunId = null
        turn = TurnState.Streaming(text = "", canStop = false)

        scope.launch {
            val accumulated = StringBuilder()
            val result = io {
                client.streamTurn(sessionId, text) { event ->
                    when (event) {
                        is StreamEvent.RunStarted -> activeRunId = event.runId
                        is StreamEvent.AssistantDelta -> {
                            accumulated.append(event.delta)
                            // canStop only once a delta has landed: stopping before the agent
                            // is registered returns 409 run_not_active (Gate 0d).
                            postStreaming(accumulated.toString(), tool = null, canStop = true)
                        }
                        is StreamEvent.ToolStarted -> postStreaming(
                            accumulated.toString(),
                            tool = event.preview ?: event.toolName,
                            canStop = true,
                        )
                        is StreamEvent.ToolCompleted -> postStreaming(
                            accumulated.toString(), tool = null, canStop = true,
                        )
                        else -> Unit
                    }
                }
            }

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
            activeRunId = null
            refreshHistory()
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

    private fun postStreaming(text: String, tool: String?, canStop: Boolean) {
        // Called from the streaming thread; Compose state writes are safe from any thread and
        // recomposition is scheduled on the main thread.
        val current = turn
        val alreadyStopping = (current as? TurnState.Streaming)?.stopRequested ?: stopRequested
        turn = TurnState.Streaming(
            text = text,
            runningTool = tool,
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
