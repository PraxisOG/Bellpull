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

    @Test fun whitespaceAndMarkdownSuffixSurviveAcrossProseAndThinkingParts() {
        val rows = messages.dropLast(1) + Message(4, role = "assistant", content = "Saved\ttext. More\u2003saved.", reasoningContent = "First thought.")
        val turn = TurnState.Streaming("Saved text.\nMore saved.\n\n**New** suffix.", reasoning = "First\nthought. Next thought.", parts = listOf(
            LivePart.Prose("first", "Saved text."), LivePart.Prose("thinking", "First thought. Next thought.", thinking = true),
            LivePart.Prose("last", "More\r\nsaved.\n\n**New** suffix.")))
        val result = projectLiveHistory(turn, rows, record).turn as TurnState.Streaming
        assertEquals("**New** suffix.", result.text)
        assertEquals("Next thought.", result.reasoning)
        assertEquals(listOf(LivePart.Prose("thinking", "Next thought.", thinking = true), LivePart.Prose("last", "**New** suffix.")), result.parts)
    }

    @Test fun mismatchesAndPartialWordsKeepTheLiveText() {
        for (text in listOf("Saved tex", "Saved different.", "Unrelated", "", "  ")) {
            val result = projectLiveHistory(TurnState.Streaming(text), messages, record).turn as TurnState.Streaming
            assertEquals(text, result.text)
        }
    }

    @Test(timeout = 3000) fun longReplayAndReasoningLeaveOnlyTheNewSuffix() {
        val saved = "A saved word.\n".repeat(30_000)
        val rows = messages.dropLast(1) + Message(4, role = "assistant", content = saved, reasoningContent = saved)
        val turn = TurnState.Streaming(saved + "**New answer**", reasoning = saved + "New thought", parts = listOf(
            LivePart.Prose("reasoning", saved + "New thought", thinking = true), LivePart.Prose("answer", saved + "**New answer**")))
        val result = projectLiveHistory(turn, rows, record).turn as TurnState.Streaming
        assertEquals("**New answer**", result.text)
        assertEquals("New thought", result.reasoning)
        assertEquals(listOf("New thought", "**New answer**"), result.parts.filterIsInstance<LivePart.Prose>().map { it.text })
        val mismatch = turn.copy(text = saved.dropLast(2) + "? Different")
        assertEquals(mismatch.text, (projectLiveHistory(mismatch, rows, record).turn as TurnState.Streaming).text)
    }
}
