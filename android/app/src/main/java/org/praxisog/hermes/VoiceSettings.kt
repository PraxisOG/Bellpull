package org.praxisog.hermes

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

internal class VoiceConfiguration(val endpoint: String = "", val key: String = "", val model: String = "whisper-1",
    val rate: Float = 1.05f, val pauseMs: Int = 1600, val concise: Boolean = true) {
    override fun toString() = "VoiceConfiguration(credentials=redacted)"
}
@Suppress("DEPRECATION")
internal class VoiceSettings(context: Context, profileId: String) {
    private val prefs = EncryptedSharedPreferences.create(context, "bellpull_voice_$profileId",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    fun read() = VoiceConfiguration(prefs.getString("endpoint", "").orEmpty(), prefs.getString("key", "").orEmpty(),
        prefs.getString("model", "whisper-1").orEmpty(), prefs.getFloat("rate", 1.05f), prefs.getInt("pause", 1600), prefs.getBoolean("concise", true))
    fun save(value: VoiceConfiguration) {
        org.praxisog.hermes.core.PrivateTranscription.validateEndpoint(value.endpoint)
        require(value.model.isNotBlank() && value.model.length <= 200)
        check(prefs.edit().putString("endpoint", value.endpoint.trim()).putString("key", value.key.trim()).putString("model", value.model.trim())
            .putFloat("rate", value.rate.coerceIn(.7f, 1.5f)).putInt("pause", value.pauseMs.coerceIn(1000, 3500)).putBoolean("concise", value.concise).commit())
    }
    fun pending(sessionId: String): String = prefs.getString("pending_session", null)?.takeIf { it == sessionId }
        ?.let { prefs.getString("pending_text", "") }.orEmpty()
    fun pendingRequest(sessionId: String): String? = prefs.getString("pending_session", null)?.takeIf { it == sessionId }
        ?.let { prefs.getString("pending_request", null) }
    fun saveRequest(requestId: String?) { check(prefs.edit().putString("pending_request", requestId).commit()) }
    fun saveTranscript(sessionId: String, text: String) {
        val edit = prefs.edit().putString("pending_session", sessionId).putString("pending_text", text.take(12000))
        edit.remove("pending_request")
        check(edit.commit())
    }
    fun playback(sessionId: String): String? = prefs.getString("playback_session", null)?.takeIf { it == sessionId }
        ?.let { prefs.getString("playback_text", null) }
    fun savePlayback(sessionId: String, text: String, interrupted: Boolean) {
        check(prefs.edit().putString("playback_session", sessionId).putString("playback_text", text.takeLast(1200))
            .putBoolean("playback_interrupted", interrupted).commit())
    }
    fun interrupted(sessionId: String) = prefs.getString("playback_session", null) == sessionId && prefs.getBoolean("playback_interrupted", false)
    fun clear(): Boolean = prefs.edit().clear().commit()
}
