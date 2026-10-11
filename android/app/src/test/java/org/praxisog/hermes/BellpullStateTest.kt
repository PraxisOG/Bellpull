package org.praxisog.hermes

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.model.Message

class BellpullStateTest {
    @Test fun interimCommentaryEndsItsParagraphAndAlreadyStreamedCopiesAreIgnored() {
        val live = LiveTranscript()
        listOf(
            "message.interim" to """{"text":"Reading your notes.","already_streamed":false,"seq":0}""",
            "message.interim" to """{"text":"Reading your notes.","already_streamed":true,"seq":1}""",
            "message.delta" to """{"delta":"The answer is ","seq":2}""",
            "message.delta" to """{"delta":"here.","seq":3}""",
        ).forEach { (name, json) -> live.accept(org.praxisog.hermes.model.StreamEvent.parse(name, json)!!) }
        assertEquals(listOf("Reading your notes.", "The answer is here."), live.parts.filterIsInstance<LivePart.Prose>().map { it.text })
    }
    @Test fun liveToolsAppendOnceAndCommentaryKeepsItsChronologicalPosition() {
        val live = LiveTranscript()
        fun event(name: String, seq: Int, fields: String = "") =
            org.praxisog.hermes.model.StreamEvent.parse(name, "{\"seq\":$seq$fields}")!!
        live.accept(event("run.started", 1, ",\"run_id\":\"run-a\""))
        val first = event("tool.started", 2, ",\"tool_name\":\"web_search\"")
        live.accept(first)
        live.accept(first) // an identical delivery must not add another row
        live.accept(event("tool.completed", 3, ",\"tool_name\":\"web_search\""))
        live.accept(event("tool.started", 4, ",\"tool_name\":\"web_extract\""))
        live.accept(event("assistant.delta", 5, ",\"delta\":\"Reading that page.\""))
        live.accept(event("tool.started", 6, ",\"tool_name\":\"terminal\""))
        assertEquals(3, live.parts.size)
        val firstGroup = live.parts.first() as LivePart.Tools
        assertEquals(2, firstGroup.rows.size)
        assertFalse(firstGroup.rows.first().running)
        assertEquals("Reading that page.", (live.parts[1] as LivePart.Prose).text)
        assertEquals(1, (live.parts[2] as LivePart.Tools).rows.size)
    }

    @Test fun acknowledgementOfAnOlderSendPreservesNewerDraftAndSelection() {
        val drafts = ChatDrafts()
        drafts.set("a", TextFieldValue("sent", TextRange(2)))
        val revision = drafts.revision("a")
        drafts.set("a", TextFieldValue("next thought", TextRange(3, 7)))
        drafts.accepted("a", revision)
        assertEquals(TextFieldValue("next thought", TextRange(3, 7)), drafts["a"])
    }

    @Test fun acceptedDraftClearsOnlyItsConversation() {
        val drafts = ChatDrafts()
        drafts.set("a", TextFieldValue("send this"))
        drafts.set("b", TextFieldValue("keep this", TextRange(5)))
        drafts.accepted("a", drafts.revision("a"))
        assertEquals("", drafts["a"].text)
        assertEquals(TextFieldValue("keep this", TextRange(5)), drafts["b"])
    }

    @Test fun toolsGroupWithoutCrossingCommentaryOrUserTurns() {
        val messages = listOf(
            Message(1, role = "user", content = "start"), Message(2, role = "tool"), Message(3, role = "tool"),
            Message(4, role = "assistant", content = "Next step"), Message(5, role = "tool"),
            Message(6, role = "user", content = "stop"), Message(7, role = "tool"))
        val groups = groupTranscript(messages)
        assertEquals(listOf(listOf(1), listOf(2, 3), listOf(4), listOf(5), listOf(6), listOf(7)),
            groups.map { group -> group.messages.map { it.id } })
        assertEquals(groups.map { it.key }, groupTranscript(messages).map { it.key })
    }
}
