package org.praxisog.hermes.model

import org.junit.Assert.*
import org.junit.Test

class MemoryStatusTest {
    private val old = """{"access":{"owner_memory":"unavailable"},"records":0,"unfinished_messages":4,"worker_enabled":false}"""
    private fun observed(receipt: String) = old.dropLast(1) + ""","bellpull_memory":{$receipt}}"""
    private val identity = """"provider":"memory-subsystem","version":1,"observed_at":20.125"""
    private val fresh = observed("""$identity,"context_created_at":10""").replace("unavailable", "available")

    @Test fun legacyResultShowsScopedCountsWithoutInventingProviderOrTime() {
        val item = MemoryStatus.parse("memory_status", old)!!
        assertFalse(item.ownerAvailable)
        assertEquals(0L, item.visibleRecords)
        assertEquals(4L, item.unfinishedMessages)
        assertEquals(false, item.workerEnabled)
        assertNull(item.provider)
        assertNull(item.observedAtMillis)
        assertNull(item.contextCreatedAtMillis)
    }

    @Test fun statusTimeAndSnapshotTimeRemainIndependent() {
        val item = MemoryStatus.parse("memory_status", fresh)!!
        assertEquals("memory-subsystem", item.provider)
        assertTrue(item.ownerAvailable)
        assertEquals(20125L, item.observedAtMillis)
        assertEquals(10000L, item.contextCreatedAtMillis)
        assertNull(MemoryStatus.parse("memory_status", observed(identity))!!.contextCreatedAtMillis)
        assertNull(MemoryStatus.parse("memory_status", observed("""$identity,"context_created_at":21"""))!!.contextCreatedAtMillis)
        for (bad in listOf(identity.replace("20.125", "1e200"), identity.replace("memory-subsystem", "other"),
            identity.replace("\"version\":1", "\"version\":2"))) {
            assertNull(MemoryStatus.parse("memory_status", observed(bad))!!.provider)
        }
    }

    @Test fun ignoresFailuresAndUnrelatedTools() {
        assertNull(MemoryStatus.parse("another_tool", fresh))
        for (raw in listOf("text", "[]", "{}", old.replace("unavailable", "unknown"),
            old.dropLast(1) + ""","error":"denied"}""", old.dropLast(1) + ""","status":"blocked"}""")) {
            assertNull(MemoryStatus.parse("memory_status", raw))
        }
    }

    @Test fun usesOnlyMatchedNativeResultsInTheSelectedChatAndDropsSupersededEvidence() {
        fun call(id: String, name: String) = Message(1, "one", "assistant",
            toolCalls = listOf(ToolCall(id, function = ToolCallFunction(name))))
        val result = Message(2, "one", "tool", fresh, toolCallId = "a")
        val history = listOf(call("a", "memory_status"), result)
        assertNull(MemoryStatus.latest("two", history))
        assertNull(MemoryStatus.latest("one", listOf(result, call("a", "memory_status"))))
        assertNull(MemoryStatus.latest("one", listOf(call("a", "another_tool"), result)))
        assertNull(MemoryStatus.latest("one", listOf(call("a", "memory_status"), result.copy(role = "user"))))
        assertEquals(true, MemoryStatus.latest("one", history + result.copy(sessionId = "two", content = old))?.ownerAvailable)
        assertNull(MemoryStatus.latest("one", history + call("b", "memory_status") + result.copy(toolCallId = "b", content = "denied")))
    }
}
