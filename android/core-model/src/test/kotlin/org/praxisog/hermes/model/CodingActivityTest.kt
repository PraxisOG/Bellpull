package org.praxisog.hermes.model

import org.junit.Assert.*
import org.junit.Test

class CodingActivityTest {
    private val work = CodingWork("a".repeat(32), "root", "run_one", "proc_one", state = "running")
    private fun row(sequence: Long, key: String = "part", detail: String = "Text") = CodingRow(sequence, work.id, key, 123, "text", "Response", detail)
    private fun snapshot(cursor: Long, rows: List<CodingRow>, reset: Boolean = true) = CodingSnapshot(1,"root",listOf("root","tip"),cursor,reset,false,works=listOf(work),rows=rows,observedAt=1.0)
    @Test fun partUpdatesReplaceRowsAndReplayDoesNotDuplicateThem() {
        val first = snapshot(1,listOf(row(1))).validated("root")
        val next = snapshot(2,listOf(row(2,detail="Updated")),false).validated("root").merge(first)
        assertEquals(listOf("Updated"),next.rows.map { it.detail })
        assertEquals(next.rows,snapshot(2,emptyList(),false).merge(next).rows)
    }
    @Test fun resetReplacesOldRowsAndRetiredWorkDropsItsRows() {
        val first = snapshot(1,listOf(row(1)))
        assertEquals(emptyList<CodingRow>(),snapshot(2,emptyList(),true).merge(first).rows)
        assertTrue(snapshot(2,emptyList(),false).copy(works=emptyList()).merge(first).rows.isEmpty())
    }
    @Test fun mergingAnOlderServersDeltaMarksOnlyAnActualEviction() {
        val second = work.copy(id = "b".repeat(32), executor = "claude")
        val works = listOf(work, second)
        val initialRows = (1L..300L).map { i ->
            row(i, "event-$i").copy(workId = if (i > 150) second.id else work.id)
        }
        val initial = snapshot(300, initialRows).copy(works = works).validated("root")
        val replacement = initialRows.last().copy(sequence = 301)
        val revised = snapshot(301, listOf(replacement), false).copy(works = works).merge(initial)
        assertFalse(revised.gap)
        val appended = replacement.copy(key = "new", sequence = 302)
        val evicted = snapshot(302, listOf(appended), false).copy(works = works).merge(revised)
        assertTrue(evicted.gap)
        assertEquals(300, evicted.rows.size)
        assertEquals(2L, evicted.rows.first().sequence)
        assertTrue(snapshot(302, emptyList(), false).copy(works = works).merge(evicted).gap)
    }
    @Test fun malformedOwnershipOrderAndCursorAreRejected() {
        val good=snapshot(2,listOf(row(1),row(2,"other")))
        for (bad in listOf(good.copy(sessionId="foreign"),good.copy(sessionIds=listOf("other")),
            good.copy(rows=good.rows.reversed()),good.copy(cursor=1),good.copy(works=emptyList()),good.copy(version=2))) {
            assertThrows(IllegalArgumentException::class.java) { bad.validated("root") }
        }
        assertThrows(IllegalArgumentException::class.java) { snapshot(0,emptyList(),false).merge(good) }
    }
    @Test fun timelineNeverDerivesWorkCompletionFromStepOrConnectionState() {
        val value = snapshot(1,listOf(row(1).copy(kind="step_finished"))).merge(null)
        assertEquals("running",value.works.single().state)
        assertTrue(value.copy(gap=true).works.single().state == "running")
    }
}
