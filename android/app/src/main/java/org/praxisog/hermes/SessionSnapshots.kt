package org.praxisog.hermes

import org.praxisog.hermes.model.Session

/**
 * Persistence seam for the last-synced session list.
 *
 * [HermesStore] holds one of these rather than a [SessionCache] directly, for two reasons:
 * the store's whole design is to stay free of Android types, and the offline fallback is
 * logic worth testing on the JVM — [SessionCache] needs a real AndroidKeyStore and cannot run
 * there at all.
 */
internal interface SessionSnapshots {
    fun read(): Snapshot?
    fun write(sessions: List<Session>, atEpochSeconds: Double)
    fun clear()
    fun readHistory(id: String): List<org.praxisog.hermes.model.Message>? = null
    fun writeHistory(id: String, messages: List<org.praxisog.hermes.model.Message>) {}

    /** Chats this phone created untitled; their first delivered prompt may name them. */
    fun readUntitled(): Set<String> = emptySet()
    fun writeUntitled(ids: Set<String>) {}

    /** Derived first-prompt titles awaiting a confirmed PATCH, keyed by session id. */
    fun readPendingTitles(): Map<String, String> = emptyMap()
    fun writePendingTitle(id: String, title: String) {}
    fun clearPendingTitle(id: String) {}

    data class Snapshot(val sessions: List<Session>, val cachedAtEpochSeconds: Double)
}
