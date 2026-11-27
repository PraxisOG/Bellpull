package org.praxisog.hermes.model

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkerResultTest {
    private val status = "Status: completed   Duration: 51.12s"
    private val answer = "The clock chimed.\n--- RESULT ---\n    Indented worker output\n"
    private val body = """
        [ASYNC DELEGATION COMPLETE — deleg_123]
        A background subagent you dispatched earlier has finished. You may have moved on since dispatching it; the full task source is below so you can act on the result or re-dispatch if things have changed.

        Dispatched: 2026-11-27 02:31:51 (51s ago)
        Original goal: Count clocks
        Role: leaf
        $status
        --- RESULT ---
    """.trimIndent() + "\n" + answer
    private val row = Message(7, role = "user", content = body, displayKind = "async_delegation_complete", delegationId = "deleg_123")

    @Test fun presentsTheVerbatimAnswerOnceAndKeepsTheStoredReport() {
        val result = displayMessages(listOf(row, row.copy(id = 8))).single()
        assertEquals(row.copy(role = "assistant", content = answer), result)
        assertEquals(result, displayMessages(listOf(result)).single())
        assertEquals(body, row.content)
    }

    @Test fun preservesFailureRecoveryPartialOutputAndTruncationWarning() {
        val failure = "The subagent did not complete successfully (status=failed).\nDisk full\nPartial output:\nOne chime."
        val truncated = "[Partial output: the subagent hit its iteration limit.]\nOne chime."
        for ((line, result) in listOf(
            "Status: failed   API calls: 3   Duration: ?s" to failure,
            "$status [TRUNCATED: hit max_iterations — work may be incomplete]" to truncated,
        )) {
            val raw = body.replace(status, line).removeSuffix(answer) + result
            assertEquals(result, displayMessages(listOf(row.copy(content = raw))).single().content)
        }
    }

    @Test fun preservesOrdinaryMessagesNoticesMismatchedIdsAndUnrecognizedReports() {
        val variants = listOf(row.copy(displayKind = null), row.copy(delegationId = null),
            row.copy(delegationId = "another"), row.copy(delegationNotice = "task_failure:0"),
            row.copy(content = "Already a standalone answer"), row.copy(content = body.replace("Role: leaf", "Role: leaf\nUpstream warning")),
            row.copy(content = body.replace("Count clocks", "Sample:\n$status\n--- RESULT ---\nexample")),
            row.copy(content = body.removeSuffix(answer)), row.copy(content = body.replace(status, "Status: newer format")))
        for (input in variants) assertEquals(input.content, displayMessages(listOf(input)).single().content)
    }
}
