package org.praxisog.hermes

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** 16 kHz mono PCM; bounded in-memory capture, never written to shared storage. */
internal class VoiceGate(private val silenceMs: Int = 1600, private val sampleRate: Int = 16000) {
    private var noise = 180.0
    private var loudMs = 0
    private var quietMs = 0
    private var totalMs = 0
    private var voicedMs = 0
    private var manualRecording = false
    private var manualSignal = false
    private val lead = ArrayDeque<ByteArray>()
    private val recording = ByteArrayOutputStream()
    var active = false
        private set
    data class Frame(val began: Boolean = false, val complete: ByteArray? = null, val audio: ByteArray? = null)
    fun accept(pcm: ShortArray, count: Int, hold: Boolean = false, manual: Boolean = false): Frame {
        if (count <= 0) return Frame()
        require(count <= pcm.size)
        val ms = count * 1000 / sampleRate
        val rms = sqrt((0 until count).sumOf { pcm[it].toDouble() * pcm[it] } / count)
        val speech = rms > maxOf(450.0, noise * 3.0)
        val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(count) { putShort(pcm[it]) } }.array()
        var began = false
        // A held microphone is an explicit recording request. Do not require 200 ms
        // of continuously loud speech before keeping it; soft words were lost here
        // before either the local recognizer or the server ever received a WAV.
        if (manual && !active) {
            active = true; manualRecording = true; began = true
            lead.forEach(recording::write); lead.clear()
        }
        if (manualRecording && rms >= 32.0) manualSignal = true
        if (!active) {
            lead.addLast(bytes); while (lead.size > 12) lead.removeFirst()
            if (speech) loudMs += ms else { loudMs = 0; noise = .98 * noise + .02 * rms.coerceAtMost(600.0) }
            if (loudMs >= 200) {
                active = true; began = true
                lead.forEach(recording::write); lead.clear()
                totalMs = loudMs; voicedMs = loudMs
            }
        } else {
            recording.write(bytes); totalMs += ms
            if (speech) { voicedMs += ms; quietMs = 0 } else quietMs += ms
            if ((!hold && quietMs >= silenceMs) || totalMs >= 55_000) return Frame(complete = finish(), audio = bytes)
        }
        return Frame(began = began, audio = if (began) recording.toByteArray() else if (active) bytes else null)
    }
    fun finish(): ByteArray? {
        val enoughAudio = if (manualRecording) totalMs >= 160 && manualSignal else voicedMs >= 300
        val result = if (active && enoughAudio) wav(recording.toByteArray()) else null
        reset(); return result
    }
    fun reset() {
        active = false; loudMs = 0; quietMs = 0; totalMs = 0; voicedMs = 0
        manualRecording = false; manualSignal = false; lead.clear(); recording.reset()
    }
}
internal fun wav(pcm: ByteArray): ByteArray = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
    put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray()); putInt(16)
    putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
    put("data".toByteArray()); putInt(pcm.size); put(pcm)
}.array()

/** Emit coherent sentences during generation; hold incomplete words and fenced code. */
internal class VoiceSentences {
    private var consumed = 0
    private var previous = ""
    fun take(text: String, final: Boolean = false): List<String> {
        if (!text.startsWith(previous)) {
            if (previous.startsWith(text)) return emptyList() // a shorter replay cannot restart speech
            val exact = previous.lastIndexOf(text).takeIf { text.isNotEmpty() && it >= 0 }
            val overlap = if (exact != null) previous.length - exact else
                (minOf(previous.length, text.length) downTo 12).firstOrNull { previous.endsWith(text.take(it)) } ?: 0
            val offset = previous.length - overlap
            consumed = (consumed - offset).coerceIn(0, text.length)
        }
        previous = text
        if (text.length < consumed) return emptyList()
        val tail = text.substring(consumed)
        val result = mutableListOf<String>()
        fun emit(value: String) {
            val spoken = speechText(value)
            if (spoken.isNotBlank()) result.addAll(speechChunks(spoken, 260).map(String::trim).filter(String::isNotBlank))
        }
        var start = 0
        var fence = false
        var i = 0
        while (i < tail.length) {
            if (tail.startsWith("```", i)) { fence = !fence; i += 3; continue }
            val boundary = if (fence) null else speechSentenceEnd(tail, i, final)
            val clause = !fence && i - start >= 120 && tail[i] in ",;:" &&
                i + 1 < tail.length && tail[i + 1].isWhitespace()
            if (boundary != null || clause || (!fence && i - start >= 260 && tail[i].isWhitespace())) {
                val end = boundary ?: (i + 1)
                emit(tail.substring(start, end))
                start = end; i = end - 1
            }
            i++
        }
        if (final) { emit(tail.substring(start)); start = tail.length }
        consumed += start
        return result
    }
    fun reset() { consumed = 0; previous = "" }
}

/** Wait for a separator (or final text), including closing quotes in the sentence.
 * Conservative English abbreviation handling keeps names and decimals together.
 */
internal fun speechSentenceEnd(text: String, index: Int, final: Boolean): Int? {
    val c = text[index]
    if (c == '\n') return index + 1
    if (c !in ".!?") return null
    if (c == '.') {
        if (index > 0 && text[index - 1].isDigit() && text.getOrNull(index + 1)?.isDigit() == true) return null
        val word = text.substring(0, index).takeLastWhile { it.isLetter() || it == '.' }
        if (word.lowercase() in speechAbbreviations || word.length == 1 && word[0].isUpperCase() ||
            speechInitials.matches(word)) return null
    }
    var end = index + 1
    while (end < text.length && text[end] in ".!?\"'”’)]}") end++
    return end.takeIf { end < text.length && text[end].isWhitespace() || end == text.length && final }
}
private val speechAbbreviations = setOf("mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "e.g", "i.e")
private val speechInitials = Regex("(?:[A-Za-z]\\.)+[A-Za-z]")
internal fun speechText(value: String): String = value
    .replace(Regex("```[\\s\\S]*?(?:```|$)"), " Code is available in the chat. ")
    .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
    .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
    .replace(Regex("https?://\\S+"), "link")
    .replace(Regex("(?m)^\\s*[#>*-]+\\s*"), "")
    .replace("**", "").replace("`", "").trim()
/** Short echoes require a recently audible whole phrase; common user commands stay usable. */
internal fun likelyEcho(transcript: String, spoken: String, shortPhrases: List<String> = emptyList()): Boolean {
    fun words(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .split(Regex("\\s+")).filter { it.isNotBlank() }
    val heard = words(transcript); val said = words(spoken)
    if (heard.isEmpty()) return true
    if (heard.size >= 3) return said.windowed(heard.size).any { it == heard }
    if (heard.joinToString(" ") in setOf("yes", "no", "stop", "cancel", "pause")) return false
    return shortPhrases.any { words(it) == heard }
}

internal const val VOICE_PRESET = "[Bellpull voice turn: use friendly spoken language. Prefer a brief answer unless the user requests detail, research or a long narration; honor their requested depth and length. Do not include hidden reasoning. The complete answer will be read aloud.]"

internal enum class VoiceActivityKind { TOOL, WEB_SEARCH, THINKING, WORKING }
internal data class VoiceAnnouncements(val tools: Boolean = true, val webSearch: Boolean = true,
    val thinking: Boolean = true, val working: Boolean = true) {
    fun enabled(kind: VoiceActivityKind) = when (kind) {
        VoiceActivityKind.TOOL -> tools
        VoiceActivityKind.WEB_SEARCH -> webSearch
        VoiceActivityKind.THINKING -> thinking
        VoiceActivityKind.WORKING -> working
    }
}
internal data class VoiceActivity(val id: String, val text: String, val kind: VoiceActivityKind = VoiceActivityKind.TOOL)
/** Prose belongs to the answer lane. Tool labels never borrow or repeat preceding prose. */
internal fun voiceActivities(parts: List<LivePart>): List<VoiceActivity> = buildList {
    parts.forEach { part -> when (part) {
        is LivePart.Prose -> if (part.thinking) add(VoiceActivity("thinking:${part.id}", "Thinking.", VoiceActivityKind.THINKING))
        is LivePart.Tools -> part.rows.forEach { row ->
            val search = row.name == "web_search"
            add(VoiceActivity("tool:${row.id}", if (search) "Web search." else "Tool call. ${toolDisplayName(row.name)}.",
                if (search) VoiceActivityKind.WEB_SEARCH else VoiceActivityKind.TOOL))
        }
    } }
}
