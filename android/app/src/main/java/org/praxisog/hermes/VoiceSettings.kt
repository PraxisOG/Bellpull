package org.praxisog.hermes

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

internal enum class VoiceRecognition { LOCAL, PRIVATE_SERVER }
internal data class VoiceConfiguration(val endpoint: String = "", val key: String = "", val model: String = "whisper-1",
    val rate: Float = 1.05f, val pauseMs: Int = 1600, val concise: Boolean = true,
    val recognition: VoiceRecognition = if (endpoint.isBlank()) VoiceRecognition.LOCAL else VoiceRecognition.PRIVATE_SERVER,
    val output: VoiceOutputConfiguration = VoiceOutputConfiguration(),
    val announcements: VoiceAnnouncements = VoiceAnnouncements(),
    val transcribeViaServer: Boolean = false) {
    override fun toString() = "VoiceConfiguration(credentials=redacted)"
}

/** Recheck at each permission/service boundary; a model can be deleted while a dialog is open. */
internal fun VoiceConfiguration.startProblem(localProblem: () -> String?): String? =
    if (transcribeViaServer) null else when (recognition) {
        VoiceRecognition.LOCAL -> localProblem()
        VoiceRecognition.PRIVATE_SERVER -> "Choose Transcribe on server or on-device recognition in Voice input settings."
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
            ?: if (prefs.getString("endpoint", "").isNullOrBlank()) VoiceRecognition.LOCAL else VoiceRecognition.PRIVATE_SERVER,
        VoiceOutputConfiguration(
            engine = VoiceOutputEngine.decode(prefs.getString("output", null)),
            speaker = prefs.getString("speaker", "ryan").orEmpty(), language = prefs.getString("language", "english").orEmpty(),
            temperature = prefs.getFloat("temperature", .9f), topP = prefs.getFloat("top_p", 1f), topK = prefs.getInt("top_k", 50),
            repetition = prefs.getFloat("repetition", 1.05f), seed = prefs.getLong("seed", -1), volume = prefs.getFloat("volume", 1f),
            pocketSteps = prefs.getInt("pocket_steps", 5), pocketTemperature = prefs.getFloat("pocket_temperature", .7f),
            pocketChunkFrames = prefs.getInt("pocket_chunk_frames", 15)),
        VoiceAnnouncements(prefs.getBoolean("announce_tools", true), prefs.getBoolean("announce_web_search", true),
            prefs.getBoolean("announce_thinking", true), prefs.getBoolean("announce_working", true)),
        prefs.getBoolean("stt_server", false))
    fun save(value: VoiceConfiguration) {
        value.output.validated()
        if (!value.transcribeViaServer && value.recognition == VoiceRecognition.PRIVATE_SERVER) {
            org.praxisog.hermes.core.PrivateTranscription.validateEndpoint(value.endpoint)
            require(value.model.isNotBlank() && value.model.length <= 200)
        }
        check(prefs.edit().putString("endpoint", value.endpoint.trim()).putString("key", value.key.trim()).putString("model", value.model.trim())
            .putString("output", value.output.engine.id).putString("speaker", value.output.speaker).putString("language", value.output.language)
            .putFloat("temperature", value.output.temperature).putFloat("top_p", value.output.topP).putInt("top_k", value.output.topK)
            .putFloat("repetition", value.output.repetition).putLong("seed", value.output.seed).putFloat("volume", value.output.volume)
            .putInt("pocket_steps", value.output.pocketSteps).putFloat("pocket_temperature", value.output.pocketTemperature)
            .putInt("pocket_chunk_frames", value.output.pocketChunkFrames)
            .putBoolean("stt_server", value.transcribeViaServer)
            .putString("recognition", value.recognition.name).putFloat("rate", value.rate.coerceIn(.7f, 1.5f))
            .putInt("pause", value.pauseMs.coerceIn(1000, 3500)).putBoolean("concise", value.concise)
            .putBoolean("announce_tools", value.announcements.tools).putBoolean("announce_web_search", value.announcements.webSearch)
            .putBoolean("announce_thinking", value.announcements.thinking).putBoolean("announce_working", value.announcements.working).commit())
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
