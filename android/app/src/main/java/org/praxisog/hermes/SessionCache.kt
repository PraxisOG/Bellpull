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
internal class SessionCache(context: Context, profileId: String = SecureConnectionStore.LEGACY) : SessionSnapshots, RunJournal, ReplyCursorStore {
    private val profileFiles = java.io.File(context.filesDir, "bellpull-files/$profileId")
    private val sharedFiles = java.io.File(context.cacheDir, "bellpull-shared/$profileId")
    @Volatile private var closed = false
    @Synchronized fun close() { closed = true }

    private val preferences = EncryptedSharedPreferences.create(
        context,
        fileName(profileId),
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
        if (closed) return
        runCatching {
            preferences.edit()
                .putString(KEY_SESSIONS, json.encodeToString(sessions))
                .putFloat(KEY_CACHED_AT, atEpochSeconds.toFloat())
                .commit()
        }
    }

    /** Phone-owned drafts share the existing encrypted storage boundary, never server sync. */
    fun readDrafts(): Map<String, androidx.compose.ui.text.input.TextFieldValue> = runCatching {
        preferences.all.filterKeys { it.startsWith("draft:") }.mapNotNull { (key, raw) ->
            runCatching {
                val value = org.json.JSONObject(raw as String)
                val text = value.getString("text")
                key.removePrefix("draft:") to androidx.compose.ui.text.input.TextFieldValue(text,
                    androidx.compose.ui.text.TextRange(value.optInt("start").coerceIn(0, text.length),
                        value.optInt("end").coerceIn(0, text.length)))
            }.getOrNull()
        }.toMap()
    }.getOrDefault(emptyMap())

    fun writeDraft(id: String, value: androidx.compose.ui.text.input.TextFieldValue, revision: Long = 0) {
        if (closed) return
        val edit = preferences.edit()
        edit.putLong("draft_revision:$id", revision)
        if (value.text.isEmpty()) edit.remove("draft:$id")
        else edit.putString("draft:$id", org.json.JSONObject().put("text", value.text)
            .put("start", value.selection.start).put("end", value.selection.end).toString())
        edit.apply()
    }

    fun lastSession(): String? = preferences.getString("last_session", null)
    fun sessionSource(): String? {
        // Retire the old API-only default once, including profiles created before 0.3.5.
        // Explicit filters chosen in this version still survive restarts per profile.
        if (!preferences.contains("session_source_v2")) {
            val old = preferences.getString("session_source", null)
            preferences.edit().putString("session_source_v2", old?.takeUnless { it == "api_server" } ?: "*").commit()
        }
        return preferences.getString("session_source_v2", "*").takeUnless { it == "*" }
    }
    fun rememberSessionSource(source: String?) { if (!closed) preferences.edit().putString("session_source_v2", source ?: "*").apply() }
    fun rememberSession(id: String?) { if (!closed) preferences.edit().putString("last_session", id).apply() }

    fun readReceipts(): Map<String, ConversationReceipt> = runCatching {
        preferences.all.filterKeys { it.startsWith("read:") }.mapNotNull { (key, raw) ->
            runCatching {
                val fields = json.decodeFromString<Map<String, Int>>(raw as String)
                key.removePrefix("read:") to ConversationReceipt(fields.getValue("readThrough"), fields.getValue("unreadReply"))
            }.getOrNull()
        }.toMap()
    }.getOrDefault(emptyMap())

    fun writeReceipt(id: String, receipt: ConversationReceipt) {
        if (closed) return
        preferences.edit().putString("read:$id", json.encodeToString(mapOf(
            "readThrough" to receipt.readThrough, "unreadReply" to receipt.unreadReply))).apply()
    }

    fun draftRevision(id: String): Long = preferences.getLong("draft_revision:$id", 1L)
    fun readDraftRevisions(): Map<String, Long> = preferences.all.filterKeys { it.startsWith("draft_revision:") }
        .mapNotNull { (key, value) -> (value as? Long)?.let { key.removePrefix("draft_revision:") to it } }.toMap()
    fun readCapabilities(): String? = preferences.getString("capabilities", null)
    fun writeCapabilities(raw: String) { if (!closed) preferences.edit().putString("capabilities", raw).apply() }

    fun defaultModel(): org.praxisog.hermes.model.ModelSelection? = preferences.getString("default_model", null)?.let {
        runCatching { json.decodeFromString<org.praxisog.hermes.model.ModelSelection>(it) }.getOrNull()
    }
    fun writeDefaultModel(value: org.praxisog.hermes.model.ModelSelection?): Boolean = !closed && preferences.edit()
        .putString("default_model", value?.let { json.encodeToString(it) }).commit()
    fun draftFiles(): List<org.praxisog.hermes.model.DraftFile> = preferences.getString("draft_files", null)?.let {
        runCatching { json.decodeFromString<List<org.praxisog.hermes.model.DraftFile>>(it) }.getOrNull()
    }.orEmpty()
    fun writeDraftFiles(values: List<org.praxisog.hermes.model.DraftFile>): Boolean = !closed && preferences.edit()
        .putString("draft_files", json.encodeToString(values)).commit()

    override fun readRuns(): List<org.praxisog.hermes.model.RunSubmission> = preferences.all
        .filterKeys { it.startsWith("run:") }.values.mapNotNull { raw ->
            runCatching { json.decodeFromString<org.praxisog.hermes.model.RunSubmission>(raw as String) }.getOrNull()
        }

    @Synchronized override fun writeRun(value: org.praxisog.hermes.model.RunSubmission): Boolean = !closed && preferences.edit()
        .putString("run:${value.requestId}", json.encodeToString(value)).commit()

    @Synchronized override fun pruneRuns(beforeEpochSeconds: Double) {
        if (closed) return
        val expired = readRuns().filter { canPruneRun(it, beforeEpochSeconds) }
        if (expired.isEmpty()) return
        val edit = preferences.edit()
        expired.forEach { edit.remove("run:${it.requestId}") }
        edit.commit()
    }

    override fun readCursor(id: String): ReplyCursor? = preferences.getString("reply_cursor:$id", null)?.let { raw ->
        runCatching { org.json.JSONObject(raw).let { ReplyCursor(it.getDouble("activity"), it.getInt("count"), it.getInt("replyId")) } }.getOrNull()
    }
    @Synchronized override fun writeCursor(id: String, value: ReplyCursor) {
        if (closed) return
        check(preferences.edit().putString("reply_cursor:$id", org.json.JSONObject()
            .put("activity", value.activity).put("count", value.count).put("replyId", value.replyId).toString()).commit())
    }
    fun notifiedReply(id: String): Int = preferences.getInt("notified_reply:$id", 0)
    fun rememberNotifiedReply(id: String, messageId: Int) { if (!closed) preferences.edit().putInt("notified_reply:$id", messageId).apply() }
    fun wasNotified(identity: String): Boolean = preferences.getBoolean("notified:$identity", false)
    fun rememberNotified(identity: String) { if (!closed) preferences.edit().putBoolean("notified:$identity", true).apply() }

    /** Profile removal is explicit; ordinary switching/sign-out retains its isolated cache. */
    override fun clear() {
        check(clearChecked())
    }

    fun clearChecked(): Boolean = profileFiles.deleteRecursively() && sharedFiles.deleteRecursively() &&
        preferences.edit().clear().commit()

    companion object {
        fun fileName(profileId: String): String {
            require(profileId.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
            return if (profileId == SecureConnectionStore.LEGACY) "hermes_session_cache"
                else "hermes_session_cache_$profileId"
        }
        const val KEY_SESSIONS = "sessions"
        const val KEY_CACHED_AT = "cached_at"
    }
}
