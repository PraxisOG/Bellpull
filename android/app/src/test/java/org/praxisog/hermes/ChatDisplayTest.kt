package org.praxisog.hermes

import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.model.StreamEvent
import org.praxisog.hermes.model.Message
import org.praxisog.hermes.model.RunSubmission

class ChatDisplayTest {
    @Test fun hiddenTerminalKeepsFailuresRefusalsAndOtherToolsVisible() {
        val display = ChatDisplayOptions(hideTerminalCalls = true)
        assertFalse(display.showsTool("terminal", """{"exit_code":0,"output":"done"}"""))
        assertFalse(display.showsTool("TERMINAL"))
        assertTrue(display.showsTool("terminal", """{"exit_code":1}"""))
        assertTrue(display.showsTool("terminal", """{"status":"blocked"}"""))
        assertTrue(display.showsTool("terminal", """{"status":"pending_approval"}"""))
        assertTrue(display.showsTool("terminal", """{"approval":"flagged"}"""))
        assertTrue(display.showsTool("web_search")); assertTrue(display.showsTool(null))
        assertTrue(ChatDisplayOptions().showsTool("terminal"))
    }
    @Test fun countsUseUniqueReceivedBlocksAndStartsNotDeltasOrCompletions() {
        val transcript = LiveTranscript()
        val events = listOf(
            """{"event":"run.started","seq":0,"run_id":"one"}""",
            """{"event":"tool.progress","seq":1,"tool_name":"_thinking","delta":"First thought"}""",
            """{"event":"tool.progress","seq":2,"tool_name":"_thinking","delta":" keeps growing"}""",
            """{"event":"tool.started","seq":3,"tool_name":"terminal"}""",
            """{"event":"tool.completed","seq":4,"tool_name":"terminal"}""",
            """{"event":"tool.progress","seq":5,"tool_name":"_thinking","delta":"Second thought"}""",
            """{"event":"tool.started","seq":6,"tool_name":"web_search"}""",
            """{"event":"tool.started","seq":7,"tool_name":"read_file"}"""
        )
        (events + events).forEach { transcript.accept(checkNotNull(StreamEvent.parse(null, it))) }
        val turn = TurnState.Streaming("", parts = transcript.parts)
        assertEquals(ResponseActivity(2, 1, 1, 1), responseActivity(turn))
        assertEquals(responseActivity(turn), responseActivity(turn.copy(parts = turn.parts + turn.parts)))
        assertEquals("2 thinking blocks · 1 terminal · 1 search · 1 other tool", responseActivity(turn).label)
    }
    @Test fun previewIsSingleLineBoundedAndDoesNotBreakEmoji() {
        assertEquals("Checking the result.", thinkingPreview(" \n Checking\t the result.  "))
        assertEquals("🔔🔔🔔…", thinkingPreview("🔔".repeat(20), 3))
        assertEquals("ab…", thinkingPreview("ab c", 3))
        assertTrue(thinkingPreview("Long text ".repeat(100000)).length <= 141)
        assertEquals("", thinkingPreview(" ".repeat(100000)))
    }
    @Test fun fallbackAndIdleDoNotInventActivity() {
        assertEquals(ResponseActivity(), responseActivity(TurnState.Idle))
        assertEquals(ResponseActivity(), responseActivity(TurnState.Streaming("Prose")))
        assertEquals(ResponseActivity(1, 1), responseActivity(TurnState.Streaming("", reasoning = "Thought", runningTool = "Running", runningToolName = "terminal")))
    }
}
