package org.praxisog.hermes

import java.io.File

private const val DAY_MS = 24L * 60 * 60 * 1000

/** Startup-only cleanup. Draft attachments remain until accepted or explicitly removed;
 * recent shares/downloads are kept so another app can finish reading its URI. */
internal fun cleanPrivateFiles(root: File, shares: File, draftIds: Set<String>, now: Long = System.currentTimeMillis()) {
    root.listFiles().orEmpty().filter { it.isFile && it.name !in draftIds }.forEach { file ->
        val age = now - file.lastModified()
        val expires = when {
            file.name.endsWith(".tmp") -> DAY_MS
            file.name.startsWith("download-") -> 7 * DAY_MS
            file.name.matches(Regex("[a-f0-9]{32}")) -> DAY_MS
            else -> Long.MAX_VALUE
        }
        if (age > expires) file.delete()
    }
    shares.listFiles().orEmpty().filter { now - it.lastModified() > 7 * DAY_MS }.forEach { it.deleteRecursively() }
}
