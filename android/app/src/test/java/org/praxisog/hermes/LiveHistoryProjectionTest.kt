package org.praxisog.hermes

import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.model.*

class LiveHistoryProjectionTest {
    private val record = RunSubmission("request", "s", "Check it", 1.0, 1, setOf(1, 2), runId = "run")
    private val messages = listOf(Message(1, role = "user", content = "Old prompt"),
        Message(2, role = "assistant", content = "Saved text."), Message(3, role = "user", content = "Check it"),
        Message(4, role = "assistant", content = "Saved text."))
    @Test fun reconnectReplayDoesNotRepeatSavedPrefixAndKeepsNewSuffix() {
        val turn = TurnState.Streaming("Saved text.\n\nNew suffix.", canStop = true,
            parts = listOf(LivePart.Prose("a", "Saved text."), LivePart.Prose("b", "New suffix.")))
        val result = projectLiveHistory(turn, messages, record)
        assertEquals(4, result.appendTo)
        val projected = result.turn as TurnState.Streaming
        assertEquals("New suffix.", projected.text)
        assertEquals(listOf(LivePart.Prose("b", "New suffix.")), projected.parts)
        assertTrue(projected.canStop)
        assertEquals("Saved text.\n\nNew suffix.", turn.text)
    }
    @Test fun replayStillCatchingUpDoesNotReplaceHistoryOrEndRun() {
        val turn = TurnState.Streaming("Saved", canStop = true)
        val result = projectLiveHistory(turn, messages, record)
        assertEquals("", (result.turn as TurnState.Streaming).text)
        assertTrue(result.turn.canStop)
    }
    @Test fun separateOrAmbiguousTurnsAreNeverCoalesced() {
        val turn = TurnState.Streaming("Saved text.")
        for (history in listOf(messages.take(2), messages + Message(5, role = "user", content = "Different"),
            messages + Message(5, role = "user", content = "Check it"))) {
            assertEquals(LiveHistoryProjection(turn), projectLiveHistory(turn, history, record))
        }
        assertEquals(LiveHistoryProjection(turn), projectLiveHistory(turn, messages, record.copy(requestId = "observed:run")))
    }
    @Test fun notificationReplayNeedsServerTimeAndMatchingText() {
        val observed = record.copy(requestId = "observed:run", text = "", serverCreatedAt = 10.0)
        val rows = messages.map { it.copy(timestamp = if (it.id >= 3) 11.0 else 9.0) }
        assertEquals(4, projectLiveHistory(TurnState.Streaming("Saved text. New suffix."), rows, observed).appendTo)
        assertNull(projectLiveHistory(TurnState.Streaming("Unrelated reply"), rows, observed).appendTo)
        assertNull(projectLiveHistory(TurnState.Streaming("Saved text."), rows.map { it.copy(timestamp = 9.0) }, observed).appendTo)
    }
    @Test fun completedCompactedPromptDoesNotResurrectASecondReplyOnReopen() {
        val finished = record.copy(status = "completed", completionOutput = "Saved text.", serverCreatedAt = 10.0)
        val compacted = listOf(Message(10, role = "assistant", content = "Saved text.", timestamp = 11.0))
        assertEquals(TurnState.Idle, reconcileDurableTurn(TurnState.Finished("Saved text."), compacted, finished))
        assertEquals(TurnState.Idle, reconcileDurableTurn(TurnState.Finished("Saved text."), compacted,
            finished.copy(historySynced = true, completionOutput = null)))
    }
}
