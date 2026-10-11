package org.praxisog.hermes

import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.model.RunSubmission
import java.nio.file.Files

class RetentionTest {
    @Test fun ambiguousAndUnreconciledRunsNeverExpire() {
        val old = RunSubmission("request", "chat", "draft", 1.0, 0, emptySet())
        for (status in listOf("submitting", "reconnecting", "running", "review"))
            assertFalse(canPruneRun(old.copy(status = status, historySynced = true), 100.0))
        assertFalse(canPruneRun(old.copy(status = "completed"), 100.0))
        assertTrue(canPruneRun(old.copy(status = "completed", historySynced = true), 100.0))
        assertFalse(canPruneRun(old.copy(status = "completed", historySynced = true), 0.0))
    }
    @Test fun cleanupPreservesDraftsAndRecentDownloads() {
        val home = Files.createTempDirectory("bellpull-cleanup").toFile()
        try {
            val root = home.resolve("files").apply { mkdir() }
            val shares = home.resolve("shares").apply { mkdir() }
            val draft = root.resolve("1".repeat(32)).apply { writeText("draft"); setLastModified(1) }
            val orphan = root.resolve("2".repeat(32)).apply { writeText("orphan"); setLastModified(1) }
            val old = root.resolve("download-old.tmp").apply { writeText("partial"); setLastModified(1) }
            val recent = root.resolve("download-new").apply { writeText("recent") }
            cleanPrivateFiles(root, shares, setOf(draft.name))
            assertTrue(draft.exists()); assertTrue(recent.exists())
            assertFalse(orphan.exists()); assertFalse(old.exists())
        } finally { home.deleteRecursively() }
    }
}
