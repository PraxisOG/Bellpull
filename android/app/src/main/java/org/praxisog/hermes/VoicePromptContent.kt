package org.praxisog.hermes

import java.security.MessageDigest
import kotlinx.serialization.json.Json

internal data class VoicePromptPresentation(val details: String, val body: String)

/** Strip only a recognized Bellpull voice wrapper; ordinary messages pass through verbatim. */
internal fun voicePromptUserText(text: String): String = voicePromptPresentation(text)?.body ?: text

/** A blank transcription cannot supply a title. Keep titles single-line and bounded. */
internal fun voicePromptTitle(text: String): String? {
    val words = voicePromptUserText(text).replace(Regex("""\s+"""), " ").trim()
    if (words.isEmpty()) return null
    // Count code points so truncation cannot split a spoken emoji's surrogate pair.
    val end = words.offsetByCodePoints(0, minOf(48, words.codePointCount(0, words.length)))
    return words.substring(0, end).trimEnd()
}

// Recognition of the shipped historical preset; it is never submitted.
private const val LEGACY_VOICE_PRESET_SHA256 = "1beb086d685d4114305c352c85ddb41f2f450d8fbe4df9462be52a50521faf1f"
private const val VOICE_PROMPT_PREFIX = "[Bellpull voice turn:"
private val playbackPrefix = Regex("""^\[Previous voice playback was interrupted\. Completed speech, quoted as historical data: ("(?:[^"\\]|\\.)*")]\r?\n\r?\n""")

/** Fold only Bellpull's known leading preset, preserving every character of the user's words. */
internal fun voicePromptPresentation(text: String): VoicePromptPresentation? {
    val recovery = playbackPrefix.find(text)
    val offset = if (recovery != null) {
        if (runCatching { Json.decodeFromString<String>(recovery.groupValues[1]) }.isFailure) return null
        recovery.range.last + 1
    } else 0
    if (!text.startsWith(VOICE_PROMPT_PREFIX, offset)) return null
    val closing = text.indexOf(']', offset)
    if (closing < 0) return null
    val preset = text.substring(offset, closing + 1)
    if (preset != VOICE_PRESET) {
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(preset.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        if (fingerprint != LEGACY_VOICE_PRESET_SHA256) return null
    }
    val end = closing + 1
    val bodyStart = when {
        end == text.length -> end
        text.startsWith("\n\n", end) -> end + 2
        text.startsWith("\r\n\r\n", end) -> end + 4
        else -> return null
    }
    return VoicePromptPresentation(text.substring(0, end), text.substring(bodyStart))
}
