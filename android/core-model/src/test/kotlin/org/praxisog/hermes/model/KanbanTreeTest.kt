package org.praxisog.hermes.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class KanbanTreeTest {
    private fun task(id: String, parent: String? = null, status: String = "triage", count: Int = 0) =
        KanbanTask(id, id, status, "a".repeat(64), containerId = parent, subtaskCount = count)
    @Test fun dependenciesNeverBecomeContainmentAndProgressCountsEachDescendantOnce() {
        val root = task("root", count = 2).copy(parents = listOf("leaf"), children = listOf("sibling"))
        val tasks = listOf(root, task("child", "root", count = 1), task("leaf", "child", "done"), task("sibling", "root", "archived"))
        val tree = KanbanTree(tasks, true)
        assertEquals(listOf("root"), tree.rows(emptySet(), "").map { it.first.id })
        assertEquals(listOf("root", "child", "leaf", "sibling"), tree.rows(setOf("root", "child"), "").map { it.first.id })
        assertEquals(1 to 3, tree.progress["root"])
        assertEquals(tree.progress, KanbanTree(tasks + tasks, true).progress)
        assertEquals(1 to 1, tree.progress["child"])
        assertTrue(tree.incomplete.isEmpty())
        assertEquals("triage", root.status)
        assertFalse(tree.canContain("root", "leaf")); assertFalse(tree.canContain("child", "child"))
        assertTrue(tree.canContain("leaf", "sibling")); assertTrue(tree.canContain("root", null))
        assertFalse(tree.canContain("child", "missing"))
    }
    @Test fun searchShowsAncestorsEvenWhenCollapsedButNotUnrelatedSiblings() {
        val tree = KanbanTree(listOf(task("root", count = 2), task("branch", "root", count = 1), task("needle", "branch"), task("sibling", "root")), true)
        assertEquals(listOf("root", "branch", "needle"), tree.rows(emptySet(), "needle").map { it.first.id })
    }
    @Test fun unloadedUnavailableAndEmptyRemainDistinctAndCorruptCyclesStayVisible() {
        val partial = KanbanTree(listOf(task("child", "older", count = 2)), false)
        assertTrue("child" in partial.orphaned); assertTrue("child" in partial.incomplete)
        val empty = KanbanTree(listOf(task("root")), true)
        assertEquals(0 to 0, empty.progress["root"]); assertTrue(empty.incomplete.isEmpty())
        val corrupt = KanbanTree(listOf(task("a", "b", count = 1), task("b", "a", count = 1)), true)
        assertEquals(2, corrupt.rows(setOf("a", "b"), "").map { it.first.id }.toSet().size)
        assertTrue(corrupt.incomplete.isNotEmpty())
    }
    @Test fun tenThousandLevelsUseIterativeTraversal() {
        val tasks = (0 until 10000).map { task("t$it", if (it == 0) null else "t${it-1}", count = if (it == 9999) 0 else 1) }
        val tree = KanbanTree(tasks, true)
        assertEquals(0 to 9999, tree.progress["t0"])
        assertEquals(10000, tree.rows(tasks.map { it.id }.toSet(), "").size)
        assertEquals(10000, tree.rows(emptySet(), "t9999").size)
        assertTrue(tree.parentCandidates("t0").isEmpty())
        assertEquals(9999, tree.parentCandidates("t9999").size)
    }
    @Test fun legacyCacheAndContainmentIntentRoundTripWithoutChangingDependencies() {
        val old = Json.decodeFromString<KanbanLocalState>("""{"boardView":true}""")
        assertTrue(old.boardView); assertNull(old.viewMode); assertTrue(old.hierarchies.isEmpty())
        val mutation = KanbanMutation("a".repeat(32), "b".repeat(64), "c".repeat(32), "child", "d".repeat(64), "contain", containerId = "parent").validated()
        assertEquals(mutation, Json.decodeFromString<KanbanMutation>(Json.encodeToString(mutation)))
        assertThrows(IllegalArgumentException::class.java) { mutation.copy(containerId = "child").validated() }
    }
}
