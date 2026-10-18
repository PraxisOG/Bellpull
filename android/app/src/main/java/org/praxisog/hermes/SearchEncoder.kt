package org.praxisog.hermes

import android.content.Context
import ai.onnxruntime.*
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.Locale
import kotlin.math.sqrt

/** BERT uncased WordPiece; tokenizer and model are pinned together. */
internal class WordPiece(vocabulary: List<String>) {
    private val words = vocabulary.withIndex().associate { it.value to it.index.toLong() }
    fun tokens(text: String): LongArray {
        val specials = Regex("\\[(?:PAD|UNK|CLS|SEP|MASK)\\]").findAll(text).toList()
        if (specials.isNotEmpty()) {
            val pieces = mutableListOf<Long>(); var cursor = 0
            specials.forEach { match ->
                pieces.addAll(tokens(text.substring(cursor, match.range.first)).drop(1).dropLast(1))
                pieces.add(words.getValue(match.value)); cursor = match.range.last + 1
            }
            pieces.addAll(tokens(text.substring(cursor)).drop(1).dropLast(1))
            return (listOf(words.getValue("[CLS]")) + pieces.take(254) + words.getValue("[SEP]")).toLongArray()
        }
        val basic = StringBuilder()
        val clean = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        clean.codePoints().forEach { cp ->
            val type = Character.getType(cp)
            when {
                type == Character.NON_SPACING_MARK.toInt() -> Unit
                Character.isWhitespace(cp) || type == Character.SPACE_SEPARATOR.toInt() -> basic.append(' ')
                cp == 0 || cp == 0xfffd || type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() -> Unit
                cp in 0x4e00..0x9fff || cp in 0x3400..0x4dbf || cp in 0x20000..0x2fa1f || cp in 0xf900..0xfaff ||
                    cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126 ||
                    type in setOf(20, 21, 22, 23, 24, 29, 30) -> basic.append(' ').appendCodePoint(cp).append(' ')
                else -> basic.appendCodePoint(cp)
            }
        }
        val result = mutableListOf(words.getValue("[CLS]"))
        for (word in basic.toString().trim().split(Regex("\\s+"))) {
            if (word.isEmpty()) continue
            val pieces = mutableListOf<Long>(); var start = 0
            if (word.codePointCount(0, word.length) <= 100) while (start < word.length) {
                var end = word.length; var found: Long? = null
                while (end > start) {
                    found = words[(if (start == 0) "" else "##") + word.substring(start, end)]
                    if (found != null) break
                    end--
                }
                if (found == null) { pieces.clear(); break }
                pieces.add(found); start = end
            }
            if (pieces.isEmpty()) pieces.add(words.getValue("[UNK]"))
            result.addAll(pieces.take(255 - result.size))
            if (result.size >= 255) break
        }
        result.add(words.getValue("[SEP]")); return result.toLongArray()
    }
}

internal class SearchEncoder(context: Context) : AutoCloseable {
    companion object { const val VERSION = "minilm-l6-v2-4278337f-wordpiece-07eced37-chunks-v1" }
    private val tokenizer = WordPiece(context.assets.open("search/vocab.txt").bufferedReader().use { it.readLines() })
    private val env = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        env.createSession(context.assets.open("search/model_qint8_arm64.onnx").use { it.readBytes() }, options)
    }
    @Synchronized fun encode(text: String): List<Float> {
        val ids = tokenizer.tokens(text); val shape = longArrayOf(1, ids.size.toLong())
        val inputs = mapOf("input_ids" to ids, "attention_mask" to LongArray(ids.size) { 1 }, "token_type_ids" to LongArray(ids.size))
            .mapValues { OnnxTensor.createTensor(env, LongBuffer.wrap(it.value), shape) }
        try {
            session.run(inputs).use { output ->
                @Suppress("UNCHECKED_CAST") val vectors = (output[0].value as Array<Array<FloatArray>>)[0]
                val pooled = FloatArray(vectors[0].size)
                vectors.forEach { vector -> vector.indices.forEach { i -> pooled[i] += vector[i] / vectors.size } }
                val norm = sqrt(pooled.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-12f)
                return pooled.map { it / norm }
            }
        } finally { inputs.values.forEach { it.close() } }
    }
    override fun close() = session.close()
}

internal object OfflineSearch {
    fun chunks(source: OfflineSource): List<OfflineChunk> = source.messages.flatMap { message ->
        // At most 220 UTF-16 units also bounds CJK/punctuation-heavy chunks below
        // MiniLM's 256-piece window; no unembedded tail inside a displayed excerpt.
        // Every character is retained in the source; chunks overlap for boundary facts.
        val text = message.content.orEmpty()
        buildList { var start = 0; while (start < text.length) {
            var end = minOf(start + 220, text.length)
            if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
            add(OfflineChunk("m${message.id}:$start", message.id, message.role, text.substring(start, end)))
            if (end == text.length) break
            start = end - 40
            if (Character.isLowSurrogate(text[start])) start++
        } }
    }
    fun search(chunks: List<OfflineChunk>, query: String, vector: List<Float> = emptyList(), limit: Int = 4): List<OfflineChunk> {
        val terms = query.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+" )).filter { it.length > 1 }.distinct()
        return chunks.map { chunk ->
            val lower = chunk.text.lowercase(Locale.ROOT)
            val lexical = terms.count { lower.contains(it) }.toDouble() / maxOf(1, terms.size)
            val semantic = if (vector.isNotEmpty() && chunk.vector.size == vector.size) vector.indices.sumOf { (vector[it] * chunk.vector[it]).toDouble() } else 0.0
            chunk to (lexical * .45 + semantic * .55)
        }.filter { it.second > .03 }.sortedByDescending { it.second }.take(limit).map { it.first }
    }
}
