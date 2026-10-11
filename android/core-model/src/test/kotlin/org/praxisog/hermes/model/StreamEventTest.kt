package org.praxisog.hermes.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamEventTest {

    @Test fun liveReasoningKeepsWhitespaceAndInterimProseKeepsItsDeliveryFlag() {
        val thinking = StreamEvent.parse("reasoning.delta", """{"text":" still thinking\n","seq":3}""") as StreamEvent.ToolProgress
        assertEquals("_thinking", thinking.toolName)
        assertEquals(" still thinking\n", thinking.delta)
        val commentary = StreamEvent.parse("message.interim", """{"text":"Reading notes.","already_streamed":true}""") as StreamEvent.AssistantCommentary
        assertEquals("Reading notes.", commentary.text)
        assertTrue(commentary.alreadyStreamed)
    }

    @Test fun parsesEachObservedEventType() {
        assertTrue(StreamEvent.parse("run.started", """{"run_id":"run_1","seq":1}""") is StreamEvent.RunStarted)
        assertTrue(StreamEvent.parse("message.started", """{"message":{"id":"msg_1"},"seq":2}""") is StreamEvent.MessageStarted)
        assertTrue(StreamEvent.parse("tool.progress", """{"tool_name":"_thinking","delta":"x","seq":4}""") is StreamEvent.ToolProgress)
        assertTrue(StreamEvent.parse("tool.started", """{"tool_name":"terminal","preview":"id -un","seq":5}""") is StreamEvent.ToolStarted)
        assertTrue(StreamEvent.parse("tool.completed", """{"tool_name":"terminal","preview":null,"args":null,"seq":6}""") is StreamEvent.ToolCompleted)
        assertTrue(StreamEvent.parse("done", """{"seq":9}""") is StreamEvent.Done)
    }

    @Test fun messageStartedReadsTheNestedMessageId() {
        val event = StreamEvent.parse("message.started", """{"message":{"id":"msg_abc","role":"assistant"},"seq":2}""")
        assertEquals("msg_abc", (event as StreamEvent.MessageStarted).messageId)
    }

    @Test fun deltaCarriesTokenTextVerbatimIncludingWhitespace() {
        val event = StreamEvent.parse("assistant.delta", """{"message_id":"m","delta":" printed","seq":3}""")
        assertEquals(" printed", (event as StreamEvent.AssistantDelta).delta)
    }

    @Test fun completionFlagsDistinguishFinishedFromTruncated() {
        val finished = StreamEvent.parse(
            "assistant.completed",
            """{"message_id":"m","content":"hi","completed":true,"partial":false,"interrupted":false,"seq":7}"""
        ) as StreamEvent.AssistantCompleted
        assertTrue(finished.completed)
        assertFalse(finished.partial)

        val truncated = StreamEvent.parse(
            "assistant.completed",
            """{"message_id":"m","content":"hi","completed":false,"partial":true,"interrupted":true,"seq":7}"""
        ) as StreamEvent.AssistantCompleted
        assertFalse(truncated.completed)
        assertTrue(truncated.partial)
        assertTrue(truncated.interrupted)
    }

    @Test fun runCompletedTolerksNullFlagsObservedLive() {
        // run.completed sends completed:true but omits partial/interrupted entirely.
        val event = StreamEvent.parse("run.completed", """{"completed":true,"usage":{"total_tokens":1},"seq":8}""")
        assertTrue((event as StreamEvent.RunCompleted).completed)
        assertTrue(event.isTerminal)
    }

    @Test fun onlyDoneAndRunCompletedAreTerminal() {
        assertFalse(StreamEvent.parse("assistant.delta", """{"delta":"x"}""")!!.isTerminal)
        assertFalse(StreamEvent.parse("assistant.completed", """{"completed":true}""")!!.isTerminal)
        assertTrue(StreamEvent.parse("run.completed", """{"completed":true}""")!!.isTerminal)
        assertTrue(StreamEvent.parse("done", """{}""")!!.isTerminal)
    }

    @Test fun anUnrecognisedEventIsKeptNotThrown() {
        val event = StreamEvent.parse("some.future.event", """{"seq":99,"whatever":true}""")
        assertTrue(event is StreamEvent.Unknown)
        assertEquals("some.future.event", (event as StreamEvent.Unknown).event)
        assertEquals(99, event.seq)
        assertFalse(event.isTerminal)
    }

    @Test fun nonObjectDataReturnsNull() {
        assertEquals(null, StreamEvent.parse("run.started", "not json"))
    }
}
