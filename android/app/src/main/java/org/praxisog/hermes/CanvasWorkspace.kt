package org.praxisog.hermes

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import java.util.UUID

internal class CanvasWorkspace(private val scope: CoroutineScope, private val client: HermesClient,
    private val cache: SessionCache, supported: Boolean) {
    var supported by mutableStateOf(supported)
    var visible by mutableStateOf(false)
    var fullScreen by mutableStateOf(false)
    var snapshot by mutableStateOf<CanvasSnapshot?>(null); private set
    var connected by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var problem by mutableStateOf<String?>(null); private set
    private var session: String? = null
    private var generation = 0
    private var job: Job? = null
    @Volatile private var transport: HermesClient.Streaming? = null
    private var closed = false
    fun observe(id: String?, active: Boolean) {
        transport?.cancel(); transport = null; job?.cancel(); generation++
        if (id != session) { session = id; snapshot = id?.let(cache::readCanvas); problem = null; busy = false }
        connected = false
        if (closed || id == null || !supported || !active) return
        val token = generation
        job = scope.launch {
            while (isActive && !closed && token == generation) {
                val result = withContext(Dispatchers.IO) {
                    client.canvasEvents(id, { stream -> transport = stream; if (closed || token != generation) stream.cancel() }) { value ->
                        runBlocking { withContext(Dispatchers.Main.immediate) { accept(value, id, token) }; delay(20) }
                    }
                }
                ensureActive()
                if (token != generation) return@launch
                connected = false
                problem = result.errorOrNull()?.toUserMessage() ?: "Canvas connection ended. Reconnecting…"
                delay(2000)
            }
        }
    }
    private fun accept(value: CanvasSnapshot, id: String, token: Int) {
        if (closed || token != generation || session != id) return
        val old = snapshot
        if (old != null && old.scope != value.scope) { connected = false; problem = "The server canvas identity changed. Review this connection."; return }
        if (old != null && value.cursor < old.cursor) return
        snapshot = value; connected = true; problem = null; cache.writeCanvas(id, value)
    }
    fun mutate(action: String, canvasId: String? = snapshot?.document?.id, extra: JsonObject = buildJsonObject {}, onSettled: (Boolean) -> Unit = {}) {
        val id = session
        if (id == null || closed || !connected || busy || (action != "create" && canvasId == null)) { onSettled(false); return }
        val token = generation; val revision = snapshot?.document?.revision
        val body = buildJsonObject {
            put("action", action); put("canvas_id", canvasId ?: UUID.randomUUID().toString()); put("operation_id", UUID.randomUUID().toString())
            if (action !in setOf("create", "select")) put("base_revision", revision ?: 0)
            extra.forEach { (key, value) -> put(key, value) }
        }
        busy = true; problem = null
        scope.launch {
            var confirmed = false
            try {
                val response = withContext(Dispatchers.IO) { client.canvasEdit(id, body) }
                if (closed || token != generation) return@launch
                when (response) {
                    is HermesResult.Ok -> { accept(response.value, id, token); confirmed = true }
                    is HermesResult.Err -> {
                        val current = withContext(Dispatchers.IO) { client.canvas(id) }
                        current.valueOrNull()?.let { accept(it, id, token) }
                        if (token == generation && current is HermesResult.Err) connected = false
                        if (token == generation) problem = "Edit was not confirmed or conflicted. Review the drawing before trying again. " + response.error.toUserMessage()
                    }
                }
            } finally { if (token == generation) busy = false; onSettled(confirmed) }
        }
    }
    fun edit(operations: List<JsonObject>, revision: Long? = null, onSettled: (Boolean) -> Unit = {}) = mutate("edit", extra = buildJsonObject {
        put("operations", JsonArray(operations)); revision?.let { put("base_revision", it) }
    }, onSettled = onSettled)
    fun prepare(text: String): String = if (visible && supported) canvasPrompt(text) else text
    fun close() { closed = true; generation++; transport?.cancel(); job?.cancel(); connected = false }
}
