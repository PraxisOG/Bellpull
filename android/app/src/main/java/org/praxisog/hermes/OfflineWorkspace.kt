package org.praxisog.hermes

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*

internal data class OfflineWorkspaceState(val forks: List<OfflineFork> = emptyList(), val current: OfflineFork? = null,
    val busy: Boolean = true, val status: String = "Opening saved chats…", val error: String? = null,
    val searchResults: List<OfflineChunk> = emptyList())

/** Separate route: local Send has no network path. Network use is explicit preparation or handoff. */
internal class OfflineWorkspace(private val context: Context, val profile: String, private val client: () -> HermesClient?) {
    private val storage = OfflineStorage(context, profile)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val lab = LocalLlmLab.get(context)
    private var encoder: SearchEncoder? = null
    @Volatile private var active = true
    private var operation: Job? = null
    private val mutable = MutableStateFlow(OfflineWorkspaceState())
    val state = mutable.asStateFlow()
    init { scope.launch {
        try {
            val forks = storage.forks().map { fork ->
                fork.copy(turns = fork.turns.map { if (it.status == "generating") it.copy(status = "interrupted") else it }).also {
                    if (it != fork) write { storage.save(it) }
                }
            }
            mutable.value = OfflineWorkspaceState(forks, busy = false, status = "Saved on this phone • Gemma 4 E2B CPU")
        } catch (e: Exception) { mutable.value = OfflineWorkspaceState(busy = false, error = e.message, status = "Could not open offline storage") }
    } }
    @Synchronized private fun action(label: String, task: suspend () -> Unit) {
        if (!active || state.value.busy) return
        mutable.update { it.copy(busy = true, error = null, status = label) }
        operation = scope.launch {
            try { task() } catch (e: Exception) {
                mutable.update { it.copy(error = e.message ?: "Operation failed", status = "Saved local chat retained") }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
    @Synchronized private fun save(fork: OfflineFork) {
        check(active) { "Profile closed" }
        storage.save(fork)
        mutable.update { it.copy(current = fork, forks = (it.forks.filterNot { f -> f.id == fork.id } + fork.copy(source = null)).sortedByDescending { f -> f.updatedAt }) }
    }
    @Synchronized private fun write(block: () -> Unit) { check(active) { "Profile closed" }; block() }
    fun open(id: String) = action("Opening local chat…") {
        val fork = checkNotNull(storage.fork(id)); mutable.update { it.copy(current = fork, searchResults = emptyList(), status = "Local • source and server chat are separate") }
    }
    fun back() { if (!state.value.busy) mutable.update { it.copy(current = null, searchResults = emptyList()) } }
    fun create(sourceId: String?, title: String = "Local conversation") = action("Creating local fork…") {
        val source = sourceId?.let { storage.source(it) ?: error("Open this chat online once or choose Prepare for offline to save its history.") }
        save(OfflineFork(title = source?.title ?: title, source = source, memory = storage.memory()))
        mutable.update { it.copy(status = "Fork saved. Search works now; Prepare for offline adds semantic search.") }
    }
    fun prepare(sourceId: String, title: String) = action("Saving full conversation…") {
        val api = client() ?: error("Connect to this profile to prepare its chat.")
        val history = when (val result = api.completeHistory(sourceId)) {
            is HermesResult.Ok -> result.value
            is HermesResult.Err -> error("Could not download the chat. Existing local copies remain available.")
        }
        val source = OfflineSource(sourceId, title, history.data)
        currentCoroutineContext().ensureActive()
        write { storage.save(source) }
        when (val result = api.offlineMemory()) { is HermesResult.Ok -> write { storage.save(result.value) }; else -> Unit }
        val fork = OfflineFork(title = title, source = source, memory = storage.memory())
        save(fork); index(fork)
        mutable.update { it.copy(status = "Prepared ${source.messages.size} messages for offline use. Add a brief if useful.") }
    }
    fun prepareCurrent() = action("Building semantic search…") {
        index(checkNotNull(state.value.current)); mutable.update { it.copy(status = "Semantic search ready on this phone") }
    }
    private suspend fun index(fork: OfflineFork) {
        val source = fork.source ?: return
        val old = storage.index(fork.id)?.takeIf { it.model == SearchEncoder.VERSION && it.revision == source.revision }
        val byId = old?.chunks.orEmpty().associateBy { it.id }
        val chunks = mutableListOf<OfflineChunk>()
        OfflineSearch.chunks(source).forEachIndexed { i, chunk ->
            currentCoroutineContext().ensureActive()
            check(active)
            val saved = byId[chunk.id]?.takeIf { it.text == chunk.text && it.vector.isNotEmpty() }
            val indexed = saved ?: chunk.copy(vector = encode(chunk.text))
            if (i % 16 == 0) mutable.update { it.copy(status = "Indexing source excerpt ${i + 1}…") }
            chunks.add(indexed)
            if (i % 32 == 31) write { storage.save(fork.id, OfflineIndex(source.revision, chunks = chunks.toList())) }
        }
        write { storage.save(fork.id, OfflineIndex(source.revision, chunks = chunks)) }
    }
    @Synchronized private fun encode(text: String) = (encoder ?: SearchEncoder(context).also { encoder = it }).encode(text)
    private fun chunks(fork: OfflineFork): List<OfflineChunk> = fork.source?.let { source ->
        val indexed = storage.index(fork.id)?.takeIf { it.model == SearchEncoder.VERSION && it.revision == source.revision }?.chunks.orEmpty().associateBy { it.id }
        OfflineSearch.chunks(source).map { indexed[it.id]?.takeIf { saved -> saved.text == it.text } ?: it }
    }.orEmpty()
    private fun search(fork: OfflineFork, query: String): List<OfflineChunk> {
        val chunks = chunks(fork)
        return OfflineSearch.search(chunks, query, if (chunks.any { it.vector.isNotEmpty() }) encode(query) else emptyList())
    }
    fun search(query: String) = action("Searching this source chat…") {
        val fork = checkNotNull(state.value.current)
        val hits = search(fork, query)
        mutable.update { it.copy(searchResults = hits, status = "${hits.size} source excerpts • ${if (hits.any { h -> h.vector.isNotEmpty() }) "semantic + keyword" else "keyword"} search") }
    }
    fun readChunk(id: String) = action("Opening source excerpt…") {
        val hits = chunks(checkNotNull(state.value.current)).filter { it.id == id }.take(1)
        mutable.update { it.copy(searchResults = hits, status = "Source excerpt [$id]") }
    }
    fun draft(value: String) {
        if (state.value.busy) return
        mutable.update { it.copy(current = it.current?.copy(draft = value.take(12000))) }
        // Capture the fork before navigation can clear/change the visible selection.
        // This serialized write is queued before a following Send/Open operation.
        val saved = state.value.current
        scope.launch { if (saved != null && active) runCatching { write { storage.save(saved) } } }
    }
    fun configure(thinking: Boolean, brief: String) = action("Saving settings…") {
        save(checkNotNull(state.value.current).copy(thinking = thinking, brief = brief.take(12000)))
    }
    fun refreshMemory() = action("Refreshing saved memory…") {
        val result = client()?.offlineMemory()
        if (result is HermesResult.Ok) write { storage.save(result.value) }
        save(checkNotNull(state.value.current).copy(memory = storage.memory()))
        mutable.update { it.copy(status = if (result is HermesResult.Ok) "Memory synced from Hermes" else "Using last locally saved memory snapshot") }
    }
    fun send() = action("Retrieving context…") {
        var fork = checkNotNull(state.value.current)
        check(fork.pending == null) { "Retry the pending handoff before adding more messages." }
        val text = fork.draft.trim(); require(text.isNotEmpty())
        fork = fork.copy(turns = fork.turns + OfflineTurn(role = "user", text = text) + OfflineTurn(role = "assistant", status = "generating"),
            draft = "", remoteId = null, updatedAt = System.currentTimeMillis())
        save(fork)
        try {
            var hits = search(fork, text)
            var resultText: String? = null
            var remaining = if (fork.thinking) 768 else 384
            var totalGenerated = 0
            var totalGenerationMs = 0.0
            var totalPromptMs = 0.0
            for (round in 0..2) {
                var persistedAt = 0L
                val generating = lab.offlineReply("$profile:${fork.id}", fork.thinking, remaining, { count ->
                    OfflinePrompt.build(fork, hits, resultText, round < 2 && fork.source != null, count)
                }) { raw ->
                    val output = OfflinePrompt.output(raw)
                    val turn = fork.turns.last().copy(text = output.answer, thought = output.thought,
                        citations = hits.map { it.id })
                    fork = fork.copy(turns = fork.turns.dropLast(1) + turn)
                    mutable.update { it.copy(current = fork, status = if (output.thought.isNotEmpty() && output.answer.isEmpty()) "Thinking on phone…" else "Generating on phone…") }
                    val now = System.currentTimeMillis()
                    if (now - persistedAt > 1000) { save(fork); persistedAt = now }
                }
                val result = try { generating.await() } catch (e: CancellationException) {
                    lab.stop()
                    withContext(NonCancellable) { runCatching { generating.await() } }
                    throw e
                }
                val output = OfflinePrompt.output(result.first)
                val report = JSONObject(result.second)
                val used = report.getInt("generated_tokens")
                remaining -= used; totalGenerated += used
                totalGenerationMs += report.getDouble("generation_ms"); totalPromptMs += report.getDouble("prompt_ms")
                report.put("total_generated_tokens", totalGenerated).put("total_generation_ms", totalGenerationMs)
                    .put("total_prompt_ms", totalPromptMs).put("generation_steps", round + 1)
                val tool = if (round < 2 && report.getString("finish") == "complete") OfflinePrompt.tool(output.answer) else null
                if (tool != null && remaining < 32) error("Local retrieval used the response budget. Try a shorter question or search the source manually.")
                if (tool == null) {
                    val finish = report.getString("finish")
                    save(fork.copy(turns = fork.turns.dropLast(1) + fork.turns.last().copy(text = output.answer,
                        thought = output.thought, status = finish, metrics = report.toString())))
                    mutable.update { it.copy(status = if (finish == "complete") "Saved locally" else "Stopped at $finish • reply may be incomplete") }
                    break
                }
                hits = if (tool.name == "search_chat") search(fork, tool.value)
                    else chunks(fork).filter { it.id == tool.value }.take(1)
                fork = fork.copy(turns = fork.turns.dropLast(1) + fork.turns.last().copy(tools = fork.turns.last().tools + tool.name))
                resultText = if (hits.isEmpty()) "No matching source excerpt. Say that this information is unavailable." else
                    hits.joinToString("\n") { "[${it.id}] ${it.text}" }
                mutable.update { it.copy(status = "Used ${tool.name} on this source chat") }
            }
        } catch (e: Exception) {
            if (active) save(fork.copy(turns = fork.turns.dropLast(1) + fork.turns.last().copy(status = "interrupted")))
            throw e
        }
    }
    fun stop() { if (state.value.busy) { lab.stop(); operation?.cancel() } }
    fun background() { stop(); lab.background() }
    fun handoff(onComplete: (String) -> Unit) = action("Sending reviewed local conversation to Hermes…") {
        var fork = checkNotNull(state.value.current)
        val api = client() ?: error("Reconnect to this server profile before handing off.")
        if (fork.pending == null) {
            val discovery = api.capabilities()
            check(discovery is HermesResult.Ok) { "Reconnect to this server before handing off. Your local chat remains editable." }
            check(discovery.value.features["bellpull_offline"] == true) {
                "This server needs Bellpull plugin 0.5.0 or newer for handoff. You can keep chatting locally."
            }
        }
        val request = fork.pending ?: OfflineHandoff(offlineId(), fork.source?.id, fork.title.take(200),
            fork.turns.filter { it.role == "user" || it.status == "complete" }.filter { it.text.isNotBlank() }
                .map { OfflineHandoffMessage(it.role, it.text) }, fork.brief).also { fork = fork.copy(pending = it); save(fork) }
        when (val result = api.offlineHandoff(request)) {
            is HermesResult.Ok -> {
                check(result.value.operationId == request.operationId)
                save(fork.copy(pending = null, remoteId = result.value.sessionId))
                mutable.update { it.copy(status = "Imported into a new Hermes conversation") }
                withContext(Dispatchers.Main) { onComplete(result.value.sessionId) }
            }
            is HermesResult.Err -> {
                val problem = result.error
                if (problem is HermesError.BadRequest || problem is HermesError.SessionNotFound ||
                    (problem is HermesError.ServerError && problem.status in listOf(400, 404, 413))) {
                    save(fork.copy(pending = null))
                    error("Hermes rejected the handoff. The source may have been removed or the transcript is too large. Your local chat remains editable.")
                }
                error("Handoff not confirmed. Retry to safely recover the same server conversation.")
            }
        }
    }
    fun delete() = action("Deleting local fork…") {
        val fork = checkNotNull(state.value.current); storage.delete(fork)
        mutable.update { it.copy(current = null, forks = it.forks.filterNot { f -> f.id == fork.id }, status = "Local fork deleted") }
    }
    @Synchronized fun close() {
        stop(); active = false; scope.cancel()
        // Encoder may still be in a native run; let its serialized worker finish before closing.
        CoroutineScope(Dispatchers.IO).launch { synchronized(this@OfflineWorkspace) { encoder?.close(); encoder = null } }
    }
}
