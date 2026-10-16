package org.praxisog.hermes

import android.content.Context
import kotlinx.coroutines.*
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

internal enum class VoiceOutputEngine(val id: String, val label: String) {
    SYSTEM("system", "Default Android voice"), QWEN("qwen", "Qwen3-TTS · on device"), MOSS("moss", "MOSS-TTS-Nano · on device"),
    SERVER_MOSS("server-moss", "MOSS · server offload");
    companion object {
        fun decode(id: String?): VoiceOutputEngine = when (id?.lowercase(java.util.Locale.ROOT)) {
            "qwen" -> QWEN
            "server-moss" -> SERVER_MOSS
            "moss", "pocket" -> MOSS // Upgrade the former selectable engine; weights still need downloading.
            else -> SYSTEM
        }
    }
}
internal data class VoiceOutputConfiguration(
    val engine: VoiceOutputEngine = VoiceOutputEngine.SYSTEM,
    val speaker: String = "ryan", val language: String = "english",
    val temperature: Float = .9f, val topP: Float = 1f, val topK: Int = 50,
    val repetition: Float = 1.05f, val seed: Long = -1, val volume: Float = 1f,
    val pocketSteps: Int = 5, val pocketTemperature: Float = .7f, val pocketChunkFrames: Int = 15,
) {
    fun validated(): VoiceOutputConfiguration {
        require(speaker in speakers && language in languages)
        require(temperature.isFinite() && temperature in 0f..1.5f && topP.isFinite() && topP in .1f..1f)
        require(topK in 0..100 && repetition.isFinite() && repetition in 1f..1.5f && seed >= -1)
        require(volume.isFinite() && volume in 0f..1f)
        require(pocketSteps in 1..10 && pocketTemperature.isFinite() && pocketTemperature in 0f..1.5f && pocketChunkFrames in 1..30)
        return this
    }
    companion object {
        val speakers = listOf("ryan", "aiden", "serena", "vivian", "uncle_fu", "ono_anna", "sohee", "eric", "dylan")
        val languages = listOf("english", "auto", "chinese", "japanese", "korean", "german", "french", "russian", "portuguese", "spanish", "italian")
    }
}
internal data class VoiceModelFile(val name: String, val bytes: Long, val sha256: String, val remoteName: String = name)
internal object QwenModel {
    const val revision = "b7ee2e8c7459c3bea99da23e3d178125a7d1713c"
    const val label = "Qwen3-TTS 0.6B CustomVoice"
    const val checkpoint = "Qwen3-TTS-12Hz-0.6B-CustomVoice"
    const val runtime = "qwentts.cpp 51512f1 / GGML 2eddaf9"
    val files = listOf(
        VoiceModelFile("qwen-talker-0.6b-customvoice-Q4_K_M.gguf", 604878080, "b3a7e6613d80f8a703c06267fc1e94d48ce91932ab82ab6e31c50f4ca4868e1e"),
        VoiceModelFile("qwen-tokenizer-12hz-Q4_K_M.gguf", 254974752, "cf3788b4d50aaa665fb6e57c170396aae03a3555fea52d2b5d0cda902d658039"))
    val bytes = files.sumOf { it.bytes }
}

internal data class OutputModel(val id: String, val label: String, val revision: String,
    val repository: String, val files: List<VoiceModelFile>) {
    val bytes get() = files.sumOf { it.bytes }
}
internal val qwenOutputModel = OutputModel("qwen-0.6b", "Qwen", QwenModel.revision,
    "Serveurperso/Qwen3-TTS-GGUF", QwenModel.files)
internal object PocketModel {
    const val revision = "e715955cf50d18d919d37231513c0e914b83661a"
    const val checkpoint = "sherpa-onnx-pocket-tts-int8-2026-01-26"
    const val runtime = "Sherpa-ONNX 1.13.8 (Pocket only) / ONNX Runtime 1.28.2"
    val model = OutputModel("pocket-int8", "Pocket", revision,
        "csukuangfj2/sherpa-onnx-pocket-tts-int8-2026-01-26", listOf(
        VoiceModelFile("decoder.int8.onnx", 22693618, "12b0857402d31aead94df19d6783b4350d1f740e811f3a3202c70ad89ae11eea"),
        VoiceModelFile("encoder.onnx", 72713165, "e8f2f6d301ffb96e398b138a7dc6d3038622d236044636b73d920bab85890260"),
        VoiceModelFile("lm_flow.int8.onnx", 9962530, "8d627d235c44a597da908e1085ebe241cbbe358964c502c5a5063d18851a5529"),
        VoiceModelFile("lm_main.int8.onnx", 76341079, "bfc0c7e7e3d72864fa3bb2ee499f62f21ddc1474b885f5f3ca570f8be73e787e"),
        VoiceModelFile("text_conditioner.onnx", 16388343, "0b84e837d7bfaf2c896627b03e3f080320309f37f4fc7df7698c644f7ba5e6b1"),
        VoiceModelFile("vocab.json", 69478, "6fb646346cf931016f70c4921aab0900ce7a304b893cb02135c74e294abfea01"),
        VoiceModelFile("token_scores.json", 123616, "5be2f278caf9b9800741f0fd82bff677f4943ec764c356f907213434b622d958"),
        VoiceModelFile("loona.wav", 50478, "82bde825fe9ad43b3d7fa2b429a7e7acc9bcda2d3a208546e2db4863043ec64c", "test_wavs/loona.wav")))
}

/** App-owned, immutable, hash-verified weights. No Hermes credentials or user paths. */
internal class VoiceOutputFiles(private val root: File,
    private val specs: List<VoiceModelFile> = QwenModel.files,
    private val model: OutputModel = qwenOutputModel,
    private val open: (VoiceModelFile) -> InputStream = { openVoiceModel(it, model) }) {
    constructor(context: Context, model: OutputModel = qwenOutputModel) :
        this(File(context.noBackupFilesDir, "voice-output-models"), model.files, model)
    init {
        // Checkpoint IDs include decimal sizes (qwen-0.6b). Keep them stable so
        // an upgrade reuses downloaded weights; allow dots after a safe prefix.
        require(model.id.matches(Regex("[a-z0-9][a-z0-9.-]*")) &&
            (model.revision.matches(Regex("[0-9a-f]{40}")) || model == MossModel.model))
        require(specs.isNotEmpty() && specs.map { it.name }.distinct().size == specs.size)
        require(specs.all { it.name.matches(Regex("[a-zA-Z0-9_.-]+\\.(gguf|onnx|json|wav|model|data)")) && !it.name.startsWith('.') && it.bytes > 0 && it.sha256.matches(Regex("[0-9a-f]{64}")) })
    }
    val directory get() = File(root, "${model.id}-${model.revision}")
    private val staging get() = File(root, ".${model.id}-${model.revision}.partial")
    val partial get() = staging.isDirectory
    val installed get() = File(directory, ".complete").readTextOrNull() == model.revision &&
        specs.all { File(directory, it.name).let { f -> f.isFile && f.length() == it.bytes } }
    fun verify() {
        check(installed) { "Download ${model.label} in Settings → Voice output first." }
        check(specs.all { valid(File(directory, it.name), it) }) { "${model.label} files failed verification. Delete and download the model again." }
    }
    fun delete() {
        check(!directory.exists() || directory.deleteRecursively()) { "Could not delete ${model.label} model files." }
        check(!staging.exists() || staging.deleteRecursively()) { "Could not delete ${model.label} partial download." }
    }
    suspend fun download(progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        if (installed) { verify(); return@withContext }
        check(root.mkdirs() || root.isDirectory)
        check(staging.mkdirs() || staging.isDirectory)
        val total = specs.sumOf { it.bytes }
        val missing = specs.filterNot { valid(File(staging, it.name), it) }.sumOf { it.bytes }
        check(root.usableSpace > missing + 32_000_000) { "Free at least ${missing / 1_000_000 + 32} MB to finish this download." }
        var complete = 0L
        for (spec in specs) {
            currentCoroutineContext().ensureActive()
            val destination = File(staging, spec.name)
            if (!valid(destination, spec)) {
                val temporary = File(staging, "${spec.name}.part")
                try {
                    open(spec).use { source -> temporary.outputStream().use { sink ->
                        val buffer = ByteArray(128 * 1024); var count = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = source.read(buffer); if (n < 0) break
                            count += n; check(count <= spec.bytes) { "Model download exceeded its pinned size." }
                            sink.write(buffer, 0, n); progress(complete + count, total)
                        }
                    } }
                    check(valid(temporary, spec)) { "${model.label} download failed its size or SHA-256 check. Retry when connected." }
                    check(temporary.renameTo(destination))
                } finally { temporary.delete() }
            }
            complete += spec.bytes; progress(complete, total)
        }
        currentCoroutineContext().ensureActive()
        // Only a fully verified set receives a completion marker.
        File(staging, ".complete").writeText(model.revision)
        check(!directory.exists()) { "Delete the incomplete installed model before retrying." }
        check(staging.renameTo(directory)) { "Could not finish installing ${model.label}." }
    }
    private fun valid(file: File, spec: VoiceModelFile): Boolean {
        if (!file.isFile || file.length() != spec.bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == spec.sha256
    }
}
private fun File.readTextOrNull(): String? = runCatching { takeIf { isFile }?.readText() }.getOrNull()
internal fun voiceModelUri(spec: VoiceModelFile, model: OutputModel): URI {
    require(model in listOf(qwenOutputModel, PocketModel.model, MossModel.model) && spec in model.files)
    return if (model == MossModel.model) MossModel.uri(spec)
        else URI("https://huggingface.co/${model.repository}/resolve/${model.revision}/${spec.remoteName}")
}
private fun openVoiceModel(spec: VoiceModelFile, model: OutputModel): InputStream {
    var uri = voiceModelUri(spec, model)
    repeat(6) {
        require(uri.scheme == "https" && uri.userInfo == null && uri.fragment == null &&
            (uri.host == "huggingface.co" || uri.host == "hf.co" || uri.host?.endsWith(".huggingface.co") == true ||
                uri.host?.endsWith(".hf.co") == true || uri.host?.endsWith(".xethub.hf.co") == true)) { "Unexpected model download redirect." }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false; connection.connectTimeout = 15000; connection.readTimeout = 15000
        try {
            val status = connection.responseCode
            if (status in listOf(301, 302, 303, 307, 308)) {
                val next = connection.getHeaderField("Location") ?: error("Model redirect has no destination.")
                uri = uri.resolve(next); connection.disconnect()
            } else {
                check(status == 200) { "${model.label} download failed (HTTP $status). Retry when connected." }
                return object : FilterInputStream(connection.inputStream) {
                    override fun close() { try { super.close() } finally { connection.disconnect() } }
                }
            }
        } catch (error: Exception) { connection.disconnect(); throw error }
    }
    error("Too many model download redirects.")
}
