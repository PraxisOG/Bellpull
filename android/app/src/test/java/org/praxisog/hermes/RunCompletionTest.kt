package org.praxisog.hermes

import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.model.Message
import org.praxisog.hermes.model.RunSubmission
import kotlinx.serialization.json.Json

class RunCompletionTest {
    private val record = RunSubmission("request", "root", "repeat", 500.0, 1, setOf(1, 2),
        runId = "run", status = "completed", completionOutput = "Final", serverCreatedAt = 100.0)

    @Test fun copiedOldRowsCannotPassTheCompactedPromptFallback() {
        assertNull(matchedCompletion(record, listOf(Message(20, role = "assistant", content = "Final", timestamp = 99.0))))
        assertNull(matchedCompletion(record, listOf(Message(20, role = "assistant", content = "Final"))))
        assertEquals(20, matchedCompletion(record, listOf(Message(20, "tip", "assistant", "Final", timestamp = 101.0)))?.id)
    }

    @Test fun twoIdenticalNewPromptsAreAmbiguousAndOtherTurnsDoNotCount() {
        val prompt = Message(10, role = "user", content = "repeat")
        val reply = Message(12, role = "assistant", content = "Final")
        assertNull(matchedCompletion(record, listOf(prompt, prompt.copy(id = 11), reply)))
        assertNull(matchedCompletion(record, listOf(prompt, prompt.copy(id = 11, content = "Another turn"), reply)))
        assertNull(matchedCompletion(record.copy(completionOutput = null), listOf(prompt, reply)))
    }

    @Test fun oldJournalLoadsAndReconciliationEvidenceRoundTrips() {
        val old = Json.decodeFromString<RunSubmission>("""{"requestId":"old","sessionId":"root","text":"repeat","createdAt":1,"draftRevision":1,"previousIds":[1],"runId":"admitted","status":"completed"}""")
        assertNull(old.completionOutput)
        assertFalse(old.historyNeedsReview)
        val pending = old.copy(completionOutput = "Final", serverCreatedAt = 100.0, historyChecks = 12, historyNeedsReview = true)
        assertEquals(pending, Json.decodeFromString<RunSubmission>(Json.encodeToString(pending)))
    }
}
