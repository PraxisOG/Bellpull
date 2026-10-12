package org.praxisog.hermes

import org.junit.Test
import org.junit.Assert.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceSignalTest {
    @Test fun voicePresetRequestsShortAnswersAndReasoningWithoutProviderOptions() {
        assertTrue(VOICE_PRESET.contains("at most one paragraph"))
        assertTrue(VOICE_PRESET.contains("180 words"))
        assertTrue(VOICE_PRESET.contains("reasoning minimal"))
        assertTrue(VOICE_PRESET.contains("what you are doing and why"))
    }
    @Test fun spokenBudgetCapsNonCompliantRepliesAndResetsForTheNextTurn() {
        val budget = VoiceSpeechBudget()
        assertEquals(170, budget.take(List(170) { "word" }.joinToString(" "))!!.split(' ').size)
        assertEquals(10, budget.take(List(80) { "extra" }.joinToString("\n"))!!.split(' ').size)
        assertNull(budget.take("More words."))
        budget.reset(); assertEquals("Next reply.", budget.take("Next reply."))
    }
    @Test fun activityAnnouncementsUseGenuineKindsAndCommentaryWithoutSpeakingThinkingOrArguments() {
        val parts = listOf(LivePart.Prose("a", "private reasoning", true),
            LivePart.Prose("b", "Checking tomorrow's forecast for your walk."),
            LivePart.Tools("c", listOf(LiveTool("search", "web_search", "RAW TOKEN SECRET"), LiveTool("file", "read_file", "PRIVATE PATH"))))
        val activities = voiceActivities(parts)
        assertEquals("Thinking.", activities[0].text)
        assertEquals("Web search. Checking tomorrow's forecast for your walk.", activities[1].text)
        assertTrue(activities[2].text.startsWith("Tool call. Read file."))
        assertFalse(activities.joinToString().contains("private reasoning"))
        assertFalse(activities.joinToString().contains("SECRET"))
        assertFalse(activities.joinToString().contains("PRIVATE PATH"))
    }
    private val loud = ShortArray(320) { if (it % 2 == 0) 2400 else -2400 }
    @Test fun noiseAndBriefClicksDoNotCreateAnUtterance() {
        val gate = VoiceGate()
        repeat(100) { assertNull(gate.accept(ShortArray(320) { 120 }, 320).complete) }
        repeat(4) { gate.accept(loud, 320) }
        repeat(100) { assertNull(gate.accept(ShortArray(320), 320).complete) }
        assertFalse(gate.active)
    }
    @Test fun naturalPauseKeepsOneTurnAndWavFormatIsValid() {
        val gate = VoiceGate(1600)
        repeat(25) { gate.accept(loud, 320) }
        repeat(60) { assertNull(gate.accept(ShortArray(320), 320).complete) }
        repeat(25) { gate.accept(loud, 320) }
        var complete: ByteArray? = null
        repeat(90) { gate.accept(ShortArray(320), 320).complete?.let { complete = it } }
        val wav = complete!!
        assertEquals("RIFF", wav.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        val bytes = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16000, bytes.getInt(24)); assertEquals(wav.size - 44, bytes.getInt(40)); assertFalse(gate.active)
    }
    @Test fun holdTurnSurvivesSilenceUntilExplicitFinish() {
        val gate = VoiceGate()
        repeat(25) { gate.accept(loud, 320, true) }
        repeat(200) { assertNull(gate.accept(ShortArray(320), 320, true).complete) }
        assertNotNull(gate.finish())
    }
    @Test fun sixtySecondsCannotGrowAnUnboundedRecording() {
        val gate = VoiceGate()
        var completed = 0
        repeat(3000) { if (gate.accept(loud, 320).complete != null) completed++ }
        assertEquals(1, completed)
    }
    @Test fun incrementalSentencesNeverRepeatAndHoldPartialWords() {
        val chunks = VoiceSentences()
        assertTrue(chunks.take("Hello").isEmpty())
        assertEquals(listOf("Hello there."), chunks.take("Hello there. More"))
        assertTrue(chunks.take("Hello there. More").isEmpty())
        assertEquals(listOf("More detail."), chunks.take("Hello there. More detail.", true))
        assertTrue(chunks.take("Hello there. More detail.", true).isEmpty())
    }
    @Test fun codeAndLinksAreConvertedToSpeakableText() {
        val chunks = VoiceSentences()
        assertEquals(listOf("See the answer.", "Code is available in the chat."),
            chunks.take("See [the answer](https://private.invalid). ```sh\nsecret-token\n```", true))
    }
    @Test fun finalOutputCanOmitEarlierToolCommentaryWithoutManglingTheAnswer() {
        val chunks = VoiceSentences()
        assertEquals(listOf("I will check."), chunks.take("I will check. The saved answer is"))
        assertEquals(listOf("The saved answer is correct."), chunks.take("The saved answer is correct.", true))
        assertTrue(chunks.take("The saved answer is correct.", true).isEmpty())
    }
    @Test fun completedSentencesRemainConsumedWhenStatusDropsToolCommentary() {
        val chunks = VoiceSentences()
        assertEquals(listOf("I will check.", "The saved answer is correct."), chunks.take("I will check. The saved answer is correct. More"))
        assertEquals(listOf("More detail."), chunks.take("The saved answer is correct. More detail.", true))
    }
    @Test fun echoMustMatchAContiguousPhraseAndShortCommandsRemainPossible() {
        assertTrue(likelyEcho("the wiring is correct", "Here the wiring is correct and ready."))
        assertFalse(likelyEcho("stop", "Stop here.")); assertFalse(likelyEcho("What about the wiring?", "Here the wiring is correct."))
    }
}
