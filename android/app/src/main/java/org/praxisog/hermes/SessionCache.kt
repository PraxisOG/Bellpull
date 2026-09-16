package org.praxisog.hermes

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.json.Json
import org.praxisog.hermes.model.Session

/**
 * Read-only cache of the last synced session list, so the app opens to something on a dead
 * network instead of a spinner.
 *
 * **Encrypted, deliberately.** Session titles and previews are the first line of real
 * conversations with an agent that has shell access to the homelab. That is not the kind of
 * thing to leave in plain SharedPreferences because it is "just a cache".
 *
 * **Read-only in the sense that matters:** it is never merged with server state. Hermes is
 * the source of truth, so a successful sync overwrites this wholesale and the cache is only
 * ever read when a sync has failed. There is no reconciliation path and no way for stale
 * local data to win.
 */
@Suppress("DEPRECATION") // Same project security contract as SecureConnectionStore.
internal class SessionCache(context: Context) : SessionSnapshots {

    private val preferences = EncryptedSharedPreferences.create(
        context,
        "hermes_session_cache",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Sessions and the wall-clock time they were fetched, or null if nothing is cached. */
    override fun read(): SessionSnapshots.Snapshot? {
        val raw = preferences.getString(KEY_SESSIONS, null) ?: return null
        val at = preferences.getFloat(KEY_CACHED_AT, 0f).toDouble()
        if (at <= 0.0) return null
        // A cache that no longer decodes is worse than no cache: it would render as an empty
        // list and read as "you have no sessions". Treat it as absent.
        val sessions = runCatching { json.decodeFromString<List<Session>>(raw) }.getOrNull()
            ?: return null
        return SessionSnapshots.Snapshot(sessions, at)
    }

    override fun write(sessions: List<Session>, atEpochSeconds: Double) {
        runCatching {
            preferences.edit()
                .putString(KEY_SESSIONS, json.encodeToString(sessions))
                .putFloat(KEY_CACHED_AT, atEpochSeconds.toFloat())
                .commit()
        }
    }

    /** Wiped on disconnect: a rotated key must not leave the previous transcripts readable. */
    override fun clear() {
        runCatching { preferences.edit().clear().commit() }
    }

    private companion object {
        const val KEY_SESSIONS = "sessions"
        const val KEY_CACHED_AT = "cached_at"
    }
}
