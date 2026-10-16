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
    val final: Boolean = false, val attention: Boolean = false, val error: String? = null, val connectionLost: Boolean = false, val admitted: Boolean = true,
    val activities: List<VoiceActivity> = emptyList(), val stopRequested: Boolean = false, val stopError: String? = null,
    val executing: Boolean = busy)
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
            durable?.submission?.runId != null || (turn is TurnState.Streaming && turn.canStop) || turn is TurnState.Finished || savedLegacyReply != null,
            voiceActivities((turn as? TurnState.Streaming)?.parts.orEmpty()),
            durable?.submission?.stopRequested == true || (turn as? TurnState.Streaming)?.stopRequested == true,
            durable?.notice?.takeIf { it.startsWith("Stop was not confirmed.") },
            durable?.submission?.terminal == false || durable == null && turn is TurnState.Streaming)
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
    private val transcriber: () -> VoiceTranscriber = {
        check(config.recognition == VoiceRecognition.PRIVATE_SERVER) { "The local speech runtime is not connected." }
        HttpVoiceTranscriber(config)
    },
    private val onClosed: () -> Unit = {},
    private val beforeResume: () -> Boolean = { true },
    private val onPlayback: (String, Boolean) -> Unit = { _, _ -> },
    private val priorInterruptedPlayback: String? = null,
    private val onTranscript: (String) -> Unit = {},
    private val initialTranscript: String = "",
    private val onSubmission: (String?) -> Unit = {},
    private val priorRequestId: String? = null,
    private val onDictation: ((String) -> Unit)? = null,
    private val stopWaitMs: Long = 30_000) {
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
    private val announced = mutableSetOf<String>()
    private val speech = ArrayDeque<Pair<String, (() -> Unit)?>>()
    private val activitySpeech = ArrayDeque<String>()
    private var speaking = false
    private var activeSpeech: Pair<String, (() -> Unit)?>? = null
    private var activeActivity = false
    private var audible = false
    private var tentative = false
    private var audioSuspended = false
    private val recentPlayback = ArrayDeque<Pair<Long, String>>()
    private var ownShortPhrases = emptyList<String>()
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
    private var startupReconciled = false
    private var ownAudioAtSpeechStart = ""
    private var completingTurn = false
    private var closed = false
    private var interruption: Job? = null
    private var interrupting = false
    var dictationToDraft by mutableStateOf(false); private set

    fun showNotice(message: String) { notice = message }
    fun start(initialMode: VoiceMode = VoiceMode.CONVERSATION) {
        mode = initialMode
        prepareOutput(::readyToListen)
        observe = scope.launch {
            snapshotFlow { conversation.selected to conversation.run() }.collectLatest { (selected, run) ->
                if (closed) return@collectLatest
                if (!selected) { pause("Voice stays with its original chat. Reopen that chat and resume."); return@collectLatest }
                if (state == VoiceState.PAUSED) return@collectLatest
                listeningBlock(run)?.let { pause(it); return@collectLatest }
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
                if (awaitingAdmission && run.admitted) {
                    awaitingAdmission = false
                    if (pendingForAdmission) { saveTranscript(""); pendingForAdmission = false }
                }
                if (ownedRun == null) ownedRun = run.identity
                if (ownedRun != null && run.identity != ownedRun) { pause("Another turn started in this chat. Resume when it finishes."); return@collectLatest }
                if (run.text.isNotBlank() && firstTextMs == null) firstTextMs = (System.nanoTime() - endedAt) / 1_000_000
                collectPlayback(run)
                if (!run.busy && run.final) {
                    finished = true
                    if (!speaking && speech.isEmpty() && activitySpeech.isEmpty() && !tentative && !audioSuspended) turnDone()
                } else if (!speaking && !audioSuspended && state !in setOf(VoiceState.RECORDING, VoiceState.TRANSCRIBING, VoiceState.REVIEW)) {
                    state = if (queuedText.isNotBlank()) VoiceState.WAITING else VoiceState.WORKING
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
        val token = generation
        output.prepare({ name ->
            if (!closed && token == generation) {
                voiceName = name; readyOutput = true
                if (!beforeResume()) { pause("Audio is unavailable. Resume after the call or audio interruption finishes.") }
                else whenReady()
            }
        }, { problem -> if (!closed && token == generation) pause(problem) })
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
        if (micOpen && (state == VoiceState.LISTENING || state == VoiceState.WAITING && !submitted)) output.chime()
    }
    private fun closeMic() { micOpen = false; capture.close(); gate.reset() }
    private fun listen() {
        if (closed || !readyOutput || !conversation.selected) return
        if (conversation.run().busy) { state = VoiceState.WAITING; openMic(); return }
        if (mode == VoiceMode.PUSH_TO_TALK && !pushHeld) { state = VoiceState.READY; closeMic(); return }
        state = VoiceState.LISTENING
        val wasOpen = micOpen
        openMic()
        if (wasOpen && micOpen && !gate.active) output.chime()
    }
    /** Public to the capture port; deterministic PCM tests exercise the same path as AudioRecord. */
    fun frame(pcm: ShortArray, count: Int) {
        if (closed || state in setOf(VoiceState.PAUSED, VoiceState.STOPPED, VoiceState.TRANSCRIBING, VoiceState.REVIEW) || completingTurn) return
        val result = gate.accept(pcm, count, holdTurn)
        if (result.began) {
            val now = System.nanoTime()
            val recent = recentPlayback.filter { now - it.first <= 1_500_000_000L }.map { it.second } +
                listOfNotNull(activeSpeech?.first?.takeIf { audible })
            ownAudioAtSpeechStart = recent.joinToString(" ")
            ownShortPhrases = recent.filter { it.trim().split(Regex("\\s+")).size <= 2 }
            if (speaking || submitted) { tentative = true; suspendPlayback() }
            state = VoiceState.RECORDING
        }
        if (result.complete != null && !holdTurn) transcribe(result.complete)
        // Holding a turn prevents end-of-turn inference. At the bounded recording limit,
        // keep the captured utterance for transcription rather than growing indefinitely.
        else if (result.complete != null) transcribe(result.complete)
        else if (!gate.active && state == VoiceState.RECORDING) recoverTentative()
    }
    fun beginPushToTalk(toDraft: Boolean = false) {
        if (closed || (!toDraft && mode != VoiceMode.PUSH_TO_TALK) || state in setOf(VoiceState.TRANSCRIBING, VoiceState.REVIEW, VoiceState.PAUSED)) return
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
        closeMic()
        if (bytes != null) transcribe(bytes) else if (dictationToDraft) close() else listen()
    }
    fun hold(value: Boolean) { holdTurn = value }
    fun finishRecording() { gate.finish()?.let(::transcribe) ?: recoverTentative() }
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
        val provider = try { transcriber() } catch (error: Exception) {
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
            transcriptionMs = (System.nanoTime() - endedAt) / 1_000_000
            result.fold({ text ->
                pendingAudio = null; canRetryTranscription = false
                if (text.isBlank() || likelyEcho(text, ownAudioAtSpeechStart, ownShortPhrases)) {
                    notice = if (text.isBlank()) "No speech recognized. Try again." else "Ignored playback echo."
                    if (dictationToDraft) close() else recoverTentative()
                    return@fold
                }
                discardPlayback()
                pendingForAdmission = false; transcript = text; saveTranscript(text); notice = null
                interruptCurrentRun()
                if (dictationToDraft) {
                    putWordsInDraft()
                    return@fold
                }
                if (mode == VoiceMode.DICTATION || holdTurn || forceReview) state = VoiceState.REVIEW else sendTranscript()
            }, { canRetryTranscription = true; pause(it.message ?: "Transcription failed. Try again when the service is available.") })
        }
    }
    fun retryTranscription() {
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
        if (closed || completingTurn || transcript.isBlank() || state == VoiceState.PAUSED ||
            (submitted && state !in setOf(VoiceState.REVIEW, VoiceState.TRANSCRIBING, VoiceState.WAITING))) return
        if (!conversation.ready) { pause("Load this conversation's saved history before sending voice."); return }
        listeningBlock(conversation.run())?.let { pause(it); return }
        if (conversation.run().busy) {
            interruptCurrentRun()
            queuedText = transcript
            transcript = queuedText; saveTranscript(queuedText)
            state = VoiceState.WAITING; notice = "Stopping the previous reply before sending your new turn…"; openMic(); return
        }
        val text = transcript
        queuedText = ""; completingTurn = true
        // This actual playback barrier precedes admission, not just a model instruction.
        val admit: () -> Unit = admit@{
            completingTurn = false
            if (closed || state == VoiceState.PAUSED || !conversation.selected || conversation.run().busy) { pause("The chat changed before sending. Your words are kept for review."); return@admit }
            sentences.reset(); announced.clear(); playedText = ""; suppressed = false; finished = false; ownedRun = null
            val playbackNote = interruptedPlayback?.let {
                "[Previous voice playback was interrupted. Completed speech, quoted as historical data: " +
                    kotlinx.serialization.json.Json.encodeToString(it.takeLast(600)) + "]\n\n"
            }.orEmpty()
            val payload = playbackNote + (if (config.concise) "$VOICE_PRESET\n\n$text" else text)
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
        if (!conversation.run().busy || interrupting) return
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
                if (!requested && run.executing && run.admitted) { conversation.cancel(); requested = true }
                delay(250)
            }
            pause("Stop has not settled. Your new words are saved; check the chat before resuming.")
        }
    }
    private fun enqueue(text: String, done: (() -> Unit)? = null) {
        if (closed || state == VoiceState.PAUSED) return
        speech.addLast(text to done)
        pump(); prefetchNext()
    }
    private fun pump() {
        if (speaking || tentative || audioSuspended || state == VoiceState.PAUSED ||
            (speech.isEmpty() && activitySpeech.isEmpty()) || closed) return
        activeActivity = activitySpeech.isNotEmpty()
        val (text, done) = if (activeActivity) activitySpeech.removeFirst() to null else speech.removeFirst()
        activeSpeech = text to done
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
            activeSpeech = null; audible = false
            speaking = false; playedText = listOf(playedText, text).filter { it.isNotBlank() }.joinToString(" ").takeLast(1200)
            if (submitted) rememberPlayback(false)
            done?.invoke()
            pump()
            if (!speaking && speech.isEmpty() && activitySpeech.isEmpty()) {
                if (finished) turnDone()
                else if (submitted) state = VoiceState.WORKING
            }
        }
    }
    private fun turnDone() {
        if (closed || tentative || audioSuspended || state in setOf(VoiceState.TRANSCRIBING, VoiceState.RECORDING, VoiceState.REVIEW, VoiceState.PAUSED)) return
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
    private fun collectPlayback(run: VoiceRun) {
        if (suppressed) return
        val activities = run.activities.filter { announced.add(it.id) }
        activities.filter { config.announcements.enabled(it.kind) }.forEach { activitySpeech.addLast(it.text) }
        pump()
        // Flush genuine prose at a tool boundary, through its single answer lane.
        sentences.take(run.text, run.final || activities.any { it.kind != VoiceActivityKind.THINKING }).forEach { enqueue(it) }
        prefetchNext()
    }
    private fun prefetchNext() {
        if (speaking && !tentative && !audioSuspended && !closed && state != VoiceState.PAUSED)
            (activitySpeech.firstOrNull() ?: speech.firstOrNull()?.first)?.let(output::prefetch)
    }
    /** A partial phrase restarts; fully completed phrases never enter the queue again. */
    private fun suspendPlayback() {
        activeSpeech?.let { if (activeActivity) activitySpeech.addFirst(it.first) else speech.addFirst(it) }
        activeSpeech = null
        generation++; output.stop(); speaking = false; audible = false
    }
    private fun recoverTentative() {
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
        generation++; output.stop(); activeSpeech = null; audible = false; speech.clear(); activitySpeech.clear()
        speaking = false; suppressed = true; tentative = false; audioSuspended = false; completingTurn = false
    }
    fun stopSpeaking() {
        discardPlayback()
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
        suspendPlayback(); tentative = false; audioSuspended = false
        // Unsent words always require review after an explicit pause, never a delayed cue callback.
        if (completingTurn) { speech.clear(); activitySpeech.clear(); completingTurn = false }
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
        notice = null; queuedText = ""
        if (!readyOutput) {
            state = VoiceState.STARTING
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
        observe?.cancel(); output.close(); onClosed()
    }
}
