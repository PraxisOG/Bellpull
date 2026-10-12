package org.praxisog.hermes

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.CRC32C

internal data class SpeechModelFile(val name: String, val url: String, val bytes: Long, val checksum: String)
internal data class SpeechModel(val id: String, val label: String, val architecture: Int,
    val checkpoint: String, val quantization: String, val files: List<SpeechModelFile>) {
    val downloadBytes get() = files.sumOf { it.bytes }
    val supportedBackends = listOf("CPU")
}

internal fun speechCatalog(json: String): List<SpeechModel> = Json.parseToJsonElement(json).jsonObject
    .getValue("models").jsonArray.map { entry ->
        val row = entry.jsonObject
        val id = row.getValue("id").jsonPrimitive.content
        require(id in setOf("moonshine-tiny", "moonshine-small", "moonshine-medium"))
        val files = row.getValue("files").jsonArray.map { item ->
            val file = item.jsonObject
            val name = file.getValue("name").jsonPrimitive.content
            val url = file.getValue("url").jsonPrimitive.content
            require(name.matches(Regex("[a-z_]+(?:\\.[a-z_]+)*")))
            require(url == "https://download.moonshine.ai/model/${id.removePrefix("moonshine-")}-streaming-en/quantized_26_08_21/$name")
            require(file.getValue("checksum_type").jsonPrimitive.content == "crc32c")
            SpeechModelFile(name, url, file.getValue("bytes").jsonPrimitive.long.also { require(it > 0) },
                file.getValue("checksum").jsonPrimitive.content)
        }
        require(files.size == 8 && files.map { it.name }.toSet().size == files.size)
        SpeechModel(id, row.getValue("label").jsonPrimitive.content, row.getValue("architecture").jsonPrimitive.int,
            row.getValue("checkpoint").jsonPrimitive.content, row.getValue("quantization").jsonPrimitive.content, files)
    }.also { require(it.map { model -> model.id }.toSet().size == 3) }

/** Only this app-owned root is ever removed. Incomplete sets cannot become installed models. */
internal class SpeechModelFiles(private val root: File,
    private val open: (SpeechModelFile) -> InputStream = ::openSpeechModel) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "speech-models"))
    fun directory(model: SpeechModel): File {
        require(model.id.matches(Regex("[a-z0-9-]+")) && model.checkpoint.matches(Regex("[a-zA-Z0-9_-]+")))
        require(model.files.all { it.name.matches(Regex("[a-z_]+(?:\\.[a-z_]+)*")) })
        return File(root, "${model.id}-${model.checkpoint}")
    }
    private fun staging(model: SpeechModel) = File(root, ".${model.id}-${model.checkpoint}.partial")
    fun hasPartial(model: SpeechModel): Boolean { directory(model); return staging(model).isDirectory }
    fun installed(model: SpeechModel): Boolean = directory(model).let { dir ->
        File(dir, ".complete").isFile && model.files.all { File(dir, it.name).length() == it.bytes }
    }
    fun verify(model: SpeechModel) {
        check(installed(model)) { "Download ${model.label} in Settings → Voice → Experimental first." }
        check(model.files.all { valid(File(directory(model), it.name), it) }) { "Model files failed verification. Delete and download this model again." }
    }
    fun delete(model: SpeechModel) {
        directory(model) // Validate before constructing the staging path too.
        check(!directory(model).exists() || directory(model).deleteRecursively()) { "Could not delete the installed model." }
        check(!staging(model).exists() || staging(model).deleteRecursively()) { "Could not delete the partial download." }
    }
    suspend fun download(model: SpeechModel, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        directory(model)
        if (installed(model)) { verify(model); return@withContext }
        root.mkdirs()
        val folder = staging(model).also { check(it.mkdirs() || it.isDirectory) }
        check(root.usableSpace > model.downloadBytes + 32_000_000) { "Free at least ${model.downloadBytes / 1_000_000 + 32} MB for this download." }
        var complete = 0L
        for (file in model.files) {
            currentCoroutineContext().ensureActive()
            val destination = File(folder, file.name)
            if (!valid(destination, file)) {
                val partial = File(folder, "${file.name}.part")
                try {
                    open(file).use { source -> partial.outputStream().use { sink ->
                        val bytes = ByteArray(128 * 1024)
                        var written = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = source.read(bytes)
                            if (count < 0) break
                            written += count
                            check(written <= file.bytes) { "Model download exceeded its pinned size." }
                            sink.write(bytes, 0, count)
                            progress(complete + written, model.downloadBytes)
                        }
                    } }
                    check(valid(partial, file)) { "Model download is incomplete or failed its checksum. Retry the download." }
                    check(partial.renameTo(destination)) { "Could not save the downloaded model." }
                } finally { partial.delete() }
            }
            complete += file.bytes
            progress(complete, model.downloadBytes)
        }
        currentCoroutineContext().ensureActive()
        File(folder, ".complete").writeText(model.checkpoint)
        check(!directory(model).exists()) { "An incomplete installed model exists. Delete it before retrying." }
        check(folder.renameTo(directory(model))) { "Could not finish model installation." }
    }
    private fun valid(file: File, expected: SpeechModelFile): Boolean {
        if (!file.isFile || file.length() != expected.bytes) return false
        val checksum = if (Build.VERSION.SDK_INT >= 34) CRC32C() else SpeechCrc32c()
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                checksum.update(buffer, 0, size)
            }
        }
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(4).putInt(checksum.value.toInt()).array()) == expected.checksum
    }
}

private fun openSpeechModel(file: SpeechModelFile): InputStream {
    require(file.url.startsWith("https://download.moonshine.ai/model/"))
    val connection = URL(file.url).openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = false
    connection.connectTimeout = 15000
    connection.readTimeout = 15000
    try {
        check(connection.responseCode == 200) { "Model download failed (HTTP ${connection.responseCode}). Retry when connected." }
        return object : FilterInputStream(connection.inputStream) {
            override fun close() { try { super.close() } finally { connection.disconnect() } }
        }
    } catch (error: Exception) { connection.disconnect(); throw error }
}

internal class SpeechExperimentSettings(context: Context) {
    private val prefs = context.getSharedPreferences("speech-experiments", Context.MODE_PRIVATE)
    val selectedModel get() = prefs.getString("model", "moonshine-tiny")!!
    fun select(id: String) { check(prefs.edit().putString("model", id).commit()) }
    fun tuning(id: String) = SpeechTuning(prefs.getInt("$id-update-ms", 500), prefs.getFloat("$id-vad-threshold", .5f))
    fun tune(id: String, value: SpeechTuning) {
        check(prefs.edit().putInt("$id-update-ms", value.updateMs).putFloat("$id-vad-threshold", value.vadThreshold).commit())
    }
}
