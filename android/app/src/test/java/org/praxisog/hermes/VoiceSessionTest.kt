package org.praxisog.hermes

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CopyOnWriteArrayList

class VoiceSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val target = object : VoiceConversation {
        var selection by mutableStateOf(true)
        var value by mutableStateOf(VoiceRun())
        val sent = CopyOnWriteArrayList<String>()
        var canceled = 0
        var acknowledge = true
        override val selected get() = selection
        override val ready get() = selection
        override fun run() = value
        override fun send(text: String): Boolean { sent += text; value = VoiceRun("run-${sent.size}", busy = true, admitted = acknowledge); return true }
        override fun cancel() { canceled++ }
    }
    private val output = object : VoiceOutput {
        val said = CopyOnWriteArrayList<String>()
        var completion: (() -> Unit)? = null
        var failure: ((String) -> Unit)? = null
        override fun prepare(onReady: (String) -> Unit, onError: (String) -> Unit) { failure = onError; onReady("offline-fixture") }
        override fun speak(text: String, started: () -> Unit, complete: () -> Unit) { said += text; completion = complete; started() }
        fun complete() { val callback = completion; completion = null; callback?.invoke(); tick() }
        override fun stop() { completion = null }
        override fun close() = stop()
    }
    private val capture = object : VoiceCapture {
        var active = false
        override fun start(frame: (ShortArray, Int) -> Unit, error: (String) -> Unit): Boolean { active = true; return true }
        override fun close() { active = false }
    }
    @Volatile private var recognized = "Tell me about the costume."
    @Volatile private var failure = false
    @Volatile private var unavailableRecognizer = false
    @Volatile private var transcriptions = 0
    private val voice = VoiceSession("chat", "profile", scope, target, VoiceConfiguration(concise = false), output, capture,
        transcriber = {
            check(!unavailableRecognizer) { "Selected local weights are missing." }
            object : VoiceTranscriber {
            override fun transcribe(wav: ByteArray): Result<String> { transcriptions++; return if (failure) Result.failure(IllegalStateException("Speech offline")) else Result.success(recognized) }
            override fun close() {}
        } })
    private fun tick() { Snapshot.sendApplyNotifications(); Thread.sleep(15) }
    private fun await(check: () -> Boolean) { repeat(200) { tick(); if (check()) return }; fail("Voice condition timed out: ${voice.state}") }
    private fun utterance() {
        assertTrue(capture.active)
        repeat(25) { voice.frame(ShortArray(320) { if (it % 2 == 0) 2500 else -2500 }, 320) }
        repeat(90) { voice.frame(ShortArray(320), 320) }
        await { voice.state != VoiceState.TRANSCRIBING }
    }
    @After fun close() { voice.close(); scope.cancel() }
    @Test fun actualPreamblePlaybackPrecedesOneAdmissionAndAnswerStreamsBeforeCompletion() {
        voice.start(); utterance()
        assertEquals(listOf("I'll ask Hermes."), output.said)
        assertTrue(target.sent.isEmpty())
        output.complete(); assertEquals(1, target.sent.size)
        target.value = VoiceRun("run-1", true, "Here is the first sentence. More")
        await { output.said.size == 2 }
        assertEquals("Here is the first sentence.", output.said.last())
        assertTrue(target.value.busy)
        output.complete()
        target.value = VoiceRun("run-1", false, "Here is the first sentence. More detail.", final = true)
        await { output.said.size == 3 }; output.complete()
        await { voice.state == VoiceState.LISTENING }
        assertEquals(1, target.sent.size)
        assertEquals(1, transcriptions)
    }
    @Test fun reviewModeAllowsCorrectionAndNeverAutoSends() {
        voice.start(VoiceMode.DICTATION); utterance()
        assertEquals(VoiceState.REVIEW, voice.state); assertTrue(target.sent.isEmpty()); assertFalse(capture.active)
        voice.correct("Corrected words."); voice.sendTranscript(); output.complete()
        assertEquals(listOf("Corrected words."), target.sent)
    }
    @Test fun unavailableLocalRecognizerPausesWithRecoverableAudioAndNoAdmission() {
        unavailableRecognizer = true
        voice.start(VoiceMode.DICTATION); utterance()
        assertEquals(VoiceState.PAUSED, voice.state)
        assertTrue(voice.canRetryTranscription)
        assertTrue(voice.notice!!.contains("local weights"))
        assertTrue(target.sent.isEmpty()); assertEquals(0, transcriptions)
        unavailableRecognizer = false
        voice.retryTranscription()
        await { voice.state == VoiceState.REVIEW }
        assertEquals(recognized, voice.transcript)
        assertTrue(target.sent.isEmpty()); assertEquals(1, transcriptions)
    }
    @Test fun interruptionDiscardsLatePlaybackWithoutCancelingAgentOrResubmitting() {
        voice.start(); utterance(); output.complete()
        target.value = VoiceRun("run-1", true, "First sentence. Next")
        await { output.said.size == 2 }
        val late = output.completion!!
        voice.stopSpeaking(); late()
        target.value = VoiceRun("run-1", false, "First sentence. Next sentence.", final = true)
        await { voice.state == VoiceState.LISTENING }
        assertEquals(2, output.said.size); assertEquals(1, target.sent.size); assertEquals(0, target.canceled)
        voice.cancelWork(); assertEquals(1, target.canceled)
    }
    @Test fun spokenInterruptionQueuesOneFollowupUntilPriorRunSettles() {
        voice.start(); utterance(); output.complete()
        target.value = VoiceRun("run-1", true, "First sentence. More")
        await { voice.state == VoiceState.SPEAKING }
        recognized = "What about the battery?"; utterance()
        assertEquals(1, target.sent.size); assertEquals(recognized, voice.queuedText)
        target.value = VoiceRun("run-1", false, "First sentence. More detail.", final = true)
        await { output.said.last() == "I'll ask Hermes." && voice.state == VoiceState.SPEAKING }
        output.complete(); assertEquals(2, target.sent.size); assertTrue(target.sent.last().endsWith(recognized)); assertTrue(target.sent.last().contains("playback was interrupted"))
    }
    @Test fun selectingAnotherChatPausesAndDropsNoWorkOntoThatChat() {
        voice.start(); utterance(); val late = output.completion!!
        target.selection = false; await { voice.state == VoiceState.PAUSED }
        late(); assertTrue(target.sent.isEmpty()); assertFalse(capture.active)
    }
    @Test fun approvalPausesMicAndDoesNotSpeakOrAnswerTheApproval() {
        voice.start(); utterance(); output.complete()
        target.value = VoiceRun("run-1", busy = true, attention = true)
        await { voice.state == VoiceState.PAUSED }; assertFalse(capture.active); assertEquals(1, target.sent.size)
        voice.resume(); assertEquals(VoiceState.PAUSED, voice.state); assertFalse(capture.active)
    }
    @Test fun existingTypedRunsAlsoPauseVoiceAndResumeRequiresApprovalOrConnectionRecovery() {
        voice.start()
        target.value = VoiceRun("typed-run", busy = true, attention = true)
        await { voice.state == VoiceState.PAUSED }
        voice.resume(); assertEquals(VoiceState.PAUSED, voice.state); assertFalse(capture.active)
        target.value = VoiceRun("typed-run", busy = true); tick()
        voice.resume(); assertTrue(capture.active)
        target.value = VoiceRun("typed-run", busy = true, connectionLost = true)
        await { voice.state == VoiceState.PAUSED }
        voice.resume(); assertEquals(VoiceState.PAUSED, voice.state); assertFalse(capture.active)
        target.value = VoiceRun("typed-run", final = true); tick()
        voice.resume(); assertEquals(VoiceState.LISTENING, voice.state); assertTrue(capture.active)
        assertTrue(target.sent.isEmpty()); assertTrue(output.said.isEmpty())
    }
    @Test fun transcriptionFailureStopsMicAndKeepsHermesUntouched() {
        failure = true; voice.start(); utterance()
        await { voice.state == VoiceState.PAUSED }; assertFalse(capture.active); assertTrue(target.sent.isEmpty())
        assertEquals("Speech offline", voice.notice)
    }
    @Test fun boundedEchoGuardPreventsPlaybackBecomingAnotherRun() {
        voice.start(); utterance(); output.complete()
        target.value = VoiceRun("run-1", true, "Here are the wiring instructions. More")
        await { voice.state == VoiceState.SPEAKING }
        recognized = "Here are the wiring instructions"; utterance()
        assertEquals(1, target.sent.size); assertTrue(voice.queuedText.isBlank())
    }
    @Test fun resumeDoesNotReplayTheExistingRun() {
        voice.start(); utterance(); output.complete(); voice.pause(); voice.resume()
        target.value = VoiceRun("run-1", false, "Previously generated answer.", final = true)
        await { voice.state == VoiceState.LISTENING }
        assertEquals(listOf("I'll ask Hermes."), output.said); assertEquals(1, target.sent.size)
    }
    @Test fun connectionLossPausesMicWithoutResubmittingTheAdmittedRun() {
        voice.start(); utterance(); output.complete()
        target.value = VoiceRun("run-1", busy = true, connectionLost = true)
        await { voice.state == VoiceState.PAUSED }
        assertFalse(capture.active); assertEquals(1, target.sent.size)
    }
    @Test fun restoredUnsentWordsRequireReviewAndClearOnlyAfterAdmission() {
        val persisted = CopyOnWriteArrayList<String>()
        val restored = VoiceSession("chat", "profile", scope, target, VoiceConfiguration(concise = false), output, capture,
            onTranscript = persisted::add, initialTranscript = "Unsent words.")
        try {
            restored.start(); assertEquals(VoiceState.REVIEW, restored.state)
            assertFalse(capture.active); assertTrue(target.sent.isEmpty())
            restored.sendTranscript(); assertTrue(persisted.isEmpty()); assertTrue(target.sent.isEmpty())
            output.complete(); await { persisted.lastOrNull() == "" }
            assertEquals(listOf("Unsent words."), target.sent)
        } finally { restored.close() }
    }
    @Test fun transcriptionRetryReusesCapturedAudioWithoutStartingInference() {
        failure = true; voice.start(); utterance(); assertTrue(voice.canRetryTranscription)
        failure = false; voice.retryTranscription()
        await { voice.state == VoiceState.SPEAKING }
        assertEquals(2, transcriptions); assertTrue(target.sent.isEmpty()); assertFalse(voice.canRetryTranscription)
        output.complete(); assertEquals(1, target.sent.size)
    }
    @Test fun dictationCompletionRequiresRecordingAnotherTurnInsteadOfResendingOldWords() {
        voice.start(VoiceMode.DICTATION); utterance(); voice.sendTranscript(); output.complete()
        target.value = VoiceRun("run-1", false, "Answer.", final = true)
        await { output.said.size == 2 }; output.complete()
        await { voice.state == VoiceState.READY }
        voice.sendTranscript(); assertEquals(1, target.sent.size); assertTrue(voice.transcript.isBlank())
    }
    @Test fun repeatedSendTapsCannotQueueMoreThanOneAdmissionPreamble() {
        voice.start(VoiceMode.DICTATION); utterance()
        voice.sendTranscript(); voice.sendTranscript(); voice.sendTranscript()
        assertEquals(1, output.said.size); output.complete(); assertEquals(1, target.sent.size)
    }
    @Test fun wordsStayDurableUntilHermesAcknowledgesAdmission() {
        val persisted = CopyOnWriteArrayList<String>()
        target.acknowledge = false
        val restored = VoiceSession("chat", "profile", scope, target, VoiceConfiguration(concise = false), output, capture,
            onTranscript = persisted::add, initialTranscript = "Unsent words.")
        try {
            restored.start(); restored.sendTranscript(); output.complete()
            assertTrue(persisted.isEmpty())
            target.value = target.value.copy(admitted = true)
            await { persisted.lastOrNull() == "" }; assertEquals(1, target.sent.size)
        } finally { restored.close() }
    }
    @Test fun restartWithKnownPendingRunRecoversWithoutReplayingSpeechOrSubmittingAgain() {
        target.value = VoiceRun("run-old", busy = true, admitted = false)
        val cleared = CopyOnWriteArrayList<String>()
        val recovered = VoiceSession("chat", "profile", scope, target, VoiceConfiguration(concise = false), output, capture,
            onTranscript = cleared::add, initialTranscript = "Previously submitted words.", priorRequestId = "run-old")
        try {
            recovered.start(); tick(); assertEquals(VoiceState.WORKING, recovered.state)
            assertTrue(target.sent.isEmpty()); assertTrue(output.said.isEmpty())
            target.value = VoiceRun("run-old", busy = false, text = "Old answer.", final = true, admitted = true)
            await { recovered.state == VoiceState.LISTENING }
            assertEquals("", cleared.last()); assertTrue(target.sent.isEmpty()); assertTrue(output.said.isEmpty())
        } finally { recovered.close() }
    }
    @Test fun pauseAndResumeKeepUnsentReviewWords() {
        voice.start(VoiceMode.DICTATION); utterance(); voice.pause(); voice.resume()
        assertEquals(VoiceState.REVIEW, voice.state); assertEquals(recognized, voice.transcript)
        assertTrue(target.sent.isEmpty())
    }
    @Test fun pushToTalkOnlyOpensMicWhileHeldAndSendsOnRelease() {
        voice.start(VoiceMode.PUSH_TO_TALK)
        assertFalse(capture.active); assertEquals(VoiceState.READY, voice.state)
        voice.beginPushToTalk(); assertTrue(capture.active)
        repeat(25) { voice.frame(ShortArray(320) { 2500 }, 320) }
        repeat(100) { voice.frame(ShortArray(320), 320) }
        assertEquals(0, transcriptions)
        voice.endPushToTalk(); await { voice.state == VoiceState.SPEAKING }
        assertFalse(capture.active); output.complete(); assertEquals(1, target.sent.size)
        assertFalse(capture.active)
    }
    @Test fun releasingPushToTalkAfterSilenceClosesTheMicEvenDuringAnExistingRun() {
        target.value = VoiceRun("other", busy = true)
        voice.start(VoiceMode.PUSH_TO_TALK); voice.beginPushToTalk(); assertTrue(capture.active)
        voice.endPushToTalk(); assertFalse(capture.active); assertEquals(0, transcriptions); assertTrue(target.sent.isEmpty())
    }
    @Test fun closingSessionReleasesCaptureAndIgnoresLateCallbacks() {
        voice.start(); utterance(); val late = output.completion!!
        voice.close(); late(); assertEquals(VoiceState.STOPPED, voice.state); assertFalse(capture.active); assertTrue(target.sent.isEmpty())
    }
}
