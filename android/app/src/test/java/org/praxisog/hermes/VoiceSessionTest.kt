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
        val prefetched = CopyOnWriteArrayList<String>()
        var completion: (() -> Unit)? = null
        var failure: ((String) -> Unit)? = null
        var chimes = 0
        var preparations = 0
        override fun chime() { chimes++ }
        override fun prepare(onReady: (String) -> Unit, onError: (String) -> Unit) { preparations++; failure = onError; onReady("offline-fixture") }
        override fun speak(text: String, started: () -> Unit, complete: () -> Unit) { said += text; completion = complete; started() }
        override fun prefetch(text: String) { prefetched += text }
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
    private var recognizers = 0
    @Volatile private var recognitionWait: java.util.concurrent.CountDownLatch? = null
    private val voice = VoiceSession("chat", "profile", scope, target, VoiceConfiguration(concise = false), output, capture,
        transcriber = {
            check(!unavailableRecognizer) { "Selected local weights are missing." }
            recognizers++
            object : VoiceTranscriber {
            override fun transcribe(wav: ByteArray): Result<String> { transcriptions++; recognitionWait?.await(3, java.util.concurrent.TimeUnit.SECONDS); return if (failure) Result.failure(IllegalStateException("Speech offline")) else Result.success(recognized) }
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
    @Test fun repeatedConnectionBlipsAndExplicitResumesReusePreparedOutput() {
        voice.start()
        repeat(5) {
            target.value = VoiceRun(connectionLost = true)
            await { voice.state == VoiceState.PAUSED }
            target.value = VoiceRun()
            tick()
            voice.resume()
            assertEquals(VoiceState.LISTENING, voice.state)
        }
        assertEquals(1, output.preparations)
        assertTrue(target.sent.isEmpty())
    }
    @Test fun confirmedSpeechUsesChatStopWhilePlaybackStopsBeforeTranscriptionFinishes() {
        voice.start(); assertEquals(1, output.chimes)
        utterance(); output.complete()
        target.value = VoiceRun("run-1", true, "A reply is playing. More")
        await { voice.state == VoiceState.SPEAKING }
        val late = output.completion!!
        repeat(10) { voice.frame(ShortArray(320) { 2500 }, 320) }
        assertNull(output.completion); assertEquals(0, target.canceled)
        late(); assertEquals(VoiceState.RECORDING, voice.state)
        recognized = "New question instead."
        repeat(15) { voice.frame(ShortArray(320) { 2500 }, 320) }
        repeat(90) { voice.frame(ShortArray(320), 320) }
        await { target.canceled == 1 }
        assertEquals(1, target.sent.size); assertEquals(recognized, voice.queuedText)
        target.value = VoiceRun("run-1", false, final = true, stopRequested = true)
        await { output.said.last() == "Working." }
        output.complete(); assertEquals(2, target.sent.size)
        assertTrue(target.sent.last().endsWith(recognized))
    }
    @Test fun interruptionBeforeAdmissionWaitsForTheSameRunToBecomeStoppable() {
        target.acknowledge = false
        voice.start(); utterance(); output.complete()
        recognized = "Change of plan."
        utterance(); assertEquals(0, target.canceled); assertEquals(1, target.sent.size)
        target.value = target.value.copy(admitted = true)
        await { target.canceled == 1 }
        assertEquals(1, target.sent.size)
        target.value = target.value.copy(busy = false, final = true)
        await { output.said.size == 2 }
        output.complete(); assertEquals(2, target.sent.size)
    }
    @Test fun speechDuringSilentWorkSuppressesLaterPlaybackBeforeRecognitionConfirmsStop() {
        voice.start(); utterance(); output.complete()
        repeat(10) { voice.frame(ShortArray(320) { 2500 }, 320) }
        target.value = target.value.copy(text = "The old answer is arriving. More")
        tick()
        assertEquals(VoiceState.RECORDING, voice.state)
        assertEquals(listOf("Working."), output.said); assertEquals(0, target.canceled)
        recognized = "Actually, change the question."
        repeat(15) { voice.frame(ShortArray(320) { 2500 }, 320) }
        repeat(90) { voice.frame(ShortArray(320), 320) }
        await { target.canceled == 1 }
        assertEquals(recognized, voice.queuedText); assertEquals(1, target.sent.size)
    }
    @Test fun completedRunAwaitingSavedHistoryIsNotStoppedAndNewWordsWaitForReconciliation() {
        voice.start(); utterance(); output.complete()
        target.value = VoiceRun("run-1", busy = true, final = true, executing = false)
        recognized = "After that, a new question."
        utterance()
        assertEquals(0, target.canceled); assertEquals(1, target.sent.size)
        assertEquals(recognized, voice.queuedText)
        target.value = target.value.copy(busy = false)
        await { output.said.size == 2 }
        output.complete(); assertEquals(2, target.sent.size); assertEquals(0, target.canceled)
    }
    @Test fun failedStopKeepsTheNewWordsForReviewAndDoesNotSubmitThem() {
        voice.start(); utterance(); output.complete()
        recognized = "Keep these new words."
        utterance()
        target.value = target.value.copy(stopError = "Stop was not confirmed.")
        await { voice.state == VoiceState.PAUSED }
        assertEquals(recognized, voice.transcript); assertEquals(1, target.sent.size)
        target.value = target.value.copy(stopError = null)
        voice.resume(); assertEquals(VoiceState.REVIEW, voice.state)
        assertEquals(recognized, voice.transcript)
    }
    @Test fun aStopThatNeverSettlesPausesInsteadOfLoopingOrAdmittingAgain() {
        target.value = VoiceRun("old", busy = true)
        val waiting = VoiceSession("chat", "profile", scope, target, VoiceConfiguration(), output, capture,
            initialTranscript = "New words.", stopWaitMs = 250)
        try {
            waiting.start(); waiting.sendTranscript()
            await { waiting.state == VoiceState.PAUSED }
            assertTrue(waiting.notice!!.startsWith("Stop has not settled"))
            assertEquals("New words.", waiting.transcript)
            assertEquals(1, target.canceled); assertTrue(target.sent.isEmpty()); assertFalse(capture.active)
        } finally { waiting.close() }
    }
    @Test fun quickHeldDictationReturnsWordsToDraftWithoutSubmittingAnything() {
        val drafts = CopyOnWriteArrayList<String>()
        val persisted = CopyOnWriteArrayList<String>()
        val dictation = VoiceSession("chat", "profile", scope, target, VoiceConfiguration(), output, capture,
            transcriber = { object : VoiceTranscriber {
                override fun transcribe(wav: ByteArray) = Result.success("Dictated words.")
                override fun close() {}
            } }, onDictation = drafts::add, onTranscript = persisted::add)
        try {
            dictation.start(VoiceMode.PUSH_TO_TALK); assertFalse(capture.active)
            dictation.beginPushToTalk(toDraft = true); assertTrue(capture.active)
            repeat(25) { dictation.frame(ShortArray(320) { 2500 }, 320) }
            repeat(100) { dictation.frame(ShortArray(320), 320) }
            assertTrue(drafts.isEmpty())
            dictation.endPushToTalk()
            await { dictation.state == VoiceState.STOPPED }
            assertEquals(listOf("Dictated words."), drafts)
            assertEquals("", persisted.last()); assertTrue(target.sent.isEmpty()); assertTrue(output.said.isEmpty())
            assertFalse(capture.active)
        } finally { dictation.close() }
    }
    @Test fun releaseBeforeOutputReadinessCannotOpenTheMicrophoneLater() {
        var prepared: (() -> Unit)? = null
        val delayed = object : VoiceOutput {
            override fun prepare(onReady: (String) -> Unit, onError: (String) -> Unit) { prepared = { onReady("fixture") } }
            override fun speak(text: String, started: () -> Unit, complete: () -> Unit) = Unit
            override fun stop() = Unit
            override fun close() = Unit
        }
        val dictation = VoiceSession("chat", "profile", scope, target, VoiceConfiguration(), delayed, capture, onDictation = {})
        dictation.start(VoiceMode.PUSH_TO_TALK); dictation.beginPushToTalk(toDraft = true)
        assertFalse(capture.active)
        dictation.endPushToTalk(canceled = true); prepared!!()
        assertEquals(VoiceState.STOPPED, dictation.state); assertFalse(capture.active)
        assertTrue(target.sent.isEmpty())
    }
    @Test fun liveActivitiesAreAnnouncedOnceAndAheadOfQueuedAnswerSentences() {
        voice.start(); utterance(); output.complete()
        target.value = VoiceRun("run-1", true, "First sentence. Second sentence. More")
        await { output.said.size == 2 }
        val activity = VoiceActivity("web:1", "Web search. Checking the forecast.")
        target.value = target.value.copy(activities = listOf(activity))
        tick(); output.complete()
        await { output.said.size == 3 }
        assertEquals(activity.text, output.said.last())
        target.value = target.value.copy(text = "First sentence. Second sentence. More words")
        tick(); output.complete()
        assertEquals("Second sentence.", output.said.last())
        assertEquals(1, output.said.count { it == activity.text })
    }
    @Test fun actualPreamblePlaybackPrecedesOneAdmissionAndAnswerStreamsBeforeCompletion() {
        voice.start(); utterance()
        assertEquals(listOf("Working."), output.said)
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
    @Test fun finishedReplyKeepsPreparingOneSentenceAheadWithoutMoreServerUpdates() {
        voice.start(); utterance(); output.complete()
        target.value = VoiceRun("run-1", false, "First sentence. Second sentence. Third sentence. Fourth sentence.", final = true)
        await { output.prefetched.contains("Second sentence.") }
        output.complete()
        assertEquals("Second sentence.", output.said.last())
        assertEquals("Third sentence.", output.prefetched.last())
        output.complete()
        assertEquals("Third sentence.", output.said.last())
        assertEquals("Fourth sentence.", output.prefetched.last())
        output.complete(); output.complete()
        await { voice.state == VoiceState.LISTENING }
        assertEquals(5, output.said.size) // Working plus all four sentences, once each.
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
    @Test fun recognizedInterruptionStopsTheRunAndSendsOneReplacementAfterStopSettles() {
        voice.start(); utterance(); output.complete()
        target.value = VoiceRun("run-1", true, "First sentence. More")
        await { voice.state == VoiceState.SPEAKING }
        recognized = "What about the battery?"; utterance()
        assertEquals(1, target.sent.size); assertEquals(recognized, voice.queuedText); assertEquals(1, target.canceled)
        target.value = VoiceRun("run-1", false, "First sentence. More detail.", final = true)
        await { output.said.last() == "Working." && voice.state == VoiceState.SPEAKING }
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
        assertEquals(1, target.sent.size); assertTrue(voice.queuedText.isBlank()); assertEquals(0, target.canceled)
    }
    @Test fun resumeRecoversReplyGeneratedWhilePausedWithoutResubmission() {
        voice.start(); utterance(); output.complete(); voice.pause()
        target.value = VoiceRun("run-1", false, "Previously generated answer.", final = true)
        tick(); assertEquals(listOf("Working."), output.said)
        voice.resume(); await { output.said.size == 2 }
        output.complete(); await { voice.state == VoiceState.LISTENING }
        assertEquals(1, target.sent.size)
    }
    @Test fun rejectedNoiseRecoversPartialPhraseAndCompletedBackendRemainder() {
        voice.start(); utterance(); output.complete()
        target.value = VoiceRun("run-1", true, "Completed first. Interrupted second. Third")
        await { output.said.size == 2 }; output.complete()
        await { output.said.size == 3 }
        recognized = ""; utterance()
        await { output.said.size == 4 }
        assertEquals("Interrupted second.", output.said.last())
        target.value = target.value.copy(busy = false, text = "Completed first. Interrupted second. Third ends.", final = true)
        output.complete(); await { output.said.last() == "Third ends." }
        output.complete(); await { voice.state == VoiceState.LISTENING }
        assertEquals(1, output.said.count { it == "Completed first." })
        assertEquals(1, target.sent.size); assertEquals(0, target.canceled)
    }
    @Test fun toolBatchesAndReplayedSnapshotsNeverRepeatPublicProse() {
        voice.start(); utterance(); output.complete()
        val preamble = "Checking the public forecast."
        target.value = target.value.copy(text = "$preamble ", activities = listOf(
            VoiceActivity("tool:one", "Web search.", VoiceActivityKind.WEB_SEARCH),
            VoiceActivity("tool:two", "Tool call. Read file.")))
        await { output.said.size == 2 }
        repeat(3) { output.complete() }
        assertEquals(1, output.said.count { it == preamble })
        target.value = target.value.copy(activities = target.value.activities + VoiceActivity("tool:three", "Tool call. Terminal."))
        await { output.said.size == 5 }; output.complete()
        target.value = target.value.copy(busy = false, final = true, text = "$preamble $preamble")
        await { output.said.size == 6 }; output.complete()
        assertEquals(2, output.said.count { it == preamble }) // A later genuine repetition stays eligible.
        assertEquals(1, target.sent.size)
    }
    @Test fun searchAndOtherToolFlagsAreIndependentInEveryCombination() {
        for (tools in listOf(false, true)) for (search in listOf(false, true)) {
            output.said.clear()
            val session = VoiceSession("chat", "profile", scope, target,
                VoiceConfiguration(concise = false, announcements = VoiceAnnouncements(tools, search, false, false)),
                output, capture, initialTranscript = "A new turn.")
            try {
                target.value = VoiceRun()
                session.start(); session.sendTranscript()
                target.value = target.value.copy(busy = false, final = true, text = "Answer.", activities = listOf(
                    VoiceActivity("search", "Web search.", VoiceActivityKind.WEB_SEARCH),
                    VoiceActivity("file", "Tool call. Read file.", VoiceActivityKind.TOOL)))
                await { output.said.isNotEmpty() }
                while (output.completion != null) output.complete()
                assertEquals(if (tools) 1 else 0, output.said.count { it == "Tool call. Read file." })
                assertEquals(if (search) 1 else 0, output.said.count { it == "Web search." })
                assertEquals(1, output.said.count { it == "Answer." })
            } finally { session.close() }
        }
    }
    @Test fun backendCompletionDuringPendingEchoRecognitionRemainsRecoverable() {
        voice.start(); utterance(); output.complete()
        target.value = VoiceRun("run-1", true, "First sentence. More")
        await { output.said.size == 2 }
        recognized = "First sentence"; recognitionWait = java.util.concurrent.CountDownLatch(1)
        repeat(25) { voice.frame(ShortArray(320) { 2500 }, 320) }
        repeat(90) { voice.frame(ShortArray(320), 320) }
        await { voice.state == VoiceState.TRANSCRIBING }
        target.value = target.value.copy(busy = false, text = "First sentence. More detail.", final = true)
        tick(); assertEquals(2, output.said.size)
        recognitionWait!!.countDown()
        await { output.said.size == 3 }; output.complete()
        await { output.said.size == 4 }; output.complete()
        await { voice.state == VoiceState.LISTENING }
        assertEquals(1, target.sent.size); assertEquals(0, target.canceled)
    }
    @Test fun delayedOutputReadinessCannotReopenCaptureAfterPause() {
        var ready: ((String) -> Unit)? = null
        val delayed = VoiceSession("chat", "profile", scope, target, VoiceConfiguration(), object : VoiceOutput {
            override fun prepare(onReady: (String) -> Unit, onError: (String) -> Unit) { ready = onReady }
            override fun speak(text: String, started: () -> Unit, complete: () -> Unit) {}
            override fun stop() {}
            override fun close() {}
        }, capture)
        try {
            delayed.start(); val old = ready!!
            delayed.pause(); old("old"); assertEquals(VoiceState.PAUSED, delayed.state); assertFalse(capture.active)
            delayed.resume(); old("stale"); assertFalse(capture.active)
            ready!!("new"); assertTrue(capture.active)
        } finally { delayed.close() }
    }
    @Test fun transientFocusPreservesSpeechAndGainNeverResumesAnExplicitPause() {
        voice.start(); utterance(); output.complete()
        target.value = VoiceRun("run-1", true, "First phrase. Next")
        await { output.said.size == 2 }
        val late = output.completion!!
        voice.suspendForAudio(); assertFalse(capture.active); late()
        target.value = target.value.copy(busy = false, text = "First phrase. Next phrase.", final = true)
        tick(); assertEquals(2, output.said.size)
        voice.recoverAudio(); await { output.said.size == 3 }
        output.complete(); await { output.said.size == 4 }
        voice.suspendForAudio(); voice.pause(); voice.recoverAudio()
        assertEquals(VoiceState.PAUSED, voice.state); assertFalse(capture.active)
        assertEquals(1, target.sent.size); assertEquals(0, target.canceled)
    }
    @Test fun answersLongerThan180WordsAreSpokenToTheLastWord() {
        voice.start(); utterance(); output.complete()
        val answer = (1..350).joinToString(" ") { "word$it" } + ". Final ending."
        target.value = VoiceRun("run-1", false, answer, final = true)
        await { output.said.size > 1 }
        while (output.completion != null) output.complete()
        await { voice.state == VoiceState.LISTENING }
        assertEquals(answer, output.said.drop(1).joinToString(" "))
        assertEquals(1, target.sent.size)
    }
    @Test fun allAnnouncementsOffStillAdmitsExactlyOnceAndSpeaksAnswers() {
        val silent = VoiceSession("chat", "profile", scope, target,
            VoiceConfiguration(concise = true, announcements = VoiceAnnouncements(false, false, false, false)),
            output, capture, initialTranscript = "Tell me a detailed research answer.")
        try {
            silent.start(); silent.sendTranscript(); silent.sendTranscript()
            assertEquals(1, target.sent.size); assertTrue(output.said.isEmpty())
            target.value = target.value.copy(text = "The full answer.", final = true, busy = false,
                activities = listOf(VoiceActivity("a", "Thinking.", VoiceActivityKind.THINKING),
                    VoiceActivity("b", "Web search.", VoiceActivityKind.WEB_SEARCH)))
            await { output.said.size == 1 }; output.complete()
            assertEquals(listOf("The full answer."), output.said)
        } finally { silent.close() }
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
    @Test fun repeatedRetryCannotStartTwoRecognizersForTheSameRecording() {
        failure = true; voice.start(); utterance()
        assertTrue(voice.canRetryTranscription)
        failure = false
        val release = java.util.concurrent.CountDownLatch(1)
        recognitionWait = release
        try {
            voice.retryTranscription()
            await { transcriptions == 2 }
            voice.retryTranscription()
            assertEquals(2, recognizers)
        } finally { release.countDown() }
        await { voice.state == VoiceState.SPEAKING }
        output.complete()
        assertEquals(1, target.sent.size)
    }
    @Test fun unavailableAudioExplainsWhyTranscriptionRetryCannotStart() {
        var allowAudio = true
        var attempts = 0
        val retained = mutableListOf<ByteArray>()
        val local = VoiceSession("chat", "profile", scope, target, VoiceConfiguration(), output, capture,
            beforeResume = { allowAudio }, transcriber = { object : VoiceTranscriber {
                override fun transcribe(wav: ByteArray): Result<String> {
                    retained.add(wav.copyOf()); attempts++
                    return if (attempts == 1) Result.failure(IllegalStateException("Recognition unavailable")) else Result.success("Recovered words")
                }
                override fun close() {}
            } })
        try {
            local.start(VoiceMode.DICTATION)
            repeat(25) { local.frame(ShortArray(320) { 2500 }, 320) }
            repeat(90) { local.frame(ShortArray(320), 320) }
            await { local.state == VoiceState.PAUSED }
            assertEquals(1, attempts)
            allowAudio = false
            local.retryTranscription()
            assertTrue(local.notice.orEmpty().contains("Audio is still unavailable"))
            assertTrue(local.canRetryTranscription)
            assertEquals(1, attempts)
            allowAudio = true
            local.retryTranscription()
            await { local.state == VoiceState.REVIEW }
            assertTrue(retained[0].contentEquals(retained[1]))
            assertTrue(target.sent.isEmpty())
        } finally { local.close() }
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
