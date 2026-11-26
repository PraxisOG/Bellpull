package org.praxisog.hermes

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.praxisog.hermes.core.PrivateTranscription

internal enum class VoiceMode { CALL, CONVERSATION, DICTATION, PUSH_TO_TALK }
internal const val APPROVAL_WAITING = "Approval waiting in chat. Say “read the approval request” to hear the request. Other words wait until this run finishes."
internal enum class VoiceState(val label: String) {
    READY("Ready for your next turn"), STARTING("Starting voice…"), LISTENING("Listening"), RECORDING("Listening to you…"),
    TRANSCRIBING("Transcribing…"), REVIEW("Review your words"), WORKING("Hermes is working…"),
    SPEAKING("Speaking"), WAITING("Waiting for current work…"), PAUSED("Voice paused"), STOPPED("Voice stopped")
}
internal data class VoiceRun(val identity: String? = null, val busy: Boolean = false, val text: String = "",
    val final: Boolean = false, val attention: Boolean = false, val error: String? = null, val connectionLost: Boolean = false, val admitted: Boolean = true,
    val activities: List<VoiceActivity> = emptyList(), val stopRequested: Boolean = false, val stopError: String? = null,
    val executing: Boolean = busy, val approval: PendingRunApproval? = null, val approvalNotice: String? = null) {
    val approvalPending get() = approval != null
}
internal interface VoiceConversation {
    val selected: Boolean
    val ready: Boolean
    fun run(): VoiceRun
    fun send(text: String): Boolean
    fun decideApproval(target: PendingRunApproval, choice: String): Boolean = false
    fun cancel()
}
internal interface VoiceTranscriber {
    val routeNotice: String? get() = null
    fun transcribe(wav: ByteArray): Result<String>
    fun close()
}
internal class HttpVoiceTranscriber(config: VoiceConfiguration) : VoiceTranscriber {
    private val client = PrivateTranscription(config.endpoint, config.key, config.model)
    override fun transcribe(wav: ByteArray) = client.transcribe(wav)
    override fun close() = client.close()
}
internal class StoreVoiceConversation(private val store: HermesStore, private val id: String,
    private val canvas: CanvasWorkspace? = null) : VoiceConversation {
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
            durable?.submission?.historyNeedsReview == true || durable?.submission?.status == "review",
            if (turn is TurnState.Failed) turn.error.toUserMessage() else if (durable?.submission?.status in setOf("failed", "rejected", "interrupted"))
                "Hermes could not finish this turn. Review the chat before retrying." else null,
            durable?.notice?.startsWith("Connection lost.") == true || turn is TurnState.Truncated,
            durable?.submission?.runId != null || (turn is TurnState.Streaming && turn.canStop) || turn is TurnState.Finished || savedLegacyReply != null,
            voiceActivities((turn as? TurnState.Streaming)?.parts.orEmpty()),
            durable?.submission?.stopRequested == true || (turn as? TurnState.Streaming)?.stopRequested == true,
            durable?.stopError,
            durable?.submission?.terminal == false || durable == null && turn is TurnState.Streaming,
            approval = durable?.pendingApproval,
            approvalNotice = durable?.notice?.takeIf { durable.approval != null })
    }
    override fun send(text: String): Boolean {
        if (!ready || run().busy) return false
        previousIds = (store.history as? UiState.Ready)?.value.orEmpty().map { it.id }.toSet()
        if (store.runs == null) legacyIdentity = "voice-legacy-${java.util.UUID.randomUUID()}"
        val prompt = if (canvas == null) text else canvas.prepare(id, text) ?: return false
        return store.send(prompt)
    }
    override fun decideApproval(target: PendingRunApproval, choice: String) = ready &&
        store.runs?.respond(id, target.request.requestId, choice, target.runId, target.request) == true
    override fun cancel() { if (selected) store.stop() }
}

/** One user-started session; lifecycle is service-owned, never Activity-owned. */
internal class VoiceSession(val sessionId: String, val profileId: String, private val scope: CoroutineScope,
    private val conversation: VoiceConversation, private val config: VoiceConfiguration,
    private val output: VoiceOutput?, private val capture: VoiceCapture,
    private val transcriber: () -> VoiceTranscriber = {
        check(!config.transcribeViaServer && config.recognition == VoiceRecognition.PRIVATE_SERVER) { "The local speech runtime is not connected." }
        HttpVoiceTranscriber(config)
    },
    private val onClosed: () -> Unit = {},
    private val beforeResume: () -> Boolean = { true },
    private val onExplicitRetry: () -> Unit = {},
    private val onPlayback: (String, Boolean) -> Unit = { _, _ -> },
    private val priorInterruptedPlayback: String? = null,
    private val onTranscript: (String) -> Unit = {},
    private val initialTranscript: String = "",
    private val onSubmission: (String?) -> Unit = {},
    private val priorRequestId: String? = null,
    private val onDictation: ((String) -> Unit)? = null,
    private val stopWaitMs: Long = 5_000) {
    var state by mutableStateOf(VoiceState.STARTING); private set
    var mode by mutableStateOf(VoiceMode.CONVERSATION); private set
    var transcript by mutableStateOf(initialTranscript); private set
    var partialTranscript by mutableStateOf(""); private set
    var notice by mutableStateOf<String?>(null); private set
    var inputRouteNotice by mutableStateOf<String?>(null); private set
    val outputRouteNotice get() = output?.routeNotice
    var voiceName by mutableStateOf(""); private set
    var microphoneMuted by mutableStateOf(false); private set
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
    private val announced = mutableSetOf<String>()
    private data class Utterance(val text: String, val done: (() -> Unit)? = null, val approval: PendingRunApproval? = null)
    private val speech = ArrayDeque<Utterance>()
    private val activitySpeech = ArrayDeque<String>()
    private var speaking = false
    private var activeSpeech: Utterance? = null
    private var activeActivity = false
    private var audible = false
    private var tentative = false
    private var audioSuspended = false
    private val recentPlayback = ArrayDeque<Pair<Long, String>>()
    private var ownShortPhrases = emptyList<String>()
    private var generation = 0L
    private var micOpen = false
    val audioIdle: Boolean get() = !micOpen && !speaking
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
    private var outputPreparation = 0L
    private var startupReconciled = false
    private var approvalAtSpeechStart: PendingRunApproval? = null
    private var reviewedApproval: PendingRunApproval? = null
    private var heardBeforeSpeech = false
    private var ownAudioAtSpeechStart = ""
    private var completingTurn = false
    private var closed = false
    private var interruption: Job? = null
    private var interrupting = false
    var dictationToDraft by mutableStateOf(false); private set

    fun showNotice(message: String) { notice = message }
    fun start(initialMode: VoiceMode = VoiceMode.CONVERSATION) {
        mode = initialMode
        if (output == null) {
            require(initialMode == VoiceMode.PUSH_TO_TALK && onDictation != null)
            dictationToDraft = true
        }
        prepareOutput(::readyToListen)
        observe = scope.launch {
            var previousApproval: PendingRunApproval? = null
            snapshotFlow { conversation.selected to conversation.run() }.collectLatest { (_, observed) ->
                // Main.immediate can wake us inside the store's snapshot publication. A queued
                // send must wait for that update to finish and recheck the current run.
                val run = if (!observed.busy && queuedText.isNotBlank()) {
                    yield()
                    conversation.run()
                } else observed
                if (closed) return@collectLatest
                if (!conversation.selected) { pause("Voice stays with its original chat. Reopen that chat and resume."); return@collectLatest }
                if (state == VoiceState.PAUSED) return@collectLatest
                listeningBlock(run)?.let { pause(it); return@collectLatest }
                if (run.approval != previousApproval) {
                    reviewedApproval = null
                    notice = if (run.approvalPending) APPROVAL_WAITING else null
                    speech.removeAll { it.approval != null && it.approval != run.approval }
                    previousApproval = run.approval
                }
                if (run.approvalPending) {
                    if (run.approvalNotice != null) notice = run.approvalNotice
                    interrupting = false
                    interruption?.cancel()
                    interruption = null
                }
                if (interrupting) {
                    if (run.stopError != null) { pause(run.stopError); return@collectLatest }
                    if (!run.busy) {
                        interrupting = false; interruption?.cancel(); interruption = null
                        submitted = false; finished = false; ownedRun = null
                        if (state in setOf(VoiceState.RECORDING, VoiceState.TRANSCRIBING, VoiceState.REVIEW)) return@collectLatest
                    } else return@collectLatest
                }
                if (!submitted) {
                    if (!run.busy && queuedText.isNotBlank() && state != VoiceState.TRANSCRIBING) {
                        transcript = queuedText; sendTranscript()
                    } else if (!run.busy && state == VoiceState.WAITING && !speaking) listen()
                    return@collectLatest
                }
                if (run.error != null) { pause(run.error); return@collectLatest }
                if (ownedRun == null) ownedRun = run.identity
                if (ownedRun != null && run.identity != ownedRun) {
                    awaitingAdmission = false; pendingForAdmission = false
                    pause("Another turn started in this chat. Review the chat before resuming.")
                    return@collectLatest
                }
                if (awaitingAdmission && run.admitted) {
                    awaitingAdmission = false
                    if (pendingForAdmission) { saveTranscript(""); pendingForAdmission = false }
                }
                if (run.text.isNotBlank() && firstTextMs == null) firstTextMs = (System.nanoTime() - endedAt) / 1_000_000
                collectPlayback(run)
                if (!run.busy && run.final) {
                    finished = true
                    if (!speaking && speech.isEmpty() && activitySpeech.isEmpty() && !tentative && !audioSuspended) turnDone()
                } else if (!speaking && !audioSuspended && state !in setOf(VoiceState.RECORDING, VoiceState.TRANSCRIBING, VoiceState.REVIEW)) {
                    state = if (queuedText.isNotBlank() || run.approvalPending) VoiceState.WAITING else VoiceState.WORKING
                }
            }
        }
    }
    private fun readyToListen() {
        if (startupReconciled) { resumeReady(); return }
        startupReconciled = true
        val existing = conversation.run()
        if (priorRequestId != null && priorRequestId == existing.identity && existing.busy) {
            submitted = true; ownedRun = priorRequestId; suppressed = true; awaitingAdmission = true; pendingForAdmission = true
            state = VoiceState.WAITING; notice = "Recovering the existing voice turn. It will not be submitted again."; openMic()
        } else if (priorRequestId != null && priorRequestId == existing.identity && existing.admitted) {
            saveTranscript(""); transcript = ""; listen()
        } else if (transcript.isNotBlank()) { state = VoiceState.REVIEW; notice = "Saved voice words restored. Check the chat before sending if delivery was interrupted." }
        else listen()
    }
    private fun prepareOutput(whenReady: () -> Unit) {
        val preparation = ++outputPreparation
        val token = generation
        fun ready(name: String) {
            if (!closed && token == generation) {
                voiceName = name; readyOutput = true
                if (ensureAudio()) whenReady()
            }
        }
        if (output == null) {
            ready("Dictation")
            return
        }
        output.prepare(::ready, { problem ->
            if (!closed && preparation == outputPreparation && (readyOutput || token == generation)) {
                readyOutput = false
                pause(problem)
            }
        })
    }
    private fun listeningBlock(run: VoiceRun): String? = when {
        run.attention -> "Hermes needs your attention. Open the chat to review its request."
        run.connectionLost -> "Connection to Hermes was lost. Its existing run will recover without submitting again. Resume voice after reconnecting."
        else -> null
    }
    private fun ensureAudio(): Boolean {
        if (beforeResume()) return true
        pause("Audio is unavailable. Resume after the call or audio interruption finishes.")
        return false
    }
    private fun openMic() {
        if (micOpen || closed || audioSuspended || state == VoiceState.PAUSED || (mode == VoiceMode.PUSH_TO_TALK && !pushHeld)) return
        listeningBlock(conversation.run())?.let { pause(it); return }
        if (!ensureAudio()) return
        micOpen = capture.start(::frame, ::pause)
        if (mode != VoiceMode.CALL && micOpen && (state == VoiceState.LISTENING || state == VoiceState.WAITING && !submitted)) output?.chime()
    }
    private fun closeMic(keepTranscriber: Boolean = false) {
        micOpen = false; capture.close(); gate.reset()
        if (!keepTranscriber) cancelLiveTranscription()
    }
    private fun cancelLiveTranscription() {
        if (currentTranscriber is StreamingVoiceTranscriber) {
            currentTranscriber?.close(); currentTranscriber = null
        }
        partialTranscript = ""
    }
    private fun startLiveTranscription() {
        if (!config.transcribeViaServer) return
        cancelLiveTranscription()
        val provider = runCatching { transcriber() }.getOrNull() ?: return
        currentTranscriber = provider
        if (provider is StreamingVoiceTranscriber) {
            provider.begin { text -> scope.launch {
                if (!closed && currentTranscriber === provider &&
                    state in setOf(VoiceState.RECORDING, VoiceState.TRANSCRIBING)) partialTranscript = text
            } }
        }
    }
    private fun listen() {
        if (closed || audioSuspended || !readyOutput || !conversation.selected) return
        if (conversation.run().busy) { state = VoiceState.WAITING; openMic(); return }
        if (mode == VoiceMode.PUSH_TO_TALK && !pushHeld) { state = VoiceState.READY; closeMic(); return }
        state = VoiceState.LISTENING
        val wasOpen = micOpen
        openMic()
        if (mode != VoiceMode.CALL && wasOpen && micOpen && !gate.active) output?.chime()
    }
    /** Public to the capture port; deterministic PCM tests exercise the same path as AudioRecord. */
    fun frame(pcm: ShortArray, count: Int) {
        if (closed || audioSuspended || microphoneMuted || state in setOf(VoiceState.PAUSED, VoiceState.STOPPED, VoiceState.TRANSCRIBING, VoiceState.REVIEW) || completingTurn) return
        val result = gate.accept(pcm, count, holdTurn, manual = mode == VoiceMode.PUSH_TO_TALK && pushHeld)
        if (result.began) {
            approvalAtSpeechStart = conversation.run().approval
            heardBeforeSpeech = reviewedApproval != null && reviewedApproval == approvalAtSpeechStart && !speaking
            val now = System.nanoTime()
            val recent = recentPlayback.filter { now - it.first <= 1_500_000_000L }.map { it.second } +
                listOfNotNull(activeSpeech?.text?.takeIf { audible })
            ownAudioAtSpeechStart = recent.joinToString(" ")
            ownShortPhrases = recent.filter { it.trim().split(Regex("\\s+")).size <= 2 }
            if (speaking || submitted) { tentative = true; suspendPlayback() }
            state = VoiceState.RECORDING
            startLiveTranscription()
        }
        result.audio?.let { (currentTranscriber as? StreamingVoiceTranscriber)?.audio(it) }
        if (result.complete != null && !holdTurn) transcribe(result.complete)
        // Holding a turn prevents end-of-turn inference. At the bounded recording limit,
        // keep the captured utterance for transcription rather than growing indefinitely.
        else if (result.complete != null) transcribe(result.complete)
        else if (!gate.active && state == VoiceState.RECORDING) recoverTentative()
    }
    fun updateMicrophoneMute(muted: Boolean) {
        if (microphoneMuted == muted) return
        microphoneMuted = muted
        // Discard an unfinished utterance when muted; never submit it after unmute.
        gate.reset()
        if (muted) cancelLiveTranscription()
        if (muted && state == VoiceState.RECORDING) recoverTentative()
    }
    fun beginPushToTalk(toDraft: Boolean = false) {
        if (closed || audioSuspended || (!toDraft && mode != VoiceMode.PUSH_TO_TALK) || state in setOf(VoiceState.TRANSCRIBING, VoiceState.REVIEW, VoiceState.PAUSED)) return
        if (speaking) stopSpeaking()
        if (toDraft) { closeMic(); mode = VoiceMode.PUSH_TO_TALK; dictationToDraft = true }
        pushHeld = true; holdTurn = true
        if (!readyOutput) return
        state = VoiceState.LISTENING; openMic()
    }
    fun endPushToTalk(canceled: Boolean = false) {
        if (!pushHeld) return
        pushHeld = false; holdTurn = false
        val bytes = if (canceled) null else gate.finish()
        closeMic(keepTranscriber = bytes != null)
        if (bytes != null) transcribe(bytes) else if (dictationToDraft) close() else listen()
    }
    fun hold(value: Boolean) { holdTurn = value }
    fun finishRecording() { gate.finish()?.let(::transcribe) ?: recoverTentative() }
    fun chooseMode(value: VoiceMode) {
        if (output == null || mode == value) return
        if (pushHeld) { pushHeld = false; holdTurn = false; closeMic() }
        reviewedApproval = null
        approvalAtSpeechStart = null
        mode = value
        if (mode == VoiceMode.PUSH_TO_TALK && !pushHeld) closeMic()
        if (!submitted && state !in setOf(VoiceState.TRANSCRIBING, VoiceState.REVIEW, VoiceState.PAUSED)) listen()
    }
    private fun transcribe(bytes: ByteArray) {
        if (closed || transcriptionJob?.isActive == true) return
        val forceReview = mode == VoiceMode.PUSH_TO_TALK && pushHeld
        if (forceReview) { pushHeld = false; holdTurn = false }
        pendingAudio = bytes; canRetryTranscription = false
        // A call keeps the same AudioRecord (and Bluetooth communication route)
        // alive across recognition and playback. Frames are drained, not buffered,
        // while TRANSCRIBING. Pause, route loss and End still release capture.
        if (mode == VoiceMode.CALL) gate.reset() else closeMic(keepTranscriber = true)
        state = VoiceState.TRANSCRIBING
        endedAt = System.nanoTime(); transcriptionMs = null; firstTextMs = null; firstPlaybackMs = null
        val token = generation
        val provider = try { currentTranscriber ?: transcriber() } catch (error: Exception) {
            canRetryTranscription = true; pause(error.message ?: "Could not start the selected recognizer."); return
        }
        currentTranscriber = provider
        transcriptionJob = scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { provider.transcribe(bytes) }
            } finally {
                provider.close(); if (currentTranscriber === provider) currentTranscriber = null
            }
            if (closed || token != generation) return@launch
            partialTranscript = ""
            transcriptionMs = (System.nanoTime() - endedAt) / 1_000_000
            result.fold({ text ->
                inputRouteNotice = provider.routeNotice
                pendingAudio = null; canRetryTranscription = false
                if (text.isBlank() || likelyEcho(text, ownAudioAtSpeechStart, ownShortPhrases)) {
                    notice = if (text.isBlank()) "No speech recognized. Try again." else "Ignored playback echo."
                    if (dictationToDraft) close() else recoverTentative()
                    return@fold
                }
                if ((mode == VoiceMode.CONVERSATION || mode == VoiceMode.CALL) && !holdTurn &&
                    handleSpokenApproval(text)) return@fold
                discardPlayback()
                pendingForAdmission = false; transcript = text; saveTranscript(text); notice = null
                if (dictationToDraft) {
                    putWordsInDraft()
                    return@fold
                }
                interruptCurrentRun()
                if (mode == VoiceMode.DICTATION || holdTurn || forceReview) state = VoiceState.REVIEW else sendTranscript()
            }, { canRetryTranscription = true; pause(it.message ?: "Transcription failed. Try again when the service is available.") })
        }
    }
    private fun handleSpokenApproval(text: String): Boolean {
        val command = text.trim().trimEnd('.', '?', '!').trimEnd().lowercase()
        val choice = when (command) {
            "read the approval request", "read approval" -> null
            "allow this request once" -> "once"
            "deny this request" -> "deny"
            else -> return false
        }
        val target = conversation.run().approval
        if (!conversation.ready || target == null || target != approvalAtSpeechStart) {
            reviewedApproval = null
            notice = if (target == null) "No approval is waiting in this chat." else APPROVAL_WAITING
        } else if (choice == null) {
            reviewedApproval = null
            val details = listOfNotNull(target.request.description, target.request.command)
                .filter { it.isNotBlank() }.distinct().joinToString(". ")
            if (details.isBlank()) {
                notice = "Review this operation in chat."
            } else {
                val choices = target.request.choices
                val answers = listOfNotNull(
                    "“allow this request once”".takeIf { "once" in choices },
                    "“deny this request”".takeIf { "deny" in choices })
                val hint = when {
                    answers.size == 2 -> "To respond, start with allow or deny, then say this request. For allow, add once."
                    "once" in choices -> "To allow, say the word allow followed by this request once."
                    "deny" in choices -> "To decline, say deny followed by this request."
                    else -> "Review the available choices in chat."
                }
                speech.removeAll { it.approval != null }
                speech.addFirst(Utterance("Approval request. $details. $hint", approval = target, done = {
                    if (conversation.selected && conversation.run().approval == target) {
                        reviewedApproval = target
                        notice = if (answers.isEmpty()) hint else "Say ${answers.joinToString(" or ")}."
                    }
                    if (!submitted) listen()
                }))
            }
        } else if (!heardBeforeSpeech || reviewedApproval != target) {
            notice = APPROVAL_WAITING
        } else if (choice !in target.request.choices) {
            notice = "This choice is unavailable for the pending request."
        } else {
            reviewedApproval = null
            notice = if (conversation.decideApproval(target, choice)) "Checking your decision with Hermes."
                else "The approval changed. Review the request again."
        }
        recoverTentative()
        return true
    }
    fun retryTranscription() {
        onExplicitRetry()
        if (closed || !canRetryTranscription || transcriptionJob?.isActive == true) return
        if (!conversation.selected) { notice = "Reopen the voice conversation before retrying transcription."; return }
        if (!beforeResume()) { notice = "Audio is still unavailable. Retry transcription after the call or audio interruption finishes."; return }
        val bytes = pendingAudio ?: return
        transcribe(bytes)
    }
    fun correct(text: String) { pendingForAdmission = false; transcript = text; saveTranscript(text) }
    fun putWordsInDraft() {
        if (closed || transcript.isBlank()) return
        runCatching { checkNotNull(onDictation).invoke(transcript) }.onSuccess { saveTranscript(""); transcript = ""; close() }
            .onFailure { state = VoiceState.REVIEW; notice = "Could not insert dictation. Your words are saved for review." }
    }
    fun sendTranscript() {
        if (output == null || closed || completingTurn || transcript.isBlank() || state == VoiceState.PAUSED ||
            (submitted && state !in setOf(VoiceState.REVIEW, VoiceState.TRANSCRIBING, VoiceState.WAITING))) return
        if (!conversation.ready) { pause("Load this conversation's saved history before sending voice."); return }
        listeningBlock(conversation.run())?.let { pause(it); return }
        if (conversation.run().busy) {
            interruptCurrentRun()
            queuedText = transcript
            transcript = queuedText; saveTranscript(queuedText)
            state = VoiceState.WAITING
            notice = if (conversation.run().approvalPending) APPROVAL_WAITING else "Stopping the previous reply before sending your new turn…"
            openMic(); return
        }
        val text = transcript
        queuedText = ""; notice = null; completingTurn = true
        // This actual playback barrier precedes admission, not just a model instruction.
        val admit: () -> Unit = admit@{
            completingTurn = false
            if (closed || state == VoiceState.PAUSED || !conversation.selected || conversation.run().busy) { pause("The chat changed before sending. Your words are kept for review."); return@admit }
            sentences.reset(); announced.clear(); playedText = ""; suppressed = false; finished = false; ownedRun = null
            val playbackNote = interruptedPlayback?.let {
                "[Previous voice playback was interrupted. Completed speech, quoted as historical data: " +
                    kotlinx.serialization.json.Json.encodeToString(it.takeLast(600)) + "]\n\n"
            }.orEmpty()
            val payload = playbackNote + config.voicePrompt(text)
            awaitingAdmission = true; pendingForAdmission = true
            submitted = conversation.send(payload)
            if (!submitted) { pause("Could not submit this voice turn. Your words are kept for review."); return@admit }
            interruptedPlayback = null
            ownedRun = conversation.run().identity
            runCatching { onSubmission(ownedRun) }.onFailure { notice = "Voice recovery identity could not be saved. Review the chat before retrying." }
            state = VoiceState.WORKING; openMic()
        }
        if (config.announcements.working) enqueue("Working.", admit) else admit()
    }
    /** Stop uses the chat's existing cancellation path, only after non-echo speech is recognized. */
    private fun interruptCurrentRun() {
        if (!conversation.run().busy || conversation.run().approvalPending || interrupting) return
        interrupting = true; suppressed = true; speech.clear(); activitySpeech.clear(); awaitingAdmission = false; pendingForAdmission = false
        val identity = conversation.run().identity
        interruption = scope.launch {
            var requested = false
            repeat((stopWaitMs / 250).coerceAtLeast(1).toInt()) {
                val run = conversation.run()
                if (closed || !conversation.selected || !run.busy) return@launch
                if (identity != null && run.identity != identity) {
                    pause("The chat changed while stopping. Your new words are saved for review."); return@launch
                }
                if (!requested && run.executing && run.admitted && !run.approvalPending) { conversation.cancel(); requested = true }
                delay(250)
            }
            pause("The previous turn has not settled. Your new words are saved for review; check the chat before sending.")
            // No delayed observation or playback callback may submit these words now.
            if (transcript.isNotBlank()) { submitted = false; state = VoiceState.REVIEW }
        }
    }
    private fun enqueue(text: String, done: (() -> Unit)? = null) {
        if (closed || state == VoiceState.PAUSED) return
        speech.addLast(Utterance(text, done))
        pump(); prefetchNext()
    }
    private fun pump() {
        if (output == null || speaking || tentative || audioSuspended || state == VoiceState.PAUSED ||
            (speech.isEmpty() && activitySpeech.isEmpty()) || closed) return
        if (!ensureAudio()) return
        activeActivity = speech.isEmpty() && activitySpeech.isNotEmpty()
        val utterance = if (activeActivity) Utterance(activitySpeech.removeFirst()) else speech.removeFirst()
        val text = utterance.text
        activeSpeech = utterance
        val token = generation
        speaking = true; audible = false; state = VoiceState.SPEAKING
        output.speak(text, started = {
            if (!closed && token == generation) {
                audible = true
                recentPlayback.addLast(System.nanoTime() to text.takeLast(600))
                while (recentPlayback.size > 6) recentPlayback.removeFirst()
            }
            if (!closed && token == generation && submitted && firstPlaybackMs == null)
                firstPlaybackMs = (System.nanoTime() - endedAt) / 1_000_000
        }) {
            if (closed || token != generation) return@speak
            recentPlayback.addLast(System.nanoTime() to text.takeLast(600))
            while (recentPlayback.size > 6) recentPlayback.removeFirst()
            val heard = audible
            activeSpeech = null; audible = false; speaking = false
            if (utterance.approval == null) {
                playedText = listOf(playedText, text).filter { it.isNotBlank() }.joinToString(" ").takeLast(1200)
                if (submitted) rememberPlayback(false)
            }
            if (utterance.approval == null || heard) utterance.done?.invoke()
            pump()
            if (!speaking && speech.isEmpty() && activitySpeech.isEmpty()) {
                if (finished) turnDone()
                else if (submitted) state = VoiceState.WORKING
            }
        }
        // Refill after every handoff, even when Hermes already finished and no
        // further text update will arrive to trigger collectPlayback().
        prefetchNext()
    }
    private fun turnDone() {
        if (closed || tentative || audioSuspended || state in setOf(VoiceState.TRANSCRIBING, VoiceState.RECORDING, VoiceState.REVIEW, VoiceState.PAUSED)) return
        submitted = false; finished = false
        if (queuedText.isNotBlank()) { transcript = queuedText; sendTranscript() }
        else if (mode == VoiceMode.CALL || mode == VoiceMode.CONVERSATION) listen()
        else { transcript = ""; state = VoiceState.READY; closeMic() }
    }
    private fun saveTranscript(text: String) {
        unsent = text.isNotBlank()
        runCatching { onTranscript(text) }.onFailure { notice = "Voice transcription could not be saved on this device." }
    }
    private fun rememberPlayback(interrupted: Boolean) {
        runCatching { onPlayback(playedText, interrupted) }.onFailure { notice = "Playback progress could not be saved on this device." }
    }
    private fun collectPlayback(run: VoiceRun) {
        if (suppressed) return
        val activities = run.activities.filter { announced.add(it.id) }
        // A tool boundary is not a sentence boundary. Queue all ready prose before
        // pumping, so a cue cannot jump in front of text in this same snapshot.
        sentences.take(run.text, run.final).forEach { speech.addLast(Utterance(it)) }
        // One waiting cue is enough to signal activity, even during a large batch.
        // The four flags still independently decide which kinds are eligible.
        if (activitySpeech.isEmpty()) activities.firstOrNull { config.announcements.enabled(it.kind) }
            ?.let { activitySpeech.addLast(it.text) }
        pump()
        prefetchNext()
    }
    private fun prefetchNext() {
        if (output != null && speaking && !tentative && !audioSuspended && !closed && state != VoiceState.PAUSED)
            (speech.firstOrNull()?.text ?: activitySpeech.firstOrNull())?.let(output::prefetch)
    }
    /** A partial phrase restarts; fully completed phrases never enter the queue again. */
    private fun suspendPlayback() {
        activeSpeech?.let { if (activeActivity) activitySpeech.addFirst(it.text) else speech.addFirst(it) }
        activeSpeech = null
        generation++; output?.stop(); speaking = false; audible = false
    }
    private fun recoverTentative() {
        cancelLiveTranscription()
        tentative = false
        if (closed || state == VoiceState.PAUSED || audioSuspended) return
        state = VoiceState.WORKING
        pump()
        if (!speaking) { if (finished) turnDone() else listen() }
        if (speaking) openMic()
    }
    fun suspendForAudio() {
        if (closed || state == VoiceState.PAUSED || audioSuspended) return
        if (state in setOf(VoiceState.STARTING, VoiceState.RECORDING, VoiceState.TRANSCRIBING, VoiceState.REVIEW) || completingTurn) {
            pause("Audio interrupted your voice turn. Resume when ready."); return
        }
        audioSuspended = true; suspendPlayback(); closeMic()
        state = VoiceState.WAITING; notice = "Audio is temporarily interrupted. Playback will continue when focus returns."
    }
    fun recoverAudio() {
        if (!audioSuspended || closed || state == VoiceState.PAUSED) return
        if (!conversation.selected || listeningBlock(conversation.run()) != null || !beforeResume()) {
            pause("Audio is unavailable. Resume when ready."); return
        }
        audioSuspended = false; notice = null; state = VoiceState.WORKING
        pump()
        if (speaking) openMic() else if (finished) turnDone() else listen()
    }
    fun playbackDiagnostics() = "state=${state.name} answer_queue=${speech.size} activity_queue=${activitySpeech.size} speaking=$speaking tentative=$tentative audio_suspended=$audioSuspended"
    private fun discardPlayback() {
        if (submitted) { interruptedPlayback = playedText; rememberPlayback(true) }
        generation++; output?.stop(); activeSpeech = null; audible = false; speech.clear(); activitySpeech.clear()
        speaking = false; suppressed = true; tentative = false; completingTurn = false
    }
    fun stopSpeaking() {
        discardPlayback()
        if (audioSuspended) return
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
        reviewedApproval = null; approvalAtSpeechStart = null; heardBeforeSpeech = false
        suspendPlayback(); tentative = false; audioSuspended = false
        // Unsent words always require review after an explicit pause, never a delayed cue callback.
        if (completingTurn) { speech.clear(); activitySpeech.clear(); completingTurn = false }
        if (pushHeld) holdTurn = false
        pushHeld = false
        interruption?.cancel(); interruption = null; interrupting = false
        closeMic(); currentTranscriber?.close(); transcriptionJob?.cancel(); transcriptionJob = null
        canRetryTranscription = pendingAudio != null
        state = VoiceState.PAUSED; notice = message
        queuedText = ""
    }
    fun resume() {
        if (closed) return
        if (!conversation.selected) { notice = "Reopen the voice conversation before resuming."; return }
        listeningBlock(conversation.run())?.let { pause(it); return }
        if (!beforeResume()) { notice = "Audio is still unavailable. Resume after the call or audio interruption finishes."; return }
        onExplicitRetry()
        notice = null; queuedText = ""
        if (!readyOutput) {
            state = VoiceState.STARTING
            output?.retryPreparation()
            prepareOutput(::readyToListen)
        } else readyToListen()
    }
    private fun resumeReady() {
        if (unsent && !pendingForAdmission && transcript.isNotBlank()) {
            submitted = false; state = VoiceState.REVIEW
        } else if (submitted && !suppressed) {
            val run = conversation.run()
            if (ownedRun != null && run.identity != ownedRun) { pause("Another turn started. Review the chat before resuming."); return }
            state = VoiceState.WORKING
            collectPlayback(run); finished = !run.busy && run.final
            if (!speaking && speech.isEmpty() && activitySpeech.isEmpty() && finished) turnDone() else openMic()
        } else if (submitted && conversation.run().busy) {
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
        observe?.cancel(); output?.close(); onClosed()
    }
}
