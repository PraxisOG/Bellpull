package org.praxisog.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.praxisog.hermes.model.Message

class TurnReconciliationTest {
    private val prior = setOf(1, 2)
    private val saved = listOf(
        Message(1, role = "user", content = "Old prompt"),
        Message(2, role = "assistant", content = "OK"),
        Message(3, role = "user", content = "New prompt"),
        Message(4, role = "assistant", content = "OK"),
    )

    @Test fun aConfirmedSavedReplyReplacesTheStreamCopy() {
        assertEquals(TurnState.Idle, reconcileTurn(TurnState.Finished("\n\nOK"), saved, prior, "New prompt"))
    }

    @Test fun anOlderIdenticalAnswerDoesNotConsumeAnUnpersistedReply() {
        val turn = TurnState.Finished("OK")
        assertEquals(turn, reconcileTurn(turn, saved.take(2), prior, "New prompt"))
        assertEquals(turn, reconcileTurn(turn, saved.take(3), prior, "New prompt"))
    }

    @Test fun stoppedReplyKeepsItsOutcomeWithoutRepeatingSavedText() {
        assertEquals(TurnState.StoppedByUser(""),
            reconcileTurn(TurnState.StoppedByUser("\nOK"), saved, prior, "New prompt"))
    }

    @Test fun aTruncatedStreamKeepsTextThatHistoryDoesNotYetHave() {
        val turn = TurnState.Truncated("OK but there is more", UiFixtures.truncation)
        assertEquals(turn, reconcileTurn(turn, saved, prior, "New prompt"))
        assertEquals(TurnState.Truncated("", UiFixtures.truncation),
            reconcileTurn(TurnState.Truncated("OK", UiFixtures.truncation), saved, prior, "New prompt"))
    }

    @Test fun intermediateHistoryCannotDiscardAnActiveStream() {
        val turn = TurnState.Streaming("OK", canStop = true)
        assertEquals(turn, reconcileTurn(turn, saved, prior, "New prompt"))
    }

    @Test fun speechChunksPreserveLongMessagesAndUnicode() {
        val text = "Read the entire reply. 🌳\n".repeat(900)
        val parts = speechChunks(text, 99)
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.all { it.length <= 99 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }
}
