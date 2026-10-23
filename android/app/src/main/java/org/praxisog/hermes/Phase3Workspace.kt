package org.praxisog.hermes

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import java.io.File
import java.util.UUID

/** Owned by one connected profile. Every asynchronous operation captures its conversation. */
internal class Phase3Workspace(private val context: Context, val profileId: String,
    private val scope: CoroutineScope, private val client: HermesClient, private val cache: SessionCache,
    val filesSupported: Boolean, val modelsSupported: Boolean, val lifecycleSupported: Boolean = false,
    val runAttachmentsSupported: Boolean = filesSupported && modelsSupported) {
    val root = File(context.filesDir, "bellpull-files/$profileId").apply { mkdirs() }
    val thumbnails = ArtifactThumbnails(File(context.cacheDir, "artifact-thumbnails/$profileId"), client)
    var catalog by mutableStateOf<ModelCatalog?>(null); private set
    val selections = mutableStateMapOf<String, ModelSelection>()
    var defaultSelection by mutableStateOf(cache.defaultModel()); private set
    var drafts by mutableStateOf(cache.draftFiles()); private set
    var artifacts by mutableStateOf<List<Artifact>>(emptyList()); private set
    var storage by mutableStateOf<ArtifactStorage?>(null); private set
    var warnings by mutableStateOf<List<ArtifactWarning>>(emptyList()); private set
    val errors = mutableStateMapOf<String, String>()
    val progress = mutableStateMapOf<String, Float>()
    val sending = mutableStateMapOf<String, Boolean>()
    val importing = mutableStateMapOf<String, Int>()
    private val transfers = java.util.concurrent.ConcurrentHashMap<String, HermesClient.Streaming>()
    private val sendGenerations = mutableMapOf<String, Int>()
    private val imports = kotlinx.coroutines.sync.Mutex()
    @Volatile private var closed = false

    init {
        cleanPrivateFiles(root, File(context.cacheDir, "bellpull-shared/$profileId"), drafts.map { it.id }.toSet())
        if (modelsSupported) scope.launch { refreshCatalog() }
        if (filesSupported) refreshArtifacts()
    }
    private fun fail(id: String, result: HermesError) { if (!closed) errors[id] = result.toUserMessage() }
    private fun persist(values: List<DraftFile>): Boolean {
        if (closed || !cache.writeDraftFiles(values)) return false
        drafts = values
        return true
    }
    fun filesFor(sessionId: String?) = drafts.filter { it.sessionId == sessionId }
    private fun local(id: String): File { require(id.matches(Regex("[a-f0-9]{32}"))); return File(root, id) }

    suspend fun refreshCatalog() {
        when (val result = withContext(Dispatchers.IO) { client.modelCatalog() }) {
            is HermesResult.Ok -> if (!closed) { catalog = result.value; errors.remove("models") }
            is HermesResult.Err -> fail("models", result.error)
        }
    }
    fun refreshModels(id: String?) { scope.launch { refreshCatalog(); if (id != null) loadSelection(id) } }
    fun open(sessionId: String?) {
        if (sessionId == null || closed) return
        if (modelsSupported) scope.launch { loadSelection(sessionId) }
        if (filesSupported) refreshArtifacts()
    }
    private suspend fun loadSelection(id: String): ModelSelection? {
        return when (val result = withContext(Dispatchers.IO) { client.sessionModel(id) }) {
            is HermesResult.Ok -> if (!closed) result.value.selection.also { selections[id] = it } else null
            is HermesResult.Err -> { fail(id, result.error); null }
        }
    }
    fun choose(id: String?, value: ModelSelection, done: (Boolean) -> Unit) {
        if (id == null) {
            if (cache.writeDefaultModel(value)) { defaultSelection = value; done(true) }
            else { errors["models"] = "Could not save the default model."; done(false) }
            return
        }
        scope.launch {
            when (val result = withContext(Dispatchers.IO) { client.selectModel(id, value) }) {
                is HermesResult.Ok -> if (!closed) { selections[id] = result.value.selection; errors.remove(id); done(true) }
                is HermesResult.Err -> { fail(id, result.error); if (!closed) done(false) }
            }
        }
    }
    fun clearDefault() { if (cache.writeDefaultModel(null)) defaultSelection = null }
    fun applyDefaultToNewSession(id: String) { defaultSelection?.let { choose(id, it) {} } }

    fun addUris(sessionId: String, uris: List<Uri>) {
        if (closed) return
        if (!runAttachmentsSupported) {
            errors[sessionId] = "This server cannot receive chat attachments. Your draft is saved."
            return
        }
        importing[sessionId] = (importing[sessionId] ?: 0) + 1
        scope.launch {
            imports.lock()
            try { for (uri in uris) {
                if (closed) return@launch
                if (filesFor(sessionId).size >= 8) { errors[sessionId] = "Choose up to eight attachments."; break }
                val identity = UUID.randomUUID().toString().replace("-", "")
                val target = local(identity)
                val result = withContext(Dispatchers.IO) { runCatching {
                    val resolver = context.contentResolver
                    var name = "Attachment"
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
                    }
                    name = name.substringAfterLast('/').substringAfterLast('\\').filter { it.code >= 32 }.take(180).ifBlank { "Attachment" }
                    val mime = resolver.getType(uri)?.substringBefore(';') ?: "application/octet-stream"
                    require(mime in ALLOWED) { "This file type is not supported." }
                    var total = 0L
                    val source = resolver.openInputStream(uri) ?: error("Could not open the selected file.")
                    source.use { input -> target.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            if (closed) error("Server profile changed.")
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= LIMIT) { "File exceeds 25 MiB." }
                            output.write(buffer, 0, count)
                        }
                    } }
                    require(total > 0) { "The selected file is empty." }
                    DraftFile(identity, sessionId, name, mime, total)
                } }
                result.fold(onSuccess = { file ->
                    if (closed || !persist(drafts + file)) { target.delete(); if (!closed) errors[sessionId] = "Could not save the attachment draft." }
                    else errors.remove(sessionId)
                }, onFailure = { target.delete(); if (!closed) errors[sessionId] = it.message ?: "Could not read this file." })
            } } finally { imports.unlock(); if (!closed) importing[sessionId] = ((importing[sessionId] ?: 1) - 1).coerceAtLeast(0) }
        }
    }
    fun remove(id: String) {
        drafts.find { it.id == id }?.sessionId?.let { if (sending[it] == true) cancel(it) }
        transfers.remove(id)?.cancel()
        if (persist(drafts.filterNot { it.id == id })) { local(id).delete(); progress.remove(id) }
    }
    fun cancel(sessionId: String) {
        sendGenerations[sessionId] = (sendGenerations[sessionId] ?: 0) + 1
        filesFor(sessionId).forEach { transfers[it.id]?.cancel() }
        sending[sessionId] = false
        errors[sessionId] = "Upload cancelled. Your draft and files are saved."
    }
    fun accepted(record: RunSubmission) {
        val ids = record.bellpull?.artifactIds.orEmpty().toSet()
        if (persist(drafts.filterNot { it.id in ids })) ids.forEach { local(it).delete(); progress.remove(it) }
        refreshArtifacts()
    }
    fun send(store: HermesStore, text: String): Boolean {
        val id = store.openSessionId ?: return false
        if (sending[id] == true || (importing[id] ?: 0) > 0 || store.runPresentation?.busy == true || text.isBlank() || store.branchingSession ||
            store.turn is TurnState.Streaming || store.history !is UiState.Ready) return false
        val files = filesFor(id)
        if (files.isNotEmpty() && !runAttachmentsSupported) {
            errors[id] = "This server cannot receive chat attachments. Your draft and files are saved; nothing was sent."
            return false
        }
        if (!modelsSupported) return store.send(text)
        val revision = store.drafts.revision(id)
        val generation = (sendGenerations[id] ?: 0) + 1
        sendGenerations[id] = generation
        fun active() = !closed && sending[id] == true && sendGenerations[id] == generation
        sending[id] = true; errors.remove(id)
        scope.launch {
            try {
                val selected = selections[id] ?: loadSelection(id) ?: return@launch
                if (files.sumOf { it.size } > LIMIT) { errors[id] = "Combined attachments exceed 25 MiB."; return@launch }
                val route = catalog?.models?.find { it.model == selected.model && it.provider == selected.provider }
                if (files.any { it.mime.startsWith("image/") } && route?.vision != true) {
                    errors[id] = "Select a model with confirmed vision support before sending images."; return@launch
                }
                for (file in files) {
                    if (!active()) return@launch
                    if (file.uploaded) continue
                    val result = withContext(Dispatchers.IO) { client.uploadArtifact(local(file.id), file,
                        onStart = { handle -> transfers[file.id] = handle; if (closed) handle.cancel() },
                        onProgress = { bytes -> scope.launch { if (active() && drafts.any { it.id == file.id })
                            progress[file.id] = bytes.toFloat() / file.size } }) }
                    transfers.remove(file.id)
                    if (!active()) return@launch
                    when (result) {
                        is HermesResult.Err -> { fail(id, result.error); return@launch }
                        is HermesResult.Ok -> if (!persist(drafts.map { if (it.id == file.id) it.copy(uploaded = true) else it })) {
                            errors[id] = "Could not save upload progress. Retry uses the same file identity."; return@launch
                        }
                    }
                }
                if (!active()) return@launch
                if (store.openSessionId != id || store.drafts.revision(id) != revision) {
                    errors[id] = "Files are ready. Review this conversation's draft and send when ready."; return@launch
                }
                store.send(text, BellpullRunOptions(selection = selected, artifactIds = files.map { it.id }))
            } finally { if (!closed && sendGenerations[id] == generation) sending[id] = false }
        }
        return true
    }
    fun refreshArtifacts() {
        if (!filesSupported || closed) return
        scope.launch {
            when (val result = withContext(Dispatchers.IO) { client.artifacts() }) {
                is HermesResult.Ok -> if (!closed) {
                    artifacts = result.value.data; storage = result.value.storage
                    warnings = result.value.warnings; errors.remove("artifacts")
                }
                is HermesResult.Err -> fail("artifacts", result.error)
            }
        }
    }
    fun deleteArtifact(item: Artifact) {
        if (!lifecycleSupported || closed) return
        scope.launch {
            when (val result = withContext(Dispatchers.IO) { client.deleteArtifact(item.id) }) {
                is HermesResult.Ok -> if (!closed) { File(root, "download-${item.id}").delete(); refreshArtifacts() }
                is HermesResult.Err -> fail("artifacts", result.error)
            }
        }
    }
    suspend fun download(item: Artifact): File? {
        val target = File(root, "download-${item.id}")
        val temporary = File.createTempFile("download-", ".tmp", root)
        try {
            val result = withContext(Dispatchers.IO) { client.downloadArtifact(item, temporary) {
                transfers[item.id] = it; if (closed) it.cancel()
            } }
            if (closed) return null
            return when (result) {
                is HermesResult.Ok -> if (temporary.renameTo(target)) target else null
                is HermesResult.Err -> { fail(item.id, result.error); null }
            }
        } finally { temporary.delete(); transfers.remove(item.id) }
    }
    fun cancelDownload(id: String) { transfers[id]?.cancel() }
    fun close() { closed = true; thumbnails.close(); transfers.values.forEach { it.cancel() }; transfers.clear() }
    companion object {
        const val LIMIT = 25L * 1024 * 1024
        val ALLOWED = setOf("image/png", "image/jpeg", "image/webp", "application/pdf", "text/plain", "text/markdown",
            "text/csv", "application/json", "application/zip", "application/octet-stream",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation")
    }
}
