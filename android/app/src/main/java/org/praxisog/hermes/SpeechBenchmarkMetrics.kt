package org.praxisog.hermes

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.Locale

/** Direct recognizer input; never routes a benchmark through a speaker or microphone. */
internal data class SpeechAudio(val samples: FloatArray, val sampleRate: Int = 16000, val sourceSha256: String? = null) {
    val seconds get() = samples.size.toDouble() / sampleRate
    companion object {
        const val MAX_BYTES = 4_000_000
        fun readWav(bytes: ByteArray): SpeechAudio {
            require(bytes.size in 44..MAX_BYTES) { "Use a WAV shorter than one minute (16 kHz mono PCM16)." }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun tag(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
            require(tag(0) == "RIFF" && tag(8) == "WAVE") { "Choose a RIFF WAV file." }
            val declared = buffer.getInt(4).toLong() and 0xffffffffL
            require(declared + 8 <= bytes.size) { "The WAV file is truncated." }
            var position = 12
            var validFormat = false
            var audio: FloatArray? = null
            while (position + 8 <= declared + 8) {
                val size = buffer.getInt(position + 4).toLong() and 0xffffffffL
                val start = position + 8
                require(start + size <= declared + 8) { "The WAV chunk is truncated." }
                when (tag(position)) {
                    "fmt " -> {
                        require(size >= 16 && buffer.getShort(start).toInt() == 1 &&
                            buffer.getShort(start + 2).toInt() == 1 && buffer.getInt(start + 4) == 16000 &&
                            buffer.getShort(start + 12).toInt() == 2 && buffer.getShort(start + 14).toInt() == 16) {
                            "Use 16 kHz, mono, signed 16-bit PCM WAV."
                        }
                        validFormat = true
                    }
                    "data" -> {
                        require(size % 2 == 0L && size in 2..1_920_000 && audio == null) { "Use one audio chunk, at most one minute long." }
                        audio = FloatArray((size / 2).toInt()) { buffer.getShort(start + it * 2) / 32768f }
                    }
                }
                position = (start + size + (size and 1)).toInt()
            }
            require(validFormat && audio != null) { "WAV format or audio data is missing." }
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            return SpeechAudio(audio, sourceSha256 = hash)
        }
    }
}

internal data class WordErrors(val edits: Int, val referenceWords: Int) {
    val rate get() = if (referenceWords == 0) null else edits.toDouble() / referenceWords
}

/** Lexical WER: case/punctuation normalized, numbers and units not rewritten. */
internal fun wordErrors(reference: String, actual: String): WordErrors {
    fun words(text: String) = Regex("[\\p{L}\\p{N}]+(?:['.][\\p{L}\\p{N}]+)*").findAll(
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace('’', '\''))
        .map { it.value }.toList()
    val expected = words(reference)
    val found = words(actual)
    var previous = IntArray(found.size + 1) { it }
    expected.forEachIndexed { row, word ->
        val next = IntArray(found.size + 1)
        next[0] = row + 1
        found.forEachIndexed { column, candidate ->
            next[column + 1] = minOf(previous[column + 1] + 1, next[column] + 1,
                previous[column] + if (word == candidate) 0 else 1)
        }
        previous = next
    }
    return WordErrors(previous[found.size], expected.size)
}

internal enum class SpeechFeed(val label: String) { THROUGHPUT("Maximum throughput"), REALTIME("Real-time chunks") }

internal data class SpeechUpdate(val text: String, val completedLine: Boolean = false, val audioEndMs: Double? = null)

/** Exclusively owned until close. Callbacks execute synchronously on the calling thread. */
internal interface LocalSpeechEngine : AutoCloseable {
    val actualBackend: String
    val runtimeVersion: String
    fun start(onUpdate: (SpeechUpdate) -> Unit)
    fun accept(samples: FloatArray)
    fun finish(): String
    fun transcribe(audio: SpeechAudio): String
}

internal data class SpeechRunMeasurement(
    val modelId: String, val checkpoint: String, val quantization: String, val runtime: String,
    val backend: String, val fixture: String, val mode: String, val repetition: Int,
    val loadMs: Double, val audioSeconds: Double, val elapsedMs: Double,
    val firstPartialMs: Double?, val lastWordToFinalMs: Double?, val forcedFinalization: Boolean,
    val prematureEndings: Int, val wordEdits: Int, val referenceWords: Int,
    val peakPssKb: Int, val peakRssKb: Int, val thermalStart: String, val thermalEnd: String,
    val transcript: String, val audioSha256: String? = null,
)
