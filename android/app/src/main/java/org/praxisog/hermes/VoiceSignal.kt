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

internal const val VOICE_PRESET = "[Bellpull voice turn: answer in concise, friendly spoken language. Use tools and reasoning economically, while verifying facts when needed. Briefly describe meaningful tool work before doing it. Keep necessary detail available in the written response.]"
