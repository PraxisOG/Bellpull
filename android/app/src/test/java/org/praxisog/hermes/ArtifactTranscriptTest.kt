package org.praxisog.hermes

import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.model.Artifact
import org.praxisog.hermes.model.Message

class ArtifactTranscriptTest {
    private fun file(id: String, time: Double, message: Int? = null) =
        Artifact(id, "s", "$id.pdf", "application/pdf", 10, "0".repeat(64), time, "agent", messageId = message)
    @Test fun filesStayInChronologicalPositionWhenLaterRepliesArrive() {
        val messages = listOf(Message(1, role = "user", timestamp = 2.0), Message(2, role = "assistant", timestamp = 4.0),
            Message(3, role = "user", timestamp = 6.0), Message(4, role = "assistant", timestamp = 8.0))
        val entries = artifactTranscript(groupTranscript(messages), listOf(file("output", 5.0), file("input", 1.0)))
        assertEquals(listOf("artifact-input", "message-1", "message-2", "artifact-output", "message-3", "message-4"), entries.map { it.key })
    }
    @Test fun explicitMessageWinsAndGroupedToolsRemainIntact() {
        val messages = listOf(Message(1, role = "tool", timestamp = 2.0), Message(2, role = "tool", timestamp = 3.0),
            Message(3, role = "assistant", timestamp = 4.0))
        val entries = artifactTranscript(groupTranscript(messages), listOf(file("output", 7.0, 1)))
        assertEquals(listOf("tools-1", "artifact-output", "message-3"), entries.map { it.key })
    }
    @Test fun legacyMissingTimesAndEmptyHistoryKeepFilesAccessibleOnce() {
        val artifact = file("output", 7.0, 90)
        assertEquals(listOf("message-1", "artifact-output"), artifactTranscript(groupTranscript(listOf(Message(1, role = "assistant"))),
            listOf(artifact, artifact)).map { it.key })
        assertEquals(listOf("artifact-output"), artifactTranscript(emptyList(), listOf(artifact)).map { it.key })
    }
}
