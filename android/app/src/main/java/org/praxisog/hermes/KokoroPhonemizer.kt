package org.praxisog.hermes

import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.CancellationException

/** English lexicon/stemming adapted from hexgrad/Misaki (Apache-2.0).
 * The pinned 0.75M BART model handles unknown words. No network or system voice.
 * Without a POS tagger, heteronyms use the lexicon's DEFAULT pronunciation.
 */
internal class KokoroPhonemizer(metadata: String) {
    private val meta = JSONObject(metadata)
    private val gold = meta.getJSONObject("gold")
    private val silver = meta.getJSONObject("silver")
    private val graphemes = meta.getString("graphemes").withIndex().associate { it.value to it.index.toLong() }
    private val phonemes = meta.getString("phonemes")
    private val vocab = meta.getJSONObject("kokoro_vocab")
    private val predictions = object : LinkedHashMap<String, String>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 256
    }
    private fun lookup(word: String): String? {
        for (dictionary in listOf(gold, silver)) {
            for (key in listOf(word, word.lowercase(Locale.ROOT), word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) })) {
                val item = dictionary.opt(key)
                val value = if (item is JSONObject) item.optString("DEFAULT") else item as? String
                if (!value.isNullOrEmpty()) return value
            }
        }
        return null
    }
    private fun letters(word: String) = word.filter { it.isLetter() }.mapNotNull { lookup(it.uppercaseChar().toString()) }.joinToString(" ")
    private fun stem(word: String): String? {
        if (word.endsWith("s") && word.length > 2) {
            val candidates = buildList {
                if (!word.endsWith("ss")) add(word.dropLast(1))
                if (word.endsWith("'s") || word.endsWith("es")) add(word.dropLast(2))
                if (word.endsWith("ies")) add(word.dropLast(3) + "y")
            }
            candidates.firstNotNullOfOrNull(::lookup)?.let { base ->
                return base + when (base.last()) { in "ptkfθ" -> "s"; in "szʃʒʧʤ" -> "ᵻz"; else -> "z" }
            }
        }
        if (word.endsWith("ed") && word.length > 3) {
            listOf(word.dropLast(1), word.dropLast(2)).firstNotNullOfOrNull(::lookup)?.let { base ->
                return base + when (base.last()) { in "td" -> "ᵻd"; in "pkfθʃsʧ" -> "t"; else -> "d" }
            }
        }
        if (word.endsWith("ing") && word.length > 4) {
            val root = word.dropLast(3)
            (lookup(root) ?: lookup(root + "e") ?: if (root.length > 2 && root.last() == root[root.lastIndex - 1]) lookup(root.dropLast(1)) else null)
                ?.let { return it + "ɪŋ" }
        }
        return null
    }
    internal fun word(word: String, next: String?, predict: (LongArray) -> IntArray): String {
        val lower = word.lowercase(Locale.ROOT)
        // Pin the app's names instead of asking the tiny fallback to guess.
        if (lower == "bellpull") return "bˈɛlpʊl"
        if (lower == "hermes") return "hˈɜɹmiz"
        if (word.length > 1 && word.all { it in 'A'..'Z' } && gold.opt(word) == null) return letters(word)
        val vowelNext = next?.firstOrNull()?.lowercaseChar()?.let { it in "aeiou" } == true
        when (lower) {
            "the" -> return if (vowelNext) "ði" else "ðə"
            "to" -> if (next != null) return if (vowelNext) "tʊ" else "tə"
            "a" -> return if (next == null) "ˈA" else "ɐ"
            "an" -> return "ɐn"
        }
        lookup(word)?.let { return it }
        stem(lower)?.let { return it }
        return predictions.getOrPut(word) {
            // Bound encoder work and never feed unsupported Unicode as silent deletions.
            if (word.length > 64) return@getOrPut letters(word)
            val ids = longArrayOf(meta.getLong("bos_id")) + word.map { graphemes[it] ?: meta.getLong("unknown_id") }.toLongArray() + meta.getLong("eos_id")
            predict(ids).filter { it > 3 && it < phonemes.length }.map { phonemes[it] }.joinToString("").ifBlank { letters(word) }
        }
    }
    fun chunks(text: String, cancelled: () -> Boolean = { false }, predict: (LongArray) -> IntArray): Sequence<LongArray> = sequence {
        require(text.length <= 32_000) { "This passage is too long for a single Kokoro request." }
        val normalized = normalize(text)
        val tokens = Regex("[A-Za-z]+(?:'[A-Za-z]+)*|[^\\sA-Za-z]").findAll(normalized).toList()
        var current = mutableListOf<Long>()
        suspend fun SequenceScope<LongArray>.flush() {
            while (current.lastOrNull() == 16L) current.removeAt(current.lastIndex)
            if (current.any { it > 16 }) yield(longArrayOf(0) + current.toLongArray() + 0L)
            current = mutableListOf()
        }
        for ((index, match) in tokens.withIndex()) {
            if (cancelled()) throw CancellationException("Kokoro stopped")
            val token = match.value
            val isWord = token.first() in 'a'..'z' || token.first() in 'A'..'Z'
            val next = tokens.getOrNull(index + 1)?.value?.takeIf { it.first().isLetter() }
            val ps = if (isWord) word(token, next, predict) else token
            val ids = ps.mapNotNull { c -> if (vocab.has(c.toString())) vocab.getLong(c.toString()) else null }
            // Work in short phrases for bounded latency and interruption. Never truncate a long word.
            if (current.size + ids.size + 1 > 160) flush()
            if (isWord && current.isNotEmpty()) current.add(16)
            for (id in ids) { if (current.size >= 160) flush(); current.add(id) }
            if (speechSentenceEnd(normalized, match.range.first, final = true) != null ||
                token in listOf(",", ";", ":") && current.size >= 64) flush()
        }
        flush()
    }
    companion object {
        internal fun normalize(input: String): String {
            var text = Normalizer.normalize(input, Normalizer.Form.NFKC).replace('’', '\'').replace('‘', '\'')
                .replace('“', '"').replace('”', '"').replace("—", ", ").replace("–", ", ")
            text = Regex("\\$([0-9][0-9,]*)(?:\\.([0-9]{2}))?").replace(text) { m ->
                val dollars = m.groupValues[1].replace(",", "")
                val cents = m.groupValues[2].toIntOrNull()
                "$dollars ${if (dollars == "1") "dollar" else "dollars"}" + if (cents != null && cents != 0) " and $cents ${if (cents == 1) "cent" else "cents"}" else ""
            }
            text = Regex("[0-9][0-9,]*(?:\\.[0-9]+)?").replace(text) { m ->
                val number = m.value.replace(",", "").split('.')
                val whole = number[0].toLongOrNull()
                val spoken = if (whole != null && whole < 1_000_000_000_000L) cardinal(whole) else number[0].map { cardinal(it.digitToInt().toLong()) }.joinToString(" ")
                spoken + if (number.size == 2) " point " + number[1].map { cardinal(it.digitToInt().toLong()) }.joinToString(" ") else ""
            }
            text = text.replace("%", " percent ").replace("&", " and ").replace("+", " plus ").replace("=", " equals ").replace("/", " slash ").replace("@", " at ")
            text = Normalizer.normalize(text, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
            require(text.none { it.isLetter() && it !in 'a'..'z' && it !in 'A'..'Z' }) { "Kokoro currently supports English text. Choose another voice for this language." }
            return text
        }
        private fun cardinal(n: Long): String {
            val small = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
            if (n < 20) return small[n.toInt()]
            if (n < 100) return listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")[(n / 10).toInt()] + if (n % 10 == 0L) "" else " " + cardinal(n % 10)
            val (unit, name) = when { n >= 1_000_000_000 -> 1_000_000_000L to "billion"; n >= 1_000_000 -> 1_000_000L to "million"; n >= 1000 -> 1000L to "thousand"; else -> 100L to "hundred" }
            return cardinal(n / unit) + " " + name + if (n % unit == 0L) "" else " " + cardinal(n % unit)
        }
    }
}
