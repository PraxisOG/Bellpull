package org.praxisog.hermes

import java.security.MessageDigest
import kotlinx.serialization.json.Json

internal data class VoicePromptPresentation(val details: String, val body: String, val label: String = "Bellpull voice enabled")

internal const val MAX_INSTRUCTION_LENGTH = 2048
internal const val MOBILE_PRESET = "Keep replies dense and scannable on a narrow screen. Prefer short sentences and flat lists. When a chart or diagram would be clearer, publish it as an image."

private const val VOICE_HEADER = "[Bellpull voice instruction: "
private const val TYPED_HEADER = "[Bellpull typed instruction: "

private fun withInstruction(prefix: String, instruction: String, words: String): String {
    if (words.isBlank() || instruction.isBlank()) return words
    require(instruction.length <= MAX_INSTRUCTION_LENGTH)
    return "$prefix${Json.encodeToString(instruction)}]\n\n$words"
}

internal fun VoiceConfiguration.voicePrompt(words: String): String = when {
    !concise || voiceInstruction.isBlank() || words.isBlank() -> words
    voiceInstruction == VOICE_PRESET -> "$VOICE_PRESET\n\n$words"
    else -> withInstruction(VOICE_HEADER, voiceInstruction, words)
}

internal fun VoiceConfiguration.typedPrompt(words: String): String =
    if (typedInstructionEnabled) withInstruction(TYPED_HEADER, typedInstruction, words) else words

/** Strip only a recognized Bellpull voice wrapper; ordinary messages pass through verbatim. */
internal fun voicePromptUserText(text: String): String {
    val body = org.praxisog.hermes.model.splitCanvasContext(splitPhoneNotificationContext(text).first).first
    return voicePromptPresentation(body)?.body ?: body
}

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
    val prefix = when {
        text.startsWith(VOICE_HEADER, offset) -> VOICE_HEADER
        text.startsWith(TYPED_HEADER, offset) -> TYPED_HEADER
        else -> null
    }
    if (prefix != null) {
        val end = text.indexOf('\n', offset)
        if (end < 0 || end - offset > 6 * MAX_INSTRUCTION_LENGTH + prefix.length + 4) return null
        val line = text.substring(offset, end).removeSuffix("\r")
        if (!line.endsWith(']')) return null
        val encoded = line.substring(prefix.length, line.length - 1)
        val instruction = runCatching { Json.decodeFromString<String>(encoded) }.getOrNull() ?: return null
        if (instruction.isBlank() || instruction.length > MAX_INSTRUCTION_LENGTH) return null
        val separator = if (text.startsWith("\n\r\n", end)) 3 else if (text.startsWith("\n\n", end)) 2 else return null
        return VoicePromptPresentation(text.substring(0, offset) + instruction, text.substring(end + separator),
            if (prefix == TYPED_HEADER) "Typed message instruction" else "Bellpull voice enabled")
    }
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
