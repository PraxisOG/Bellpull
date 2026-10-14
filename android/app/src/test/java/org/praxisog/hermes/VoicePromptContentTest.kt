package org.praxisog.hermes

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json

class VoicePromptContentTest {
    private val body = "Research this in detail.\n\nKeep the final sentence and 😀.\n"
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
