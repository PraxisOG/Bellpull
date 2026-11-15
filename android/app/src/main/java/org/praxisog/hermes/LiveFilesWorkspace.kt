package org.praxisog.hermes

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import java.util.UUID

/** A profile owns this workspace. Pending writes retain their identity across network loss/restarts. */
internal class LiveFilesWorkspace(private val scope: CoroutineScope, private val client: HermesClient,
    supported: Boolean, private val readPending: () -> PendingFileEdit? = { null },
    private val savePending: (PendingFileEdit?) -> Boolean = { true }) {
    var supported by mutableStateOf(supported)
    var entries by mutableStateOf(emptyList<LiveFile>()); private set
    var selectedId by mutableStateOf<String?>(null); private set
    var snapshot by mutableStateOf<LiveFileSnapshot?>(null); private set
    var problem by mutableStateOf<String?>(null); private set
    var loading by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var fresh by mutableStateOf(false); private set
    var pending by mutableStateOf(readPending()); private set
    private var closed = false
    private var revision = 0
    private var refreshJob: Job? = null
    private var refreshAgain = false
    val selected get() = entries.firstOrNull { it.id == selectedId }
    init { refresh() }
    fun open(id: String) {
        if (id != selectedId) { selectedId = id; snapshot = null; fresh = false; revision++ }
        refresh()
    }
    fun refresh() {
        if (closed || !supported) return
        if (refreshJob?.isActive == true || busy) { refreshAgain = true; return }
        refreshJob = scope.launch {
            do {
                refreshAgain = false; loading = true; fresh = false
                val id = selectedId; val token = revision
                val list = withContext(Dispatchers.IO) { client.liveFiles() }
                ensureActive(); if (closed) return@launch
                if (list is HermesResult.Ok) entries = list.value.files
                else problem = list.errorOrNull()?.toUserMessage()
                if (id != null && id == selectedId && token == revision) {
                    val result = withContext(Dispatchers.IO) { client.liveFile(id, snapshot?.etag) }
                    ensureActive(); if (closed) return@launch
                    if (token == revision && !busy) when (result) {
                        is HermesResult.Ok -> { result.value?.let { snapshot = it }; fresh = true; if (pending == null) problem = null }
                        is HermesResult.Err -> problem = result.error.toUserMessage()
                    }
                } else if (list is HermesResult.Ok && pending == null) problem = null
                loading = false
            } while (refreshAgain && !busy)
        }
    }
    fun pin(file: LiveFile) {
        if (closed || busy) return
        scope.launch {
            val result = withContext(Dispatchers.IO) { client.pinLiveFile(file.id, !file.pinned) }
            ensureActive(); if (closed) return@launch
            problem = result.errorOrNull()?.toUserMessage(); refresh()
        }
    }
    fun check(line: Int, checked: Boolean) = edit(buildJsonObject { put("action", "check"); put("line", line); put("checked", checked) })
    fun add(text: String, afterLine: Int? = snapshot?.tasks?.lastOrNull()?.line) = edit(buildJsonObject {
        put("action", "add"); put("text", text); afterLine?.let { put("after_line", it) }
    })
    private fun edit(fields: JsonObject): Boolean {
        val file = snapshot ?: return false
        if (closed || busy || pending != null || !fresh || loading || file.access != "checklist") return false
        val value = PendingFileEdit(file.id, buildJsonObject {
            put("operation_id", UUID.randomUUID().toString().replace("-", "")); put("base_etag", file.etag)
            fields.forEach { (key, value) -> put(key, value) }
        })
        if (!savePending(value)) { problem = "Could not save this edit for a safe retry."; return false }
        pending = value; retryEdit()
        return true
    }
    fun retryEdit() {
        val value = pending ?: return
        if (closed || busy) return
        busy = true; fresh = false; problem = null; revision++
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { client.editLiveFile(value.fileId, value.body) }
                ensureActive(); if (closed) return@launch
                when (result) {
                    is HermesResult.Ok -> {
                        if (savePending(null)) pending = null
                        if (selectedId == value.fileId) { snapshot = result.value; fresh = true }
                    }
                    is HermesResult.Err -> problem = result.error.toUserMessage()
                }
            } finally { busy = false; if (!closed) refresh() }
        }
    }
    fun reviewEdit() {
        if (busy || closed || pending == null) return
        // The UI makes clear that forgetting a retry never undoes a server write.
        if (savePending(null)) { pending = null; problem = null; refresh() }
    }
    fun close() { closed = true; revision++; refreshJob?.cancel() }
}
