package org.praxisog.hermes

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.model.Message
import org.praxisog.hermes.model.RunSubmission

class MessageInfoTest {
    private val completed = RunSubmission("request", "root", "repeat", 500.0, 1, setOf(1),
        runId = "run", status = "completed", completionOutput = "Final", serverCreatedAt = 100.0,
        serverCompletedAt = 172.0,
        completionUsage = Json.parseToJsonElement("""{"input_tokens":12345,"output_tokens":678,"cached":null}""")
            .let { it as kotlinx.serialization.json.JsonObject })

    @Test fun usageCountersAcceptOnlyNonnegativeNumericValues() {
        val usage = completed.completionUsage!!
        assertEquals(12345L, usageCounter(usage, "input_tokens"))
        assertEquals(678L, usageCounter(usage, "output_tokens"))
        assertNull(usageCounter(usage, "cached"))
        assertNull(usageCounter(usage, "missing"))
        assertNull(usageCounter(null, "input_tokens"))
        val hostile = Json.parseToJsonElement("""{"input_tokens":"999","output_tokens":-5,"ok":0}""")
            .let { it as kotlinx.serialization.json.JsonObject }
        assertNull(usageCounter(hostile, "input_tokens"))
        assertNull(usageCounter(hostile, "output_tokens"))
        assertEquals(0L, usageCounter(hostile, "ok"))
    }

    @Test fun runStatsAttachOnlyToTheUniquelyMatchedFinalReply() {
        val prompt = Message(10, role = "user", content = "repeat")
        val reply = Message(12, role = "assistant", content = "Final", timestamp = 101.0)
        val info = messageRunInfo(listOf(prompt, reply), listOf(completed))
        assertEquals(72.0, info[12]?.durationSeconds!!, 0.0001)
        assertEquals(12345L, info[12]!!.inputTokens)
        assertEquals(678L, info[12]!!.outputTokens)
        assertFalse(info[12]!!.estimatedDuration)
        // Ambiguous transcripts (two identical prompts) match no single row.
        assertTrue(messageRunInfo(listOf(prompt, prompt.copy(id = 11), reply), listOf(completed)).isEmpty())
        // Non-completed runs supply nothing.
        assertTrue(messageRunInfo(listOf(prompt, reply), listOf(completed.copy(status = "running"))).isEmpty())
    }

    @Test fun finalReplyWithoutRunEvidenceGetsAnExplicitlyEstimatedInterval() {
        val user = Message(1, role = "user", content = "ask", timestamp = 10.0)
        val final = Message(2, role = "assistant", content = "answer", timestamp = 40.0)
        val info = messageInfo(final, listOf(user, final), emptyMap())
        assertEquals(30.0, info.durationSeconds!!, 0.0001)
        assertTrue(info.estimatedDuration)
        // Intermediate assistant rows in a multi-row turn do not estimate; the final one does.
        val second = Message(3, role = "assistant", content = "more", timestamp = 50.0)
        assertTrue(messageInfo(final, listOf(user, final, second), emptyMap()).durationSeconds == null)
        assertEquals(40.0, messageInfo(second, listOf(user, final, second), emptyMap()).durationSeconds!!, 0.0001)
        val toolTurn = listOf(user,
            Message(4, role = "assistant", content = null, timestamp = 20.0, toolCalls = listOf(
                org.praxisog.hermes.model.ToolCall(id = "t", function = org.praxisog.hermes.model.ToolCallFunction(name = "terminal")))),
            Message(5, role = "tool", content = "ok", timestamp = 25.0))
        assertTrue(messageInfo(toolTurn[1], toolTurn, emptyMap()).durationSeconds == null)
        // User messages never show stats.
        assertTrue(messageInfo(user, listOf(user, final), emptyMap()).available.not())
        // Missing or inverted timestamps supply no estimate.
        val untimed = Message(6, role = "assistant", content = "answer")
        assertTrue(messageInfo(untimed, listOf(user.copy(timestamp = null), untimed), emptyMap()).durationSeconds == null)
    }

    @Test fun durationsFormatWithoutZeroFilling() {
        assertEquals("<1 s", formatWorkingDuration(0.4))
        assertEquals("12.5 s", formatWorkingDuration(12.5))
        assertEquals("1 min 12 s", formatWorkingDuration(72.0))
        assertEquals("1 h 2 min", formatWorkingDuration(3720.0))
        assertEquals("12,345", groupDigits(12345))
    }
}
