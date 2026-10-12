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
    private val lead = ArrayDeque<ByteArray>()
    private val recording = ByteArrayOutputStream()
    var active = false
        private set
    data class Frame(val began: Boolean = false, val complete: ByteArray? = null)
    fun accept(pcm: ShortArray, count: Int, hold: Boolean = false): Frame {
        if (count <= 0) return Frame()
        val ms = count * 1000 / sampleRate
        val rms = sqrt((0 until count).sumOf { pcm[it].toDouble() * pcm[it] } / count)
        val speech = rms > maxOf(450.0, noise * 3.0)
        val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(count) { putShort(pcm[it]) } }.array()
        var began = false
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
            if ((!hold && quietMs >= silenceMs) || totalMs >= 55_000) return Frame(complete = finish())
        }
        return Frame(began = began)
    }
    fun finish(): ByteArray? {
        val result = if (active && voicedMs >= 300) wav(recording.toByteArray()) else null
        reset(); return result
    }
    fun reset() { active = false; loudMs = 0; quietMs = 0; totalMs = 0; voicedMs = 0; lead.clear(); recording.reset() }
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
        var start = 0
        var fence = false
        var i = 0
        while (i < tail.length) {
            if (tail.startsWith("```", i)) { fence = !fence; i += 3; continue }
            val boundary = !fence && (tail[i] in ".!?\n") && (i + 1 < tail.length && tail[i + 1].isWhitespace())
            if (boundary || (!fence && i - start >= 260 && tail[i].isWhitespace())) {
                speechText(tail.substring(start, i + 1)).takeIf { it.isNotBlank() }?.let(result::add)
                start = i + 1
            }
            i++
        }
        if (final) { speechText(tail.substring(start)).takeIf { it.isNotBlank() }?.let(result::add); start = tail.length }
        consumed += start
        return result
    }
    fun reset() { consumed = 0; previous = "" }
}
internal fun speechText(value: String): String = value
    .replace(Regex("```[\\s\\S]*?(?:```|$)"), " Code is available in the chat. ")
    .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
    .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
    .replace(Regex("https?://\\S+"), "link")
    .replace(Regex("(?m)^\\s*[#>*-]+\\s*"), "")
    .replace("**", "").replace("`", "").trim()
internal fun likelyEcho(transcript: String, spoken: String): Boolean {
    fun words(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
    val heard = words(transcript); val said = words(spoken)
    if (heard.isEmpty()) return true
    return heard.size >= 3 && said.windowed(heard.size).any { it == heard }
}

internal const val VOICE_PRESET = "[Bellpull voice turn: give at most one paragraph, no more than 180 words, in friendly spoken language. Keep reasoning minimal and spend as little time thinking as this request permits; still verify facts when needed. Before every tool call or web search, briefly tell the user what you are doing and why. Do not include lists, long explanations or hidden reasoning in the answer.]"

/** Bound spoken answers even when a provider ignores the visible voice preset. Statuses are separate. */
internal class VoiceSpeechBudget(private val limit: Int = 180) {
    private var words = 0
    fun take(text: String): String? {
        val remaining = limit - words
        if (remaining <= 0) return null
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        words += tokens.size.coerceAtMost(remaining)
        return tokens.take(remaining).joinToString(" ").takeIf { it.isNotBlank() }
    }
    fun reset() { words = 0 }
}

internal data class VoiceActivity(val id: String, val text: String)
internal fun voiceActivities(parts: List<LivePart>): List<VoiceActivity> {
    var commentary = ""
    return buildList {
        parts.forEach { part -> when (part) {
            is LivePart.Prose -> if (part.thinking) add(VoiceActivity("thinking:${part.id}", "Thinking."))
                else commentary = speechText(part.text).replace(Regex("\\s+"), " ").trim()
                    .split(' ').take(35).joinToString(" ")
            is LivePart.Tools -> part.rows.forEach { row ->
                val purpose = commentary.takeIf { it.isNotBlank() } ?: when (row.name) {
                    "web_search" -> "Searching the web for this request."
                    "web_extract", "web_fetch" -> "Reading a webpage for this request."
                    "read_file", "file_read" -> "Reading a file for this request."
                    "terminal" -> "Running a command for this request."
                    else -> "Using this tool for your request; no specific purpose was provided."
                }
                val label = if (row.name == "web_search") "Web search" else "Tool call. ${toolDisplayName(row.name)}"
                add(VoiceActivity("tool:${row.id}", "$label. $purpose"))
            }
        } }
    }
}
