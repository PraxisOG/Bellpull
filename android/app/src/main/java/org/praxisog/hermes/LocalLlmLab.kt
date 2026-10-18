package org.praxisog.hermes

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import org.praxisog.hermes.local.LocalLlmNative
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.DataInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal data class LocalModel(val id: String, val name: String, val bytes: Long, val sha256: String)
internal data class LocalTurn(val role: String, val text: String, val status: String = "")
internal data class LocalLabState(
    val models: List<LocalModel> = emptyList(), val selected: String? = null,
    val busy: Boolean = false, val ready: Boolean = false, val gpu: Boolean = false,
    val status: String = "Import a GGUF instruction model to begin.", val error: String? = null,
    val messages: List<LocalTurn> = emptyList(), val draft: String = "",
    val metrics: String = "", val report: String = "", val validation: String = "GPU has not been validated.",
    val gpuValidatedFor: String? = null, val contextTokens: Int = 2048, val scanned: Boolean = false,
)

/** A single serialized native owner, independent of Hermes and its credentials. */
internal class LocalLlmLab private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: LocalLlmLab? = null
        fun get(context: Context): LocalLlmLab = instance ?: synchronized(this) {
            instance ?: LocalLlmLab(context.applicationContext).also { instance = it }
        }
        const val E2B_SHA = "fa401b55b07ee70a54c6dae3903c783a6e65064312529ea57175cb5f8dec6634"
        const val MAX_MODEL_BYTES = 10L * 1024 * 1024 * 1024
        val comparisonPrompts = listOf("Reply with exactly: one two three", "What is 2 plus 3? Answer with one number.")
        fun validationPass(cpu: List<List<Int>>, gpu: List<List<Int>>): Boolean =
            cpu.size == 4 && gpu.size == 4 && cpu.all { it.isNotEmpty() } &&
                cpu[0] == cpu[1] && cpu[2] == cpu[3] && gpu[0] == gpu[1] && gpu[2] == gpu[3] && cpu == gpu
    }
    private val folder = File(context.filesDir, "local-llm").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("local-llm-lab", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val stopped = AtomicBoolean(false)
    @Volatile private var nativeInitialized = false
    private val mutable = MutableStateFlow(LocalLabState())
    val state = mutable.asStateFlow()

    init {
        scope.launch {
            folder.listFiles()?.filter { it.extension == "part" }?.forEach { it.delete() }
            val models = folder.listFiles()?.filter { it.extension == "json" && it.name != "last-report.json" }?.mapNotNull { file ->
                runCatching {
                    val json = JSONObject(file.readText())
                    LocalModel(file.nameWithoutExtension, json.getString("name"), json.getLong("bytes"), json.getString("sha256"))
                        .takeIf { modelFile(it).length() == it.bytes }
                }.getOrNull()
            }.orEmpty().sortedBy { it.name }
            mutable.update { it.copy(scanned = true, models = models, selected = prefs.getString("selected", null)?.takeIf { id -> models.any { m -> m.id == id } } ?: models.firstOrNull()?.id,
                report = runCatching { File(folder, "last-report.json").readText() }.getOrDefault(""),
                status = if (models.isEmpty()) it.status else "Choose a model, then load CPU.") }
        }
    }
    private fun modelFile(model: LocalModel) = File(folder, "${model.id}.gguf")
    private fun selected() = state.value.models.first { it.id == state.value.selected }
    private fun checkStop() { check(!stopped.get()) { "Stopped" } }
    @Synchronized private fun operation(label: String, block: suspend () -> Unit) {
        if (state.value.busy) return
        stopped.set(false)
        mutable.update { it.copy(busy = true, error = null, status = label) }
        scope.launch {
            try {
                if (nativeInitialized) LocalLlmNative.clearCancel()
                block()
            } catch (error: Throwable) {
                if (error is VirtualMachineError) throw error
                val reason = if (stopped.get()) "Stopped. You can load the model again." else error.message ?: error.javaClass.simpleName
                mutable.update { it.copy(error = if (stopped.get()) null else reason, status = reason,
                    messages = it.messages.map { m -> if (m.status == "generating") m.copy(status = if (stopped.get()) "stopped" else "failed") else m }) }
                if (nativeInitialized) LocalLlmNative.unload()
                mutable.update { it.copy(ready = false) }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }
    fun import(uri: Uri, name: String) = importModel(name) { context.contentResolver.openInputStream(uri) ?: error("Cannot open this document.") }
    internal fun importModel(name: String, open: () -> InputStream) = operation("Importing model…") {
        val id = UUID.randomUUID().toString()
        val part = File(folder, "$id.part")
        try {
            var count = 0L
            val hash = MessageDigest.getInstance("SHA-256")
            val capacity = StatFs(folder.path).availableBytes - 512L * 1024 * 1024
            open().use { input -> part.outputStream().buffered().use { output ->
                val header = ByteArray(8).also { DataInputStream(input).readFully(it) }
                require(header.size == 8 && header.copyOfRange(0, 4).contentEquals("GGUF".toByteArray()) && header[4].toInt() in 2..3 && header.sliceArray(5..7).all { it.toInt() == 0 }) { "Select a GGUF v2/v3 model file." }
                require(!name.contains("mmproj", true)) { "Choose the text model, not its mmproj companion." }
                output.write(header); hash.update(header); count = 8
                val buffer = ByteArray(1024 * 1024)
                var reported = 0L
                while (true) {
                    checkStop()
                    val size = input.read(buffer)
                    if (size < 0) break
                    count += size
                    require(count <= MAX_MODEL_BYTES && count <= capacity) { "Not enough free space, or model exceeds this experiment's 10 GiB limit." }
                    output.write(buffer, 0, size); hash.update(buffer, 0, size)
                    if (count - reported >= 64L * 1024 * 1024) {
                        mutable.update { it.copy(status = "Importing ${count / (1024 * 1024)} MiB…") }; reported = count
                    }
                }
            } }
            checkStop()
            val model = LocalModel(id, name.take(180), count, hash.digest().joinToString("") { "%02x".format(it) })
            check(part.renameTo(modelFile(model))) { "Cannot finish model import." }
            File(folder, "$id.json").writeText(JSONObject().put("name", model.name).put("bytes", count).put("sha256", model.sha256).toString())
            if (nativeInitialized) LocalLlmNative.unload()
            prefs.edit().putString("selected", id).apply()
            mutable.update { it.copy(models = it.models + model, selected = id, ready = false, messages = emptyList(), metrics = "", gpuValidatedFor = null,
                validation = "GPU has not been validated for this model.", status = "Imported ${model.name}. Load CPU to chat.") }
        } finally { part.delete() }
    }
    fun select(id: String) = operation("Selecting model…") {
        check(state.value.models.any { it.id == id })
        if (nativeInitialized) LocalLlmNative.unload()
        prefs.edit().putString("selected", id).apply()
        mutable.update { it.copy(selected = id, ready = false, messages = emptyList(), metrics = "", gpuValidatedFor = null,
            validation = "GPU has not been validated for this model.", status = "Model selected. Load CPU to chat.") }
    }
    fun deleteSelected() = operation("Removing model…") {
        val model = selected()
        if (nativeInitialized) LocalLlmNative.unload()
        check(modelFile(model).delete()) { "Cannot delete model." }
        File(folder, "${model.id}.json").delete()
        mutable.update { val models = it.models.filterNot { m -> m.id == model.id }; it.copy(models = models, selected = models.firstOrNull()?.id,
            ready = false, messages = emptyList(), metrics = "", gpuValidatedFor = null, status = "Model removed.") }
        prefs.edit().putString("selected", state.value.selected).apply()
    }
    private fun loadNative(gpu: Boolean, contextTokens: Int = 2048) {
        checkStop()
        LocalLlmNative.initialize(); nativeInitialized = true
        checkStop()
        LocalLlmNative.clearCancel()
        // Recheck after clearing: a concurrent Stop must never be lost.
        if (stopped.get()) LocalLlmNative.cancel()
        LocalLlmNative.load(modelFile(selected()).absolutePath, gpu, contextTokens)
        mutable.update { it.copy(contextTokens = contextTokens) }
        checkStop()
    }
    fun load(gpu: Boolean = false) = operation(if (gpu) "Loading validated Vulkan model…" else "Loading CPU model…") {
        check(!gpu || state.value.gpuValidatedFor == selected().sha256) { "Run CPU/GPU comparison before enabling GPU chat." }
        mutable.update { it.copy(ready = false) }
        loadNative(gpu)
        mutable.update { it.copy(ready = true, gpu = gpu, status = if (gpu) "Vulkan ready • validated on fixed prompts only" else "CPU ready • entirely on this phone", metrics = metrics()) }
    }
    fun unload() = operation("Unloading…") {
        if (nativeInitialized) LocalLlmNative.unload()
        mutable.update { it.copy(ready = false, status = "Model unloaded. Your local chat remains until New chat or process exit.") }
    }
    fun stop() {
        stopped.set(true)
        if (nativeInitialized) LocalLlmNative.cancel()
        if (state.value.busy) mutable.update { it.copy(status = "Stopping…") }
    }
    fun background() {
        stop()
        scope.launch {
            if (nativeInitialized) LocalLlmNative.unload()
            mutable.update { it.copy(ready = false, status = "Model unloaded while the lab was in the background. Load to continue.") }
        }
    }
    fun draft(value: String) { mutable.update { it.copy(draft = value.take(12000)) } }
    fun newChat() { if (!state.value.busy) mutable.update { it.copy(messages = emptyList(), draft = "", error = null, status = if (it.ready) "Ready for a new local chat." else "Load a model to chat.") } }
    fun send() {
        if (!state.value.ready || state.value.draft.isBlank()) return
        val prompt = state.value.draft.trim()
        operation("Processing prompt…") {
            val messages = state.value.messages.chunked(2).filter { pair -> pair.size == 2 && pair[1].status in listOf("complete", "token limit") }.flatten() + LocalTurn("user", prompt)
            mutable.update { it.copy(messages = it.messages + LocalTurn("user", prompt) + LocalTurn("assistant", "", "generating"), draft = "") }
            val started = SystemClock.elapsedRealtime()
            var first = 0L
            infer(messages, 256) { text ->
                if (first == 0L) first = SystemClock.elapsedRealtime() - started
                mutable.update { it.copy(messages = it.messages.dropLast(1) + LocalTurn("assistant", text, "generating"), status = "Generating on ${if (it.gpu) "Vulkan" else "CPU"}…") }
            }
            val finish = JSONObject(LocalLlmNative.report()).getString("finish")
            mutable.update { it.copy(messages = it.messages.dropLast(1) + it.messages.last().copy(status = finish),
                status = if (finish == "token limit") "Stopped at the 256-token response limit." else "Ready", metrics = "First text ${first} ms • " + metrics()) }
        }
    }
    private fun infer(messages: List<LocalTurn>, tokens: Int, onText: (String) -> Unit = {}): Pair<String, JSONObject> {
        checkStop()
        val payload = JSONArray().apply { messages.forEach { put(JSONObject().put("role", it.role).put("content", it.text)) } }
        LocalLlmNative.begin(payload.toString().toByteArray(Charsets.UTF_8), tokens)
        val output = ByteArrayOutputStream()
        while (true) {
            checkStop()
            val piece = LocalLlmNative.next() ?: break
            output.write(piece)
            // Decode the accumulated bytes so split Unicode tokens repair naturally.
            onText(output.toString(Charsets.UTF_8.name()))
        }
        return output.toString(Charsets.UTF_8.name()) to JSONObject(LocalLlmNative.report())
    }

    private fun offlineReady(contextTokens: Int) {
        val e2b = state.value.models.firstOrNull { it.sha256 == E2B_SHA }
            ?: error("Import Gemma 4 E2B Q4_0 in Settings first.")
        if (nativeInitialized) LocalLlmNative.clearCancel()
        checkStop()
        if (!state.value.ready || state.value.gpu || state.value.selected != e2b.id || state.value.contextTokens != contextTokens) {
            mutable.update { it.copy(selected = e2b.id, ready = false, status = "Loading Gemma on CPU…") }
            loadNative(false, contextTokens)
            mutable.update { it.copy(ready = true, gpu = false, status = "Local model ready · limited functionality") }
        }
    }
    private fun bytes(turns: List<LocalTurn>) = JSONArray().apply {
        turns.forEach { put(JSONObject().put("role", it.role).put("content", it.text)) }
    }.toString().toByteArray(Charsets.UTF_8)

    /** Shares admission and the native owner with the lab. The deferred completes AFTER release. */
    @Synchronized private fun <T> offlineOperation(contextTokens: Int, block: () -> T): Deferred<T> {
        check(!state.value.busy) { "The local model is busy. Stop the other operation first." }
        val result = CompletableDeferred<T>()
        stopped.set(false)
        mutable.update { it.copy(busy = true, error = null) }
        scope.launch {
            try {
                offlineReady(contextTokens)
                val value = block()
                mutable.update { it.copy(busy = false) }
                result.complete(value)
            } catch (error: Throwable) {
                if (error is VirtualMachineError) throw error
                if (nativeInitialized) LocalLlmNative.unload()
                mutable.update { it.copy(ready = false, busy = false, error = if (stopped.get()) null else error.message) }
                result.completeExceptionally(error)
            }
        }
        return result
    }
    fun prepareOffline(contextTokens: Int) = offlineOperation(contextTokens) { Unit }
    fun <T> measureOffline(contextTokens: Int, task: ((List<LocalTurn>, Boolean) -> Int) -> T): Deferred<T> =
        offlineOperation(contextTokens) { task { turns, thinking -> checkStop(); LocalLlmNative.countTokens(bytes(turns), thinking) } }

    fun offlineReply(cacheKey: String, thinking: Boolean, maxTokens: Int,
        contextTokens: Int = 2048, prompt: ((List<LocalTurn>) -> Int) -> List<LocalTurn>,
        onText: (String) -> Unit): Deferred<Pair<String, String>> = offlineOperation(contextTokens) {
        val messages = prompt { LocalLlmNative.countTokens(bytes(it), thinking) }
        checkStop()
        LocalLlmNative.begin(bytes(messages), maxTokens, thinking, cacheKey)
        val output = ByteArrayOutputStream()
        if (JSONObject(LocalLlmNative.report()).optBoolean("reasoning_prefill"))
            output.write("<|channel>thought\n".toByteArray())
        while (true) {
            checkStop()
            val piece = LocalLlmNative.next() ?: break
            output.write(piece); onText(output.toString(Charsets.UTF_8.name()))
        }
        output.toString(Charsets.UTF_8.name()) to LocalLlmNative.report()
    }
    private fun observation(): JSONObject {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val bm = context.getSystemService(BatteryManager::class.java)
        return JSONObject().put("elapsed_ms", SystemClock.elapsedRealtime()).put("pss_mib", memory.totalPss / 1024.0)
            .put("thermal_status", context.getSystemService(PowerManager::class.java).currentThermalStatus)
            .put("battery_temperature_c", (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0)
            .put("battery_percent", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("charge_counter_uah", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER))
            .put("plugged", battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0)
    }
    private fun metrics(): String {
        val r = JSONObject(LocalLlmNative.report()); val o = observation()
        return "Load %.1f s • prompt %.1f tok/s • generation %.1f tok/s • %.0f MiB PSS • battery %.1f°C • thermal %d".format(
            r.getDouble("load_ms") / 1000, r.getDouble("prompt_tps"), r.getDouble("generation_tps"), o.getDouble("pss_mib"), o.getDouble("battery_temperature_c"), o.getInt("thermal_status"))
    }
    fun benchmark(compareGpu: Boolean) = operation(if (compareGpu) "Starting CPU/GPU validation…" else "Starting CPU benchmark…") {
        mutable.update { it.copy(ready = false, gpuValidatedFor = null) }
        val report = JSONObject().put("llama_revision", "a97cce86a8addeb9f40cba7a261c94b1f0c576cb")
            .put("model", selected().name).put("sha256", selected().sha256).put("context", 2048).put("threads", 4)
            .put("device", android.os.Build.MODEL).put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("gpu_configuration", "no coopmat, coopmat2, coopmat2 decode vector, integer dot, f16, fusion, async; flash attention off")
            .put("start", observation()).put("scope", "Fixed prompts and settings only. Short, plugged-in runs do not establish battery life or sustained thermals.")
        val runs = JSONArray(); report.put("runs", runs)
        val cpu = mutableListOf<List<Int>>(); val gpu = mutableListOf<List<Int>>()
        try {
            for (useGpu in if (compareGpu) listOf(false, true) else listOf(false)) {
                mutable.update { it.copy(status = "Loading ${if (useGpu) "Vulkan" else "CPU"} for measurement…") }
                loadNative(useGpu)
                val prompts = if (compareGpu) comparisonPrompts.flatMap { listOf(it, it) } else comparisonPrompts + listOf(
                    "Read this note: Alice's meeting is in Cobalt Orchard at 3 pm. Which room is the meeting in? Answer only the room.",
                    "Return only a JSON object with keys color and count, with values blue and 3.",
                    "Give five practical steps for organizing a small desk. Keep each step to one sentence.",
                    "Write a short explanation of why the Moon has phases for a ten-year-old.")
                for ((index, prompt) in prompts.withIndex()) {
                    mutable.update { it.copy(status = "${if (useGpu) "Vulkan" else "CPU"}: prompt ${index + 1}/${prompts.size}…") }
                    val started = SystemClock.elapsedRealtime()
                    val (text, result) = infer(listOf(LocalTurn("user", prompt)), if (compareGpu) 12 else 128)
                    result.put("prompt", prompt).put("text", text).put("wall_ms", SystemClock.elapsedRealtime() - started).put("observation", observation())
                    runs.put(result)
                    val ids = result.getJSONArray("token_ids").let { ids -> List(ids.length()) { ids.getInt(it) } }
                    (if (useGpu) gpu else cpu).add(ids)
                    mutable.update { it.copy(metrics = metrics()) }
                }
                LocalLlmNative.unload()
            }
            val passed = compareGpu && validationPass(cpu, gpu)
            report.put("gpu_exact_match", if (compareGpu) passed else JSONObject.NULL)
            report.put("end", observation())
            mutable.update { it.copy(gpuValidatedFor = if (passed) selected().sha256 else null,
                validation = if (!compareGpu) it.validation else if (passed) "GPU passed both repeated fixed prompts. Broader correctness is not established." else "GPU failed exact token validation. GPU chat is disabled; load CPU to continue.",
                status = if (compareGpu) "Comparison complete. Choose a backend to load." else "CPU benchmark complete. Load CPU to chat.") }
        } catch (error: Throwable) {
            report.put("error", error.message).put("end", observation())
            mutable.update { it.copy(validation = if (compareGpu) "GPU validation did not complete. GPU chat is disabled." else it.validation) }
            throw error
        } finally {
            if (nativeInitialized) LocalLlmNative.unload()
            val text = report.toString(2)
            File(folder, "last-report.json").writeText(text)
            mutable.update { it.copy(report = text, ready = false) }
        }
    }
}
