package org.praxisog.hermes

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** Port of docs/references/MossOnnxDemoEngine.kt: same prompt, prefill, KV-cache,
 * fixed-frame sampling and full-codec graph feeds. WAV output is replaced by the
 * existing cancellable PCM sink. No codec-step signature is assumed.
 */
internal class MossOnnxEngine(private val root: File, cpuThreads: Int = 2) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val manifest = json(asset("browser_poc_manifest.json"))
    private val cfg = manifest.getJSONObject("tts_config")
    private val templates = manifest.getJSONObject("prompt_templates")
    private val modelFiles = manifest.getJSONObject("model_files")
    private val ttsMeta = json(asset(modelFiles.getString("tts_meta")))
    private val codecMeta = json(asset(modelFiles.getString("codec_meta")))
    private val nVq = cfg.getInt("n_vq")
    private val sampleRate = codecMeta.getJSONObject("codec_config").getInt("sample_rate")
    private val maxFrames = manifest.optJSONObject("generation_defaults")?.optInt("max_new_frames", 375) ?: 375
    private val sessions = ArrayList<OrtSession>()
    private val options = OrtSession.SessionOptions()
    private val prefill: OrtSession
    private val decode: OrtSession
    private val localFrame: OrtSession
    private val codec: OrtSession
    private var closed = false

    init {
        try {
            // The existing shared playback sink is mono 24 kHz. Never play at the wrong rate.
            check(sampleRate == 24000) { "MOSS requires mono 24 kHz output for this playback path." }
            require(nVq > 0 && maxFrames in 1..4096)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setIntraOpNumThreads(cpuThreads.coerceAtLeast(1))
            options.setInterOpNumThreads(1)
            val files = ttsMeta.getJSONObject("files")
            prefill = session(files.getString("prefill"))
            decode = session(files.getString("decode_step"))
            localFrame = session(files.getString("local_fixed_sampled_frame"))
            codec = session(codecMeta.getJSONObject("files").getString("decode_full"))
        } catch (error: Throwable) {
            sessions.asReversed().forEach { runCatching { it.close() } }
            options.close()
            throw error
        }
    }

    // Downloads are deliberately flat. Resolve the reference manifest's repository paths
    // by basename, strictly within the hash-verified allowlist, including external .data.
    private fun asset(path: String): File {
        val name = path.substringAfterLast('/')
        require(MossModel.model.files.any { it.name == name }) { "Unknown MOSS asset: $name" }
        return File(root, name).also { check(it.isFile) { "Missing MOSS asset: $name" } }
    }
    private fun session(path: String): OrtSession =
        env.createSession(asset(path).absolutePath, options).also { sessions += it }

    fun synthesize(textTokenIds: IntArray, sink: LocalTtsSink, seed: Long): Int {
        check(!closed)
        require(textTokenIds.isNotEmpty()) { "MOSS text tokenization returned no tokens." }
        if (sink.isCancelled()) return -5
        // ORT's RunOptions termination also covers long prefill/codec calls. Join the
        // watcher before closing options so cancellation cannot access a freed handle.
        OrtSession.RunOptions().use { runOptions ->
            val finished = AtomicBoolean(false)
            val watcher = Thread({
                try {
                    while (!finished.get()) {
                        if (sink.isCancelled()) { runOptions.setTerminate(true); break }
                        Thread.sleep(20)
                    }
                } catch (_: InterruptedException) { /* normal completion */ }
            }, "bellpull-moss-cancel").apply { isDaemon = true; start() }
            try {
                val input = buildInputRows(textTokenIds)
                val prefilled = runPrefill(input, runOptions)
                runDecode(prefilled, seed, sink, runOptions)
                return if (sink.isCancelled()) -5 else 0
            } catch (error: Exception) {
                if (sink.isCancelled()) return -5
                throw error
            } finally {
                finished.set(true); watcher.interrupt(); watcher.join()
            }
        }
    }

    private fun buildInputRows(textTokenIds: IntArray): Array<IntArray> {
        val voices = manifest.getJSONArray("builtin_voices")
        val selected = (0 until voices.length()).map { voices.getJSONObject(it) }
            .firstOrNull { it.optString("voice") == MossModel.voice &&
                (it.optJSONArray("prompt_audio_codes")?.length() ?: 0) > 0 }
            ?: error("MOSS Junhao built-in voice is missing.")
        val prompt = selected.getJSONArray("prompt_audio_codes")
        val prefix = templates.getJSONArray("user_prompt_prefix_token_ids").ints() + cfg.getInt("audio_start_token_id")
        val suffix = intArrayOf(cfg.getInt("audio_end_token_id")) +
            templates.getJSONArray("user_prompt_after_reference_token_ids").ints() + textTokenIds +
            templates.getJSONArray("assistant_prompt_prefix_token_ids").ints() + cfg.getInt("audio_start_token_id")
        val rows = ArrayList<IntArray>()
        fun textRows(tokens: IntArray) = tokens.map { token ->
            IntArray(nVq + 1) { if (it == 0) token else cfg.getInt("audio_pad_token_id") }
        }
        rows += textRows(prefix)
        for (i in 0 until prompt.length()) {
            val codes = prompt.getJSONArray(i).ints()
            rows += IntArray(nVq + 1) { index ->
                when {
                    index == 0 -> cfg.optInt("audio_user_slot_token_id", 8)
                    index - 1 < minOf(codes.size, nVq) -> codes[index - 1]
                    else -> cfg.getInt("audio_pad_token_id")
                }
            }
        }
        rows += textRows(suffix)
        return rows.toTypedArray()
    }

    private data class Prefilled(val hidden: OnnxTensor, val length: Int, val past: OrtSession.Result)
    private fun runPrefill(rows: Array<IntArray>, runOptions: OrtSession.RunOptions): Prefilled {
        val width = nVq + 1
        val flat = IntArray(rows.size * width) { rows[it / width][it % width] }
        tensor(flat, 1, rows.size.toLong(), width.toLong()).use { ids ->
            tensor(IntArray(rows.size) { 1 }, 1, rows.size.toLong()).use { mask ->
                val outputs = prefill.run(mapOf("input_ids" to ids, "attention_mask" to mask), runOptions)
                try {
                    return Prefilled(lastHidden(outputs.requiredTensor("global_hidden")), rows.size, outputs)
                } catch (error: Throwable) { outputs.close(); throw error }
            }
        }
    }

    private fun runDecode(start: Prefilled, seed: Long, sink: LocalTtsSink, runOptions: OrtSession.RunOptions) {
        val tokens = ArrayList<IntArray>()
        val seen = Array(nVq) { HashSet<Int>() }
        val onnx = ttsMeta.getJSONObject("onnx")
        val pastNames = onnx.getJSONArray("decode_input_names").strings().drop(2)
        val presentNames = onnx.getJSONArray("decode_output_names").strings().drop(1)
        val random = java.util.Random(seed)
        var length = start.length
        var hidden = start.hidden
        var past = start.past
        val pcm = MossPcmStream(sampleRate, sink)
        var ended = false
        try {
            check(pastNames.size == presentNames.size) { "MOSS decode cache metadata mismatch." }
            for (step in 0 until maxFrames) {
                if (sink.isCancelled()) return
                val frame = runLocalFrame(hidden, seen, random, runOptions) ?: run { ended = true; break }
                require(frame.size == nVq) { "MOSS frame width mismatch." }
                val row = IntArray(nVq + 1) { if (it == 0) cfg.getInt("audio_assistant_slot_token_id") else frame[it - 1] }
                for (q in 0 until nVq) seen[q].add(frame[q])
                tokens += frame
                // The reference provides only full codec decode. Decode growing prefixes
                // every 16 frames and hold one second of tail for right context. This is
                // prefix streaming, not the unverified stateful codec-step graph. Acoustic
                // continuity/latency must still be checked on a device.
                if (tokens.size % 16 == 0) {
                    if (sink.isCancelled() || !pcm.emit(decodeAudioTokens(tokens, runOptions), final = false)) return
                }
                if (sink.isCancelled()) return
                tensor(row, 1, 1, (nVq + 1).toLong()).use { input ->
                    tensor(intArrayOf(length), 1).use { valid ->
                        val feeds = linkedMapOf<String, OnnxTensorLike>("input_ids" to input, "past_valid_lengths" to valid)
                        for (i in pastNames.indices) feeds[pastNames[i]] = past.requiredTensor(presentNames[i])
                        val outputs = decode.run(feeds, runOptions)
                        val nextHidden = try { lastHidden(outputs.requiredTensor("global_hidden")) }
                            catch (error: Throwable) { outputs.close(); throw error }
                        hidden.close(); past.close()
                        hidden = nextHidden; past = outputs; length++
                    }
                }
            }
            if (sink.isCancelled()) return
            check(ended) { "MOSS reached its frame limit. Try a shorter phrase." }
            check(tokens.isNotEmpty()) { "MOSS generated no audio." }
            pcm.emit(decodeAudioTokens(tokens, runOptions), final = true)
        } finally { hidden.close(); past.close() }
    }

    private fun runLocalFrame(hidden: OnnxTensor, seen: Array<HashSet<Int>>, random: java.util.Random,
        runOptions: OrtSession.RunOptions): IntArray? {
        val codebook = cfg.getJSONArray("audio_codebook_sizes").optInt(0, 1024)
        val mask = IntArray(nVq * codebook)
        seen.forEachIndexed { channel, ids -> ids.forEach { if (it in 0 until codebook) mask[channel * codebook + it] = 1 } }
        val assistantRandom = floatArrayOf(random.nextDouble().coerceIn(1e-6, 1.0 - 1e-6).toFloat())
        val audioRandom = FloatArray(nVq) { random.nextDouble().coerceIn(1e-6, 1.0 - 1e-6).toFloat() }
        tensor(mask, 1, nVq.toLong(), codebook.toLong()).use { seenTensor ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(assistantRandom), longArrayOf(1)).use { assistant ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(audioRandom), longArrayOf(1, nVq.toLong())).use { audio ->
                    localFrame.run(mapOf("global_hidden" to hidden, "repetition_seen_mask" to seenTensor,
                        "assistant_random_u" to assistant, "audio_random_u" to audio), runOptions).use {
                        return if (it.requiredTensor("should_continue").scalarInt() > 0)
                            flattenInts(it.requiredTensor("frame_token_ids").value) else null
                    }
                }
            }
        }
    }

    private fun decodeAudioTokens(tokens: List<IntArray>, runOptions: OrtSession.RunOptions): FloatArray {
        val flat = IntArray(tokens.size * nVq) { tokens[it / nVq][it % nVq] }
        tensor(flat, 1, tokens.size.toLong(), nVq.toLong()).use { codes ->
            tensor(intArrayOf(tokens.size), 1).use { lengths ->
                codec.run(mapOf("audio_codes" to codes, "audio_code_lengths" to lengths), runOptions).use {
                    val audio = it.requiredTensor("audio").value as Array<*>
                    val batch = audio[0] as Array<*>
                    val channels = batch.map { channel -> channel as FloatArray }
                    val reported = it.requiredTensor("audio_lengths").scalarInt()
                    val size = minOf(reported, channels.minOfOrNull { channel -> channel.size } ?: 0)
                    check(size > 0 && channels.isNotEmpty()) { "MOSS codec generated no PCM." }
                    return FloatArray(size) { index -> channels.sumOf { channel -> channel[index].toDouble() }.toFloat() / channels.size }
                }
            }
        }
    }

    private fun tensor(values: IntArray, vararg shape: Long): OnnxTensor =
        OnnxTensor.createTensor(env, IntBuffer.wrap(values), shape)
    private fun lastHidden(tensor: OnnxTensor): OnnxTensor {
        val value = tensor.value as Array<*>
        val hidden = when (tensor.info.shape.size) {
            2 -> value[0] as FloatArray
            3 -> (value[0] as Array<*>).last() as FloatArray
            else -> error("Unexpected MOSS global_hidden rank: ${tensor.info.shape.size}")
        }
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(hidden.copyOf()), longArrayOf(1, hidden.size.toLong()))
    }
    override fun close() {
        if (closed) return
        closed = true
        try { sessions.asReversed().forEach { it.close() } } finally { options.close() }
    }
}

/** A growing codec prefix may revise its tail. Never replay a previously delivered
 * sample, retain right context until final decode, and bound every sink callback.
 */
internal class MossPcmStream(private val tailSamples: Int, private val sink: LocalTtsSink) {
    private var emitted = 0
    fun emit(prefix: FloatArray, final: Boolean): Boolean {
        if (sink.isCancelled()) return false
        check(prefix.size >= emitted) { "MOSS codec prefix became shorter than played audio." }
        val end = if (final) prefix.size else (prefix.size - tailSamples).coerceAtLeast(emitted)
        while (emitted < end) {
            if (sink.isCancelled()) return false
            val next = minOf(emitted + 2400, end)
            val chunk = prefix.copyOfRange(emitted, next)
            check(chunk.all { it.isFinite() }) { "MOSS codec produced invalid PCM." }
            if (!sink.onChunk(chunk)) throw MossSinkStopped()
            emitted = next
        }
        return true
    }
}

private class MossSinkStopped : java.util.concurrent.CancellationException("MOSS PCM sink stopped.")
private fun json(file: File) = JSONObject(file.readText(Charsets.UTF_8))
private fun JSONArray.ints() = IntArray(length()) { getInt(it) }
private fun JSONArray.strings() = List(length()) { getString(it) }
private fun OrtSession.Result.requiredTensor(name: String): OnnxTensor =
    get(name).orElseThrow { IllegalStateException("Missing MOSS ONNX output: $name") } as OnnxTensor
private fun OnnxTensor.scalarInt() = flattenInts(value).firstOrNull() ?: error("Empty MOSS scalar tensor.")
private fun flattenInts(raw: Any?): IntArray {
    val values = ArrayList<Int>()
    fun append(value: Any?) {
        when (value) {
            is Number -> values += value.toInt()
            is IntArray -> values += value.toList()
            is LongArray -> value.forEach { values += it.toInt() }
            is ShortArray -> value.forEach { values += it.toInt() }
            is ByteArray -> value.forEach { values += it.toInt() }
            is Array<*> -> value.forEach { append(it) }
            null -> Unit
            else -> error("Unsupported MOSS int tensor: ${value.javaClass}")
        }
    }
    append(raw)
    return values.toIntArray()
}
