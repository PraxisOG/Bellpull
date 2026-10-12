package org.praxisog.hermes

import android.content.Context
import kotlinx.coroutines.*
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

internal enum class VoiceOutputEngine(val label: String) { SYSTEM("Default Android voice"), QWEN("Qwen3-TTS · on device") }
internal data class VoiceOutputConfiguration(
    val engine: VoiceOutputEngine = VoiceOutputEngine.SYSTEM,
    val speaker: String = "ryan", val language: String = "english",
    val temperature: Float = .9f, val topP: Float = 1f, val topK: Int = 50,
    val repetition: Float = 1.05f, val seed: Long = -1, val volume: Float = 1f,
) {
    fun validated(): VoiceOutputConfiguration {
        require(speaker in speakers && language in languages)
        require(temperature.isFinite() && temperature in 0f..1.5f && topP.isFinite() && topP in .1f..1f)
        require(topK in 0..100 && repetition.isFinite() && repetition in 1f..1.5f && seed >= -1)
        require(volume.isFinite() && volume in 0f..1f)
        return this
    }
    companion object {
        val speakers = listOf("ryan", "aiden", "serena", "vivian", "uncle_fu", "ono_anna", "sohee", "eric", "dylan")
        val languages = listOf("english", "auto", "chinese", "japanese", "korean", "german", "french", "russian", "portuguese", "spanish", "italian")
    }
}
internal data class VoiceModelFile(val name: String, val bytes: Long, val sha256: String)
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

/** App-owned, immutable, hash-verified weights. No Hermes credentials or user paths. */
internal class VoiceOutputFiles(private val root: File,
    private val specs: List<VoiceModelFile> = QwenModel.files,
    private val open: (VoiceModelFile) -> InputStream = ::openVoiceModel) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "voice-output-models"))
    init {
        require(specs.size == 2 && specs.map { it.name }.distinct().size == 2)
        require(specs.all { it.name.matches(Regex("[a-zA-Z0-9_.-]+\\.gguf")) && !it.name.startsWith('.') && it.bytes > 0 && it.sha256.matches(Regex("[0-9a-f]{64}")) })
    }
    val directory get() = File(root, "qwen-0.6b-${QwenModel.revision}")
    private val staging get() = File(root, ".qwen-0.6b-${QwenModel.revision}.partial")
    val partial get() = staging.isDirectory
    val installed get() = File(directory, ".complete").readTextOrNull() == QwenModel.revision &&
        specs.all { File(directory, it.name).let { f -> f.isFile && f.length() == it.bytes } }
    fun verify() {
        check(installed) { "Download Qwen in Settings → Voice output first." }
        check(specs.all { valid(File(directory, it.name), it) }) { "Qwen files failed verification. Delete and download the model again." }
    }
    fun delete() {
        check(!directory.exists() || directory.deleteRecursively()) { "Could not delete Qwen model files." }
        check(!staging.exists() || staging.deleteRecursively()) { "Could not delete Qwen partial download." }
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
                    check(valid(temporary, spec)) { "Qwen download failed its size or SHA-256 check. Retry when connected." }
                    check(temporary.renameTo(destination))
                } finally { temporary.delete() }
            }
            complete += spec.bytes; progress(complete, total)
        }
        currentCoroutineContext().ensureActive()
        // Only a fully verified set receives a completion marker.
        File(staging, ".complete").writeText(QwenModel.revision)
        check(!directory.exists()) { "Delete the incomplete installed model before retrying." }
        check(staging.renameTo(directory)) { "Could not finish installing Qwen." }
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
private fun openVoiceModel(spec: VoiceModelFile): InputStream {
    require(spec in QwenModel.files)
    var uri = URI("https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/${QwenModel.revision}/${spec.name}")
    repeat(6) {
        require(uri.scheme == "https" && uri.userInfo == null && uri.fragment == null &&
            (uri.host == "huggingface.co" || uri.host?.endsWith(".huggingface.co") == true ||
                uri.host?.endsWith(".hf.co") == true || uri.host?.endsWith(".xethub.hf.co") == true)) { "Unexpected model download redirect." }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false; connection.connectTimeout = 15000; connection.readTimeout = 15000
        try {
            val status = connection.responseCode
            if (status in listOf(301, 302, 303, 307, 308)) {
                val next = connection.getHeaderField("Location") ?: error("Model redirect has no destination.")
                uri = uri.resolve(next); connection.disconnect()
            } else {
                check(status == 200) { "Qwen download failed (HTTP $status). Retry when connected." }
                return object : FilterInputStream(connection.inputStream) {
                    override fun close() { try { super.close() } finally { connection.disconnect() } }
                }
            }
        } catch (error: Exception) { connection.disconnect(); throw error }
    }
    error("Too many model download redirects.")
}
