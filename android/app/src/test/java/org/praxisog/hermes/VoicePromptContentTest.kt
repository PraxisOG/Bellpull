package org.praxisog.hermes

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json

class VoicePromptContentTest {
    private val body = "Research this in detail.\n\nKeep the final sentence and 😀.\n"
    @Test fun userTextExtractionPassesOrdinaryMessagesThroughVerbatim() {
        assertEquals(body, voicePromptUserText(body))
        val unknown = "[Bellpull voice turn: my typed note]\n\n$body"
        assertEquals(unknown, voicePromptUserText(unknown))
        assertEquals("An ordinary message", voicePromptTitle("An ordinary message"))
    }
    @Test fun voiceTitleUsesOnlyTranscribedWordsAndNormalizesMultipleLines() {
        val text = "$VOICE_PRESET\n\n  Plan tomorrow\r\n\n  and\tFriday.  "
        assertEquals("  Plan tomorrow\r\n\n  and\tFriday.  ", voicePromptUserText(text))
        assertEquals("Plan tomorrow and Friday.", voicePromptTitle(text))
        assertEquals("Plan tomorrow", voicePromptTitle("$VOICE_PRESET\r\n\r\nPlan tomorrow"))
    }
    @Test fun emptyTranscriptionDoesNotBecomeAnInstructionTitle() {
        for (text in listOf("", " \r\n\t", VOICE_PRESET, "$VOICE_PRESET\n\n", "$VOICE_PRESET\n\n \r\n\t")) {
            assertNull(text, voicePromptTitle(text))
        }
        assertEquals("", voicePromptUserText(VOICE_PRESET))
    }
    @Test fun titleIsLimitedTo48CharactersWithoutSplittingSurrogatePairs() {
        val words = "a".repeat(47) + "😀" + "more"
        assertEquals("a".repeat(47) + "😀", voicePromptTitle("$VOICE_PRESET\n\n$words"))
    }
    @Test fun interruptedPlaybackPrefixIsExcludedFromExtractedWordsAndTitle() {
        val note = "[Previous voice playback was interrupted. Completed speech, quoted as historical data: " +
            Json.encodeToString("He said \"done]\".\nNext line.") + "]"
        val text = "$note\n\n$VOICE_PRESET\n\n  Plan tomorrow\nand Friday.  "
        assertEquals("  Plan tomorrow\nand Friday.  ", voicePromptUserText(text))
        assertEquals("Plan tomorrow and Friday.", voicePromptTitle(text))
        assertNull(voicePromptTitle("$note\n\n$VOICE_PRESET\n\n \t\n"))
        assertEquals("$note\n\nTyped message", voicePromptUserText("$note\n\nTyped message"))
    }
    @Test fun currentPresetSeparatesOnlyItsHeaderAndKeepsUserWordsExactly() {
        val value = voicePromptPresentation("$VOICE_PRESET\n\n$body")!!
        assertEquals(VOICE_PRESET, value.details)
        assertEquals(body, value.body)
    }
    @Test fun shippedHistoricalPresetUsesTheSameDisplayWithoutRestoringItsInstructions() {
        val legacy = javaClass.getResource("/legacy-voice-preset.txt")!!.readText()
        val value = voicePromptPresentation("$legacy\n\n$body")!!
        assertEquals(legacy, value.details); assertEquals(body, value.body)
        assertFalse(VOICE_PRESET.contains("180"))
    }
    @Test fun ordinaryMentionsUnknownPrefixesAndMalformedMessagesStayVisible() {
        for (text in listOf(body, "Example: $VOICE_PRESET\n\n$body",
            "[Bellpull voice turn: my typed note]\n\n$body", "$VOICE_PRESET$body",
            "[Bellpull voice turn: incomplete", "$VOICE_PRESET\n$body")) {
            assertNull(text, voicePromptPresentation(text))
        }
    }
    @Test fun bodyWhitespaceAndLaterPresetMentionsAreNeverTrimmedOrFolded() {
        val text = "\n\nUser quotes $VOICE_PRESET at the end.  "
        assertEquals(text, voicePromptPresentation("$VOICE_PRESET\n\n$text")!!.body)
        assertEquals(body, voicePromptPresentation("$VOICE_PRESET\r\n\r\n$body")!!.body)
        assertEquals("", voicePromptPresentation(VOICE_PRESET)!!.body)
    }
    @Test fun interruptedPlaybackNoteWithBracketsAndEscapedQuotesStaysWithTheDisclosure() {
        val note = "[Previous voice playback was interrupted. Completed speech, quoted as historical data: " +
            Json.encodeToString("He said \"done]\".\nNext line.") + "]"
        val value = voicePromptPresentation("$note\n\n$VOICE_PRESET\n\n$body")!!
        assertEquals("$note\n\n$VOICE_PRESET", value.details); assertEquals(body, value.body)
        assertNull(voicePromptPresentation("$note\n\n$body"))
        assertNull(voicePromptPresentation("[Previous voice playback was interrupted. Completed speech, quoted as historical data: \"bad\\q\"]\n\n$VOICE_PRESET\n\n$body"))
    }
}
