package org.praxisog.hermes

import org.junit.Assert.*
import org.junit.Test

class ConversationReadStateTest {
    @Test fun readAndUnreadReceiptsSurviveRestoration() {
        val original = ConversationReadState()
        original.completed("a", 10)
        original.readThrough("b", 20)
        original.completed("b", 20)
        val restored = ConversationReadState(original.snapshot())
        assertEquals(setOf("a"), restored.unreadIds)
        restored.readThrough("a", 9)
        assertEquals(setOf("a"), restored.unreadIds)
        restored.readThrough("a", 10)
        assertTrue(restored.unreadIds.isEmpty())
        restored.completed("a", 10)
        assertTrue(restored.unreadIds.isEmpty())
    }
    @Test fun olderCompletionAndReadEventsCannotRegressNewerState() {
        val receipts = ConversationReadState()
        receipts.completed("a", 12)
        receipts.completed("a", 10)
        receipts.readThrough("a", 10)
        assertEquals(setOf("a"), receipts.unreadIds)
        receipts.readThrough("a", 12)
        receipts.readThrough("a", 9)
        receipts.completed("a", 11)
        assertTrue(receipts.unreadIds.isEmpty())
    }

    @Test fun continuationReadClearsOlderAliasWithoutReadingNewerReplyOrBranch() {
        val receipts = ConversationReadState()
        receipts.completed("root", 10)
        receipts.completed("tip", 20)
        receipts.completed("branch", 9)
        receipts.readThroughAliases(setOf("root", "tip"), 10)
        assertEquals(setOf("tip", "branch"), receipts.unreadIds)
        receipts.readThroughAliases(setOf("root", "tip"), 20)
        assertEquals(setOf("branch"), receipts.unreadIds)
    }
}
