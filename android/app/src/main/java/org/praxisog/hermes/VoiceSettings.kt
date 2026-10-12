package org.praxisog.hermes

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

internal enum class VoiceRecognition { LOCAL, PRIVATE_SERVER }
internal class VoiceConfiguration(val endpoint: String = "", val key: String = "", val model: String = "whisper-1",
    val rate: Float = 1.05f, val pauseMs: Int = 1600, val concise: Boolean = true,
    val recognition: VoiceRecognition = if (endpoint.isBlank()) VoiceRecognition.LOCAL else VoiceRecognition.PRIVATE_SERVER) {
    override fun toString() = "VoiceConfiguration(credentials=redacted)"
}

/** Recheck at each permission/service boundary; a model can be deleted while a dialog is open. */
internal fun VoiceConfiguration.startProblem(localProblem: () -> String?): String? =
    when (recognition) {
        VoiceRecognition.LOCAL -> localProblem()
        VoiceRecognition.PRIVATE_SERVER -> if (endpoint.isBlank())
            "Configure your selected private transcription service first." else null
    }

@Suppress("DEPRECATION")
internal class VoiceSettings(private val prefs: SharedPreferences) {
    constructor(context: Context, profileId: String) : this(EncryptedSharedPreferences.create(context, "bellpull_voice_$profileId",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM))
    fun read() = VoiceConfiguration(prefs.getString("endpoint", "").orEmpty(), prefs.getString("key", "").orEmpty(),
        prefs.getString("model", "whisper-1").orEmpty(), prefs.getFloat("rate", 1.05f), prefs.getInt("pause", 1600), prefs.getBoolean("concise", true),
        prefs.getString("recognition", null)?.let(VoiceRecognition::valueOf)
            ?: if (prefs.getString("endpoint", "").isNullOrBlank()) VoiceRecognition.LOCAL else VoiceRecognition.PRIVATE_SERVER)
    fun save(value: VoiceConfiguration) {
        if (value.recognition == VoiceRecognition.PRIVATE_SERVER) {
            org.praxisog.hermes.core.PrivateTranscription.validateEndpoint(value.endpoint)
            require(value.model.isNotBlank() && value.model.length <= 200)
        }
        check(prefs.edit().putString("endpoint", value.endpoint.trim()).putString("key", value.key.trim()).putString("model", value.model.trim())
            .putString("recognition", value.recognition.name).putFloat("rate", value.rate.coerceIn(.7f, 1.5f))
            .putInt("pause", value.pauseMs.coerceIn(1000, 3500)).putBoolean("concise", value.concise).commit())
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
