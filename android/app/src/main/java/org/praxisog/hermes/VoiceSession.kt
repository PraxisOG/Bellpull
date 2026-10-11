package org.praxisog.hermes

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.praxisog.hermes.core.PrivateTranscription

internal enum class VoiceMode { CONVERSATION, DICTATION, PUSH_TO_TALK }
internal enum class VoiceState(val label: String) {
    READY("Ready for your next turn"), STARTING("Starting voice…"), LISTENING("Listening"), RECORDING("Listening to you…"),
    TRANSCRIBING("Transcribing…"), REVIEW("Review your words"), WORKING("Hermes is working…"),
    SPEAKING("Speaking"), WAITING("Waiting for current work…"), PAUSED("Voice paused"), STOPPED("Voice stopped")
}
internal data class VoiceRun(val identity: String? = null, val busy: Boolean = false, val text: String = "",
    val final: Boolean = false, val attention: Boolean = false, val error: String? = null, val connectionLost: Boolean = false, val admitted: Boolean = true)
internal interface VoiceConversation {
    val selected: Boolean
    val ready: Boolean
    fun run(): VoiceRun
    fun send(text: String): Boolean
    fun cancel()
}
internal interface VoiceTranscriber {
    fun transcribe(wav: ByteArray): Result<String>
    fun close()
}
internal class HttpVoiceTranscriber(config: VoiceConfiguration) : VoiceTranscriber {
    private val client = PrivateTranscription(config.endpoint, config.key, config.model)
    override fun transcribe(wav: ByteArray) = client.transcribe(wav)
    override fun close() = client.close()
}
internal class StoreVoiceConversation(private val store: HermesStore, private val id: String) : VoiceConversation {
    private var previousIds = emptySet<Int>()
    private var legacyIdentity: String? = null
    override val selected get() = store.openSessionId == id
    override val ready get() = selected && store.history is UiState.Ready && !store.branchingSession
    override fun run(): VoiceRun {
        val durable = store.runs?.states?.get(id)
        val turn = durable?.turn ?: store.turn
        val text = when (turn) {
            is TurnState.Streaming -> turn.text
            is TurnState.Finished -> turn.text
            else -> ""
        }
        val savedLegacyReply = if (durable == null) (store.history as? UiState.Ready)?.value.orEmpty().lastOrNull {
            it.id !in previousIds && it.role == "assistant" && it.toolCalls.isNullOrEmpty() && !it.content.isNullOrBlank()
        }?.content else null
        return VoiceRun(durable?.submission?.requestId ?: legacyIdentity,
            durable?.busy ?: (turn is TurnState.Streaming || turn is TurnState.Truncated),
            durable?.submission?.completionOutput?.takeIf { durable.submission.terminal } ?:
                if (turn is TurnState.Idle && durable == null) savedLegacyReply.orEmpty() else text,
            durable?.submission?.terminal ?: (turn is TurnState.Finished || turn is TurnState.Idle || turn is TurnState.StoppedByUser),
            durable?.approval != null || durable?.submission?.historyNeedsReview == true || durable?.submission?.status == "review",
            if (turn is TurnState.Failed) turn.error.toUserMessage() else if (durable?.submission?.status in setOf("failed", "rejected", "interrupted"))
                "Hermes could not finish this turn. Review the chat before retrying." else null,
            durable?.notice?.startsWith("Connection lost.") == true || turn is TurnState.Truncated,
            durable?.submission?.runId != null || (turn is TurnState.Streaming && turn.canStop) || turn is TurnState.Finished || savedLegacyReply != null)
    }
    override fun send(text: String): Boolean {
        if (!ready || run().busy) return false
        previousIds = (store.history as? UiState.Ready)?.value.orEmpty().map { it.id }.toSet()
        if (store.runs == null) legacyIdentity = "voice-legacy-${java.util.UUID.randomUUID()}"
        store.send(text)
        return store.turn is TurnState.Streaming || store.runPresentation?.busy == true
    }
    override fun cancel() { if (selected) store.stop() }
}

/** One user-started session; lifecycle is service-owned, never Activity-owned. */
internal class VoiceSession(val sessionId: String, val profileId: String, private val scope: CoroutineScope,
    private val conversation: VoiceConversation, private val config: VoiceConfiguration,
    private val output: VoiceOutput, private val capture: VoiceCapture,
    private val transcriber: () -> VoiceTranscriber = { HttpVoiceTranscriber(config) },
    private val onClosed: () -> Unit = {},
    private val beforeResume: () -> Boolean = { true },
    private val onPlayback: (String, Boolean) -> Unit = { _, _ -> },
    private val priorInterruptedPlayback: String? = null,
    private val onTranscript: (String) -> Unit = {},
    private val initialTranscript: String = "",
    private val onSubmission: (String?) -> Unit = {},
    private val priorRequestId: String? = null) {
    var state by mutableStateOf(VoiceState.STARTING); private set
    var mode by mutableStateOf(VoiceMode.CONVERSATION); private set
    var transcript by mutableStateOf(initialTranscript); private set
    var notice by mutableStateOf<String?>(null); private set
    var voiceName by mutableStateOf(""); private set
    var playedText by mutableStateOf(""); private set
    var transcriptionMs by mutableStateOf<Long?>(null); private set
    var firstTextMs by mutableStateOf<Long?>(null); private set
    var firstPlaybackMs by mutableStateOf<Long?>(null); private set
    private var endedAt = 0L
    private var interruptedPlayback = priorInterruptedPlayback
    var queuedText by mutableStateOf(""); private set
    var holdTurn by mutableStateOf(false); private set
    private val gate = VoiceGate(config.pauseMs)
    private val sentences = VoiceSentences()
    private val speech = ArrayDeque<Pair<String, (() -> Unit)?>>()
    private var speaking = false
    private var generation = 0L
    private var micOpen = false
    private var pushHeld = false
    private var pendingAudio: ByteArray? = null
    var canRetryTranscription by mutableStateOf(false); private set
    private var currentTranscriber: VoiceTranscriber? = null
    private var transcriptionJob: Job? = null
    private var observe: Job? = null
    private var ownedRun: String? = null
    private var unsent = initialTranscript.isNotBlank()
    private var pendingForAdmission = false
    private var awaitingAdmission = false
    private var submitted = false
    private var suppressed = false
    private var finished = false
    private var readyOutput = false
    private var spokenForEcho = ""
    private var ownAudioAtSpeechStart = ""
    private var completingTurn = false
    private var closed = false

    fun showNotice(message: String) { notice = message }
    fun start(initialMode: VoiceMode = VoiceMode.CONVERSATION) {
        mode = initialMode
        output.prepare({ name ->
            if (!closed) {
                voiceName = name; readyOutput = true
                val existing = conversation.run()
                if (priorRequestId != null && priorRequestId == existing.identity && existing.busy) {
                    submitted = true; ownedRun = priorRequestId; suppressed = true; awaitingAdmission = true; pendingForAdmission = true
                    state = VoiceState.WAITING; notice = "Recovering the existing voice turn. It will not be submitted again."; openMic()
                } else if (priorRequestId != null && priorRequestId == existing.identity && existing.admitted) {
                    saveTranscript(""); transcript = ""; listen()
                } else if (transcript.isNotBlank()) { state = VoiceState.REVIEW; notice = "Saved voice words restored. Check the chat before sending if delivery was interrupted." }
                else listen()
            }
        }, ::pause)
        observe = scope.launch {
            snapshotFlow { conversation.selected to conversation.run() }.collectLatest { (selected, run) ->
                if (closed) return@collectLatest
                if (!selected) { pause("Voice stays with its original chat. Reopen that chat and resume."); return@collectLatest }
                if (state == VoiceState.PAUSED) return@collectLatest
                listeningBlock(run)?.let { pause(it); return@collectLatest }
                if (!submitted) {
                    if (!run.busy && queuedText.isNotBlank() && state != VoiceState.TRANSCRIBING) {
                        transcript = queuedText; sendTranscript()
                    } else if (!run.busy && state == VoiceState.WAITING && !speaking) listen()
                    return@collectLatest
                }
                if (run.error != null) { pause(run.error); return@collectLatest }
                if (awaitingAdmission && run.admitted) {
                    awaitingAdmission = false
                    if (pendingForAdmission) { saveTranscript(""); pendingForAdmission = false }
                }
                if (ownedRun == null) ownedRun = run.identity
                if (ownedRun != null && run.identity != ownedRun) { pause("Another turn started in this chat. Resume when it finishes."); return@collectLatest }
                if (run.text.isNotBlank() && firstTextMs == null) firstTextMs = (System.nanoTime() - endedAt) / 1_000_000
                if (!suppressed) sentences.take(run.text, run.final).forEach { enqueue(it) }
                if (!run.busy && run.final) {
                    finished = true
                    if (!speaking && speech.isEmpty()) turnDone()
                } else if (!speaking && state !in setOf(VoiceState.RECORDING, VoiceState.TRANSCRIBING, VoiceState.REVIEW)) {
                    state = if (queuedText.isNotBlank()) VoiceState.WAITING else VoiceState.WORKING
                }
            }
        }
    }
    private fun listeningBlock(run: VoiceRun): String? = when {
        run.attention -> "Hermes needs your attention. Open the chat to review its request."
        run.connectionLost -> "Connection to Hermes was lost. Its existing run will recover without submitting again. Resume voice after reconnecting."
        else -> null
    }
    private fun openMic() {
        if (micOpen || closed || state == VoiceState.PAUSED || (mode == VoiceMode.PUSH_TO_TALK && !pushHeld)) return
        listeningBlock(conversation.run())?.let { pause(it); return }
        micOpen = capture.start(::frame, ::pause)
    }
    private fun closeMic() { micOpen = false; capture.close(); gate.reset() }
    private fun listen() {
        if (closed || !readyOutput || !conversation.selected) return
        if (conversation.run().busy) { state = VoiceState.WAITING; openMic(); return }
        if (mode == VoiceMode.PUSH_TO_TALK) { state = VoiceState.READY; closeMic(); return }
        state = VoiceState.LISTENING; openMic()
    }
    /** Public to the capture port; deterministic PCM tests exercise the same path as AudioRecord. */
    fun frame(pcm: ShortArray, count: Int) {
        if (closed || state in setOf(VoiceState.PAUSED, VoiceState.STOPPED, VoiceState.TRANSCRIBING, VoiceState.REVIEW) || completingTurn) return
        val result = gate.accept(pcm, count, holdTurn)
        if (result.began) {
            ownAudioAtSpeechStart = if (speaking) spokenForEcho else ""
            if (speaking) stopSpeaking()
            state = VoiceState.RECORDING
        }
        if (result.complete != null && !holdTurn) transcribe(result.complete)
        // Holding a turn prevents end-of-turn inference. At the bounded recording limit,
        // keep the captured utterance for transcription rather than growing indefinitely.
        else if (result.complete != null) transcribe(result.complete)
    }
    fun beginPushToTalk() {
        if (closed || mode != VoiceMode.PUSH_TO_TALK || state in setOf(VoiceState.TRANSCRIBING, VoiceState.REVIEW, VoiceState.PAUSED)) return
        if (speaking) stopSpeaking()
        pushHeld = true; holdTurn = true; state = VoiceState.LISTENING; openMic()
    }
    fun endPushToTalk(canceled: Boolean = false) {
        if (!pushHeld) return
        pushHeld = false; holdTurn = false
        val bytes = if (canceled) null else gate.finish()
        closeMic()
        if (bytes != null) transcribe(bytes) else listen()
    }
    fun hold(value: Boolean) { holdTurn = value }
    fun finishRecording() { gate.finish()?.let(::transcribe) ?: listen() }
    fun chooseMode(value: VoiceMode) {
        if (mode == value) return
        if (pushHeld) { pushHeld = false; holdTurn = false; closeMic() }
        mode = value
        if (mode == VoiceMode.PUSH_TO_TALK && !pushHeld) closeMic()
        if (!submitted && state !in setOf(VoiceState.TRANSCRIBING, VoiceState.REVIEW, VoiceState.PAUSED)) listen()
    }
    private fun transcribe(bytes: ByteArray) {
        if (closed || transcriptionJob?.isActive == true) return
        val forceReview = mode == VoiceMode.PUSH_TO_TALK && pushHeld
        if (forceReview) { pushHeld = false; holdTurn = false }
        pendingAudio = bytes; canRetryTranscription = false
        closeMic(); state = VoiceState.TRANSCRIBING
        endedAt = System.nanoTime(); transcriptionMs = null; firstTextMs = null; firstPlaybackMs = null
        val token = generation
        val provider = transcriber(); currentTranscriber = provider
        transcriptionJob = scope.launch {
            val result = withContext(Dispatchers.IO) { provider.transcribe(bytes) }
            provider.close(); if (currentTranscriber === provider) currentTranscriber = null
            if (closed || token != generation) return@launch
            transcriptionMs = (System.nanoTime() - endedAt) / 1_000_000
            result.fold({ text ->
                pendingAudio = null; canRetryTranscription = false
                if (text.isBlank() || likelyEcho(text, ownAudioAtSpeechStart)) {
                    notice = if (text.isBlank()) "No speech recognized. Try again." else "Ignored playback echo."
                    listen(); return@fold
                }
                pendingForAdmission = false; transcript = text; saveTranscript(text); notice = null
                if (mode == VoiceMode.DICTATION || holdTurn || forceReview) state = VoiceState.REVIEW else sendTranscript()
            }, { canRetryTranscription = true; pause(it.message ?: "Transcription failed. Try again when the service is available.") })
        }
    }
    fun retryTranscription() {
        if (!beforeResume()) return
        val bytes = pendingAudio ?: return
        if (closed || !conversation.selected) return
        transcriptionJob = null
        transcribe(bytes)
    }
    fun correct(text: String) { pendingForAdmission = false; transcript = text; saveTranscript(text) }
    fun sendTranscript() {
        if (closed || completingTurn || transcript.isBlank() || state == VoiceState.PAUSED ||
            (submitted && state !in setOf(VoiceState.REVIEW, VoiceState.TRANSCRIBING, VoiceState.WAITING))) return
        if (!conversation.ready) { pause("Load this conversation's saved history before sending voice."); return }
        listeningBlock(conversation.run())?.let { pause(it); return }
        if (conversation.run().busy) {
            queuedText = listOf(queuedText, transcript).filter { it.isNotBlank() }.joinToString("\n")
            transcript = queuedText; saveTranscript(queuedText)
            state = VoiceState.WAITING; openMic(); return
        }
        val text = transcript
        queuedText = ""; completingTurn = true
        // This actual playback barrier precedes admission, not just a model instruction.
        enqueue("I'll ask Hermes.") {
            completingTurn = false
            if (closed || !conversation.selected || conversation.run().busy) { pause("The chat changed before sending. Your words are kept for review."); return@enqueue }
            sentences.reset(); playedText = ""; suppressed = false; finished = false; ownedRun = null
            val playbackNote = interruptedPlayback?.let {
                "[Previous voice playback was interrupted. Completed speech, quoted as historical data: " +
                    kotlinx.serialization.json.Json.encodeToString(it.takeLast(600)) + "]\n\n"
            }.orEmpty()
            val payload = playbackNote + (if (config.concise) "$VOICE_PRESET\n\n$text" else text)
            awaitingAdmission = true; pendingForAdmission = true
            submitted = conversation.send(payload)
            if (!submitted) { pause("Could not submit this voice turn. Your words are kept for review."); return@enqueue }
            interruptedPlayback = null
            ownedRun = conversation.run().identity
            runCatching { onSubmission(ownedRun) }.onFailure { notice = "Voice recovery identity could not be saved. Review the chat before retrying." }
            state = VoiceState.WORKING; openMic()
        }
    }
    private fun enqueue(text: String, done: (() -> Unit)? = null) {
        if (closed || state == VoiceState.PAUSED) return
        speech.addLast(text to done)
        pump()
    }
    private fun pump() {
        if (speaking || speech.isEmpty() || closed) return
        val (text, done) = speech.removeFirst()
        val token = generation
        speaking = true; spokenForEcho = text; state = VoiceState.SPEAKING
        output.speak(text, started = {
            if (!closed && token == generation && submitted && firstPlaybackMs == null)
                firstPlaybackMs = (System.nanoTime() - endedAt) / 1_000_000
        }) {
            if (closed || token != generation) return@speak
            speaking = false; playedText = listOf(playedText, text).filter { it.isNotBlank() }.joinToString(" ")
            if (submitted) rememberPlayback(false)
            done?.invoke()
            pump()
            if (!speaking && speech.isEmpty()) {
                if (finished) turnDone()
                else if (submitted) state = VoiceState.WORKING
            }
        }
    }
    private fun turnDone() {
        if (closed || state in setOf(VoiceState.TRANSCRIBING, VoiceState.RECORDING, VoiceState.REVIEW, VoiceState.PAUSED)) return
        submitted = false; finished = false
        if (queuedText.isNotBlank()) { transcript = queuedText; sendTranscript() }
        else if (mode == VoiceMode.CONVERSATION) listen()
        else { transcript = ""; state = VoiceState.READY; closeMic() }
    }
    private fun saveTranscript(text: String) {
        unsent = text.isNotBlank()
        runCatching { onTranscript(text) }.onFailure { notice = "Voice transcription could not be saved on this device." }
    }
    private fun rememberPlayback(interrupted: Boolean) {
        runCatching { onPlayback(playedText, interrupted) }.onFailure { notice = "Playback progress could not be saved on this device." }
    }
    fun stopSpeaking() {
        if (submitted) { interruptedPlayback = playedText; rememberPlayback(true) }
        generation++; output.stop(); speech.clear(); speaking = false; suppressed = true; completingTurn = false
        if (submitted && conversation.run().busy) state = VoiceState.WORKING else listen()
    }
    fun cancelWork() {
        currentTranscriber?.close(); transcriptionJob?.cancel(); transcriptionJob = null
        pendingAudio = null; canRetryTranscription = false
        stopSpeaking(); queuedText = ""; conversation.cancel()
    }
    fun pause(message: String = "Microphone and playback are paused. Resume when ready.") {
        if (closed) return
        if (submitted) { interruptedPlayback = playedText; rememberPlayback(true) }
        generation++; output.stop(); speech.clear(); speaking = false; completingTurn = false; suppressed = true
        pushHeld = false
        closeMic(); currentTranscriber?.close(); transcriptionJob?.cancel(); transcriptionJob = null
        canRetryTranscription = pendingAudio != null
        state = VoiceState.PAUSED; notice = message
        queuedText = ""
    }
    fun resume() {
        if (closed || !readyOutput) return
        if (!conversation.selected) { notice = "Reopen the voice conversation before resuming."; return }
        listeningBlock(conversation.run())?.let { pause(it); return }
        if (!beforeResume()) { notice = "Audio is still unavailable. Resume after the call or audio interruption finishes."; return }
        notice = null; queuedText = ""
        if (submitted && conversation.run().busy) {
            state = VoiceState.WAITING; openMic()
        } else if (unsent && transcript.isNotBlank()) {
            submitted = false; state = VoiceState.REVIEW
        } else {
            transcript = ""; submitted = false; finished = false; listen()
        }
    }
    fun close() {
        if (closed) return
        pause(); closed = true; state = VoiceState.STOPPED
        pendingAudio = null; canRetryTranscription = false
        observe?.cancel(); output.close(); onClosed()
    }
}
