package org.praxisog.hermes

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class ServerProfile(val id: String, val name: String, val url: String)
internal class ServerConnection(val profile: ServerProfile, val key: String) {
    override fun toString() = "ServerConnection(profile=${profile.id}, key=redacted)"
}

/** Metadata and keys share the existing encrypted boundary. No secrets enter saved UI state. */
@Suppress("DEPRECATION")
internal class SecureConnectionStore internal constructor(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(EncryptedSharedPreferences.create(
        context, "hermes_connection",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    ))

    init {
        if (!preferences.contains("profiles")) {
            val url = preferences.getString("base_url", "").orEmpty()
            val key = preferences.getString("api_key", "").orEmpty()
            if (url.isNotBlank() && key.isNotBlank()) {
                val record = JSONObject().put("id", LEGACY).put("name", "Existing server")
                    .put("url", url).put("key", key)
                check(preferences.edit().putString("profiles", JSONArray().put(record).toString())
                    .putString("active_profile", LEGACY).remove("base_url").remove("api_key").commit())
            }
        }
    }

    private fun records(): List<JSONObject> = JSONArray(preferences.getString("profiles", "[]"))
        .let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
    private fun profile(value: JSONObject) = ServerProfile(value.getString("id"), value.getString("name"), value.getString("url"))
    fun profiles(): List<ServerProfile> = records().map(::profile)
    fun activeId(): String? = preferences.getString("active_profile", null)
    fun connection(id: String): ServerConnection? = records().firstOrNull { it.getString("id") == id }
        ?.let { ServerConnection(profile(it), it.getString("key")) }
    fun active(): ServerConnection? = activeId()?.let(::connection)
    fun loadUrl(): String = active()?.profile?.url.orEmpty()
    fun loadKey(): String = active()?.key.orEmpty()

    fun add(name: String, url: String, key: String): ServerProfile {
        require(name.isNotBlank() && url.isNotBlank() && key.isNotBlank())
        val value = JSONObject().put("id", UUID.randomUUID().toString()).put("name", name.trim())
            .put("url", url).put("key", key)
        check(preferences.edit().putString("profiles", JSONArray(records() + value).toString()).commit())
        return profile(value)
    }

    fun activate(id: String?): Boolean {
        require(id == null || connection(id) != null)
        return preferences.edit().putString("active_profile", id).commit()
    }

    fun rename(id: String, name: String): Boolean {
        require(name.isNotBlank())
        val values = records().onEach { if (it.getString("id") == id) it.put("name", name.trim()) }
        return preferences.edit().putString("profiles", JSONArray(values).toString()).commit()
    }

    fun remove(id: String): Boolean {
        val values = records().filterNot { it.getString("id") == id }
        val edit = preferences.edit().putString("profiles", JSONArray(values).toString())
        if (activeId() == id) edit.remove("active_profile")
        return edit.commit()
    }

    /** Removes all saved connection credentials, including obsolete single-server fields. */
    fun clear(): Boolean = preferences.edit().clear().commit()

    /** Compatibility helper for storage instrumentation; new UI uses add + explicit activate. */
    fun save(url: String, key: String): Boolean = activate(add("Server", url, key).id)

    companion object { const val LEGACY = "legacy" }
}
