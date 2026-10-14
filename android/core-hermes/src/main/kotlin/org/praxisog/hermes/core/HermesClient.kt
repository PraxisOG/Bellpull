package org.praxisog.hermes.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.encodeToJsonElement
import org.praxisog.hermes.model.*
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.praxisog.hermes.model.HermesError
import org.praxisog.hermes.model.HermesResult
import org.praxisog.hermes.model.MessageHistory
import org.praxisog.hermes.model.RunStatus
import org.praxisog.hermes.model.RunAdmission
import org.praxisog.hermes.model.Session
import org.praxisog.hermes.model.SessionCreated
import org.praxisog.hermes.model.SessionList
import org.praxisog.hermes.model.StreamEvent
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Client for the Hermes Agent API server. Covers exactly the endpoints in
 * `docs/hermes-api.md`; nothing here is guessed.
 *
 * Every call returns a [HermesResult] carrying a typed [HermesError] on failure. Nothing
 * throws for an expected condition, and no method returns a bare null to mean "something
 * went wrong".
 *
 * The bearer key is supplied per request by [keyProvider] rather than held in a field, so
 * the caller keeps ownership of its lifetime (it lives in EncryptedSharedPreferences). It is
 * never logged, never put in a URL, and never included in an error message — the 401 body
 * from Hermes mentions only the env var name, and that is what propagates.
 */
class HermesClient(
    baseUrl: String,
    private val keyProvider: () -> String,
    /**
     * Stable value for `X-Hermes-Session-Key`, which scopes long-term memory independently of
     * the transcript session id. Max 256 chars per the contract. Null omits the header.
     */
    private val memoryScopeKey: String? = null,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val base = baseUrl.trimEnd('/')

    /** Same pool and settings as [httpClient], minus the read timeout. See [streamingClient]. */
    private val streamingHttpClient = streamingClient(httpClient)
    private val calls = mutableSetOf<Call>()
    private var closed = false
    @Volatile private var snapshotPaging = false
    fun configureCapabilities(value: Capabilities) {
        snapshotPaging = value.features["bellpull_snapshot_paging"] == true
    }

    /** Ends this profile's transports without requesting that remote runs stop. */
    fun close() = synchronized(calls) {
        closed = true
        calls.forEach { it.cancel() }
        calls.clear()
    }

    private fun trackedCall(client: OkHttpClient, request: Request): Call = synchronized(calls) {
        client.newCall(request).also { if (closed) it.cancel() else calls.add(it) }
    }
    private fun finished(call: Call) { synchronized(calls) { calls.remove(call) } }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    init {
        require(memoryScopeKey == null || memoryScopeKey.length <= 256) {
            "X-Hermes-Session-Key is capped at 256 characters"
        }
    }

    // ---- endpoints ------------------------------------------------------------

    /** Optional bellpull_gpu Hermes plugin. A 404 means it is not installed. */
    fun gpuTemperatures(): HermesResult<org.praxisog.hermes.model.GpuTelemetry> =
        get("/api/bellpull/plugins/gpu") { json.decodeFromString(it) }

    fun modelCatalog(): HermesResult<ModelCatalog> = get("/api/bellpull/models") { json.decodeFromString(it) }
    fun codingActivity(sessionId: String, after: Long? = null): HermesResult<CodingSnapshot> {
        require(after == null || after >= 0)
        return get("/api/bellpull/sessions/${urlPart(sessionId)}/coding" + (after?.let { "?after=$it" } ?: ""), sessionId) {
            json.decodeFromString<CodingSnapshot>(it).validated(sessionId)
        }
    }
    fun codingWorkspace(workId: String, fileId: String? = null): HermesResult<CodingWorkspaceSnapshot> {
        require(workId.matches(Regex("[a-f0-9]{32}")) && (fileId == null || fileId.matches(Regex("[a-f0-9]{64}"))))
        return get("/api/bellpull/coding/$workId/workspace" + (fileId?.let { "?file=$it" } ?: "")) {
            json.decodeFromString<CodingWorkspaceSnapshot>(it).also { value ->
                require(value.version == 1 && value.files.size <= 200 && value.files.map { f -> f.id }.distinct().size == value.files.size)
                require(value.files.all { f -> f.id.matches(Regex("[a-f0-9]{64}")) && f.path.length <= 4096 })
                require((value.diff?.length ?: 0) <= 262144)
                require(fileId == null || value.file?.id == fileId)
            }
        }
    }
    fun planning(): HermesResult<PlanningSnapshot> = get("/api/bellpull/planning") { json.decodeFromString<PlanningSnapshot>(it).validated() }
    fun planningAction(kind: String, body: JsonObject): HermesResult<JsonObject> {
        require(kind in setOf("reminders","routines","proposals"))
        return post("/api/bellpull/planning/$kind",body.toString()) { json.parseToJsonElement(it).jsonObject }
    }
    fun shareCalendar(device: String, body: JsonObject): HermesResult<JsonObject> {
        require(device.matches(Regex("[a-f0-9]{32}")))
        return execute(authed("/api/bellpull/calendar/$device").put(body.toString().toRequestBody(JSON_MEDIA)).build(),null) { json.parseToJsonElement(it).jsonObject }
    }
    private fun projectSnapshot(body: String): ProjectSnapshot {
        val raw = json.parseToJsonElement(body).jsonObject
        require(raw["version"]?.jsonPrimitive?.intOrNull == 1 &&
            (raw["revision"]?.jsonPrimitive?.longOrNull ?: -1) >= 0 &&
            raw["projects"] is kotlinx.serialization.json.JsonArray && raw["memberships"] is kotlinx.serialization.json.JsonArray)
        val value = json.decodeFromString<ProjectSnapshot>(body)
        val ids = value.projects.map { it.id }
        require(ids.size == ids.toSet().size)
        val aliases = mutableSetOf<String>()
        value.memberships.forEach { member ->
            require(member.projectId == null || member.projectId in ids)
            (member.sessionIds + member.sessionId).toSet().forEach { require(aliases.add(it)) }
        }
        return value
    }
    fun projects(): HermesResult<ProjectSnapshot> = get("/api/bellpull/projects", decode = ::projectSnapshot)
    fun mutateProject(value: ProjectMutation): HermesResult<ProjectSnapshot> =
        post("/api/bellpull/projects", json.encodeToString(value), decode = ::projectSnapshot)
    fun createProjectChat(value: PendingProjectChat): HermesResult<Session> {
        require(value.projectId.matches(Regex("[a-f0-9]{32}")))
        val payload = buildJsonObject {
            put("id", value.id); put("expected_revision", value.expectedRevision)
            value.title?.let { put("title", it) }
            value.selection?.let {
                put("model", it.model); put("provider", it.provider)
                put("model_options", json.encodeToJsonElement(it.options)); put("require_model_lock", true)
            }
        }.toString()
        return post("/api/bellpull/projects/${value.projectId}/sessions", payload) {
            json.decodeFromString<SessionCreated>(it).session
        }
    }
    fun sessionModel(sessionId: String): HermesResult<SessionModel> =
        get("/api/bellpull/sessions/$sessionId/model", sessionId) { json.decodeFromString(it) }
    fun selectModel(sessionId: String, selection: ModelSelection): HermesResult<SessionModel> =
        execute(authed("/api/bellpull/sessions/$sessionId/model")
            .put(json.encodeToString(selection).toRequestBody(JSON_MEDIA)).build(), sessionId) { json.decodeFromString(it) }
    fun artifacts(sessionId: String? = null): HermesResult<ArtifactList> =
        get("/api/bellpull/artifacts" + (sessionId?.let { "?session_id=" + urlPart(it) } ?: "")) { json.decodeFromString(it) }

    fun deleteArtifact(id: String): HermesResult<Unit> {
        require(id.matches(Regex("[a-f0-9]{32}")))
        return execute(authed("/api/bellpull/artifacts/$id").delete().build(), null) { Unit }
    }

    fun uploadArtifact(file: java.io.File, draft: DraftFile, onStart: (Streaming) -> Unit,
        onProgress: (Long) -> Unit): HermesResult<Artifact> {
        require(draft.id.matches(Regex("[a-f0-9]{32}")))
        val body = object : okhttp3.RequestBody() {
            override fun contentType() = draft.mime.toMediaType()
            // The plugin bounds streaming bytes itself. Chunking preserves stock
            // Hermes' smaller JSON Content-Length ceiling on native routes.
            override fun contentLength() = -1L
            override fun writeTo(sink: okio.BufferedSink) {
                file.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    var sent = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count); sent += count; onProgress(sent)
                    }
                }
            }
        }
        val request = authed("/api/bellpull/artifacts/${draft.id}?session_id=${urlPart(draft.sessionId)}&name=${urlPart(draft.name)}")
            .put(body).build()
        val call = trackedCall(httpClient, request)
        onStart(Streaming(call))
        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) HermesResult.Err(mapHttpError(response, draft.sessionId))
                else runCatching { HermesResult.Ok(json.decodeFromString<Artifact>(response.body.string())) }
                    .getOrElse { HermesResult.Err(HermesError.MalformedResponse("Invalid attachment metadata")) }
            }
        } catch (e: IOException) { HermesResult.Err(mapIoError(e)) }
        finally { finished(call) }
    }

    /** Caller owns a private temporary file; publish it only after byte count/hash checks. */
    fun downloadArtifact(artifact: Artifact, destination: java.io.File, onStart: (Streaming) -> Unit): HermesResult<Unit> {
        require(artifact.id.matches(Regex("[a-f0-9]{32}")))
        val call = trackedCall(httpClient, authed("/api/bellpull/artifacts/${artifact.id}/content").get().build())
        onStart(Streaming(call))
        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) return@use HermesResult.Err(mapHttpError(response, artifact.sessionId))
                val hash = java.security.MessageDigest.getInstance("SHA-256")
                var size = 0L
                response.body.byteStream().use { input -> destination.outputStream().use { output ->
                    val bytes = ByteArray(65536)
                    while (true) {
                        val count = input.read(bytes)
                        if (count < 0) break
                        size += count
                        if (size > 25 * 1024 * 1024 || size > artifact.size) throw IOException("Attachment exceeds expected size")
                        hash.update(bytes, 0, count); output.write(bytes, 0, count)
                    }
                } }
                val digest = hash.digest().joinToString("") { "%02x".format(it) }
                if (size != artifact.size || digest != artifact.sha256) throw IOException("Attachment verification failed")
                HermesResult.Ok(Unit)
            }
        } catch (e: IOException) { HermesResult.Err(mapIoError(e)) }
        finally { finished(call) }
    }

    private fun urlPart(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    /**
     * `GET /v1/capabilities`. Returns the feature flags. This is the cheapest authenticated
     * call, so it doubles as the connection check.
     */
    fun capabilities(): HermesResult<Capabilities> {
        val native = get("/v1/capabilities") { body ->
        val obj = json.parseToJsonElement(body).jsonObject
        val features = obj["features"]?.jsonObject ?: JsonObject(emptyMap())
        Capabilities(
            raw = obj,
            features = features.mapValues { (_, v) ->
                runCatching { v.jsonPrimitive.booleanOrNull ?: false }.getOrDefault(false)
            },
        )
        }
        if (native !is HermesResult.Ok) return native
        val plugin = get("/api/bellpull/capabilities") { json.parseToJsonElement(it).jsonObject }
        if (plugin !is HermesResult.Ok) {
            configureCapabilities(native.value)
            return native
        }
        val flags = (plugin.value["features"] as? JsonObject).orEmpty()
        val supported = (plugin.value["version"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull == 1
        val pluginFeatures = setOf("session_fork_at_message", "session_fork_before_message", "bellpull_model_controls",
            "bellpull_persistent_artifacts", "bellpull_notification_events", "bellpull_snapshot_paging", "bellpull_artifact_lifecycle", "bellpull_run_attachments", "bellpull_reasoning_streaming", "bellpull_projects", "bellpull_conversation_pins", "bellpull_coding_activity", "bellpull_planning", "bellpull_routines")
        val pluginFlags = pluginFeatures.associateWith { name -> kotlinx.serialization.json.JsonPrimitive(
            supported && (flags[name] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull == true) }
        val merged = JsonObject((native.value.raw["features"] as? JsonObject).orEmpty() + pluginFlags)
        val result = Capabilities(JsonObject(native.value.raw + ("features" to merged) + ("bellpull_plugin" to plugin.value)),
            native.value.features + pluginFlags.mapValues { it.value.jsonPrimitive.booleanOrNull == true })
        configureCapabilities(result)
        return HermesResult.Ok(result)
    }

    /**
     * `GET /api/sessions`.
     *
     * [includeChildren] defaults to false to omit nested agent sessions. Omitting [source]
     * includes conversations from every platform in the authenticated Hermes profile.
     */
    fun listSessions(
        limit: Int? = null,
        offset: Int? = null,
        source: String? = null,
        includeChildren: Boolean = false,
    ): HermesResult<SessionList> {
        val params = buildList {
            limit?.let { add("limit=$it") }
            offset?.let { add("offset=$it") }
            source?.let { add("source=$it") }
            add("include_children=$includeChildren")
        }
        return get("/api/sessions?" + params.joinToString("&")) { json.decodeFromString(it) }
    }

    /** The native list uses mutable recency offsets. Verify the head after paging and
     * restart if activity moved it; never present a truncated page as complete. */
    fun allSessions(source: String? = null): HermesResult<SessionList> {
        if (snapshotPaging) return snapshotSessions(source)
        repeat(3) {
            val rows = linkedMapOf<String, Session>()
            var offset = 0
            var head: List<Pair<String, Double?>>? = null
            while (offset < 100_000) {
                val result = listSessions(limit = 200, offset = offset, source = source)
                if (result is HermesResult.Err) return result
                val page = (result as HermesResult.Ok).value
                if (offset == 0) head = page.data.map { it.id to it.lastActive }
                val previousSize = rows.size
                page.data.forEach { rows[it.id] = it }
                if (!page.hasMore) {
                    if (offset == 0) return HermesResult.Ok(page.copy(data = rows.values.toList()))
                    val check = listSessions(limit = 200, offset = 0, source = source)
                    if (check is HermesResult.Err) return check
                    if ((check as HermesResult.Ok).value.data.map { it.id to it.lastActive } == head)
                        return HermesResult.Ok(page.copy(data = rows.values.toList(), offset = 0, hasMore = false))
                    break
                }
                if (rows.size == previousSize) return HermesResult.Err(HermesError.MalformedResponse("Conversation paging did not advance."))
                offset += 200
            }
        }
        return HermesResult.Err(HermesError.MalformedResponse("Conversations changed while loading. Refresh to try again."))
    }

    /** `POST /api/sessions`. Unwraps the `session` envelope. */
    fun createSession(title: String? = null, selection: ModelSelection? = null): HermesResult<Session> {
        val payload = buildJsonObject {
            title?.let { put("title", it) }
            selection?.let {
                put("model", it.model); put("provider", it.provider)
                put("model_options", json.encodeToJsonElement(it.options)); put("require_model_lock", true)
            }
        }.toString()
        return post("/api/sessions", payload) {
            json.decodeFromString<SessionCreated>(it).session
        }
    }

    /** Requires the explicitly advertised session_fork_at_message server extension. */
    fun branchSession(sessionId: String, throughMessageId: Int): HermesResult<Session> {
        require(throughMessageId > 0)
        val payload = buildJsonObject { put("through_message_id", throughMessageId) }.toString()
        return post("/api/sessions/$sessionId/fork-at-message", payload, sessionId) {
            json.decodeFromString<SessionCreated>(it).session
        }
    }

    /** Branch before a saved user prompt; preserves the source's routing and prior history. */
    fun branchBeforeMessage(sessionId: String, messageId: Int): HermesResult<Session> {
        require(messageId > 0)
        val payload = buildJsonObject { put("before_message_id", messageId) }.toString()
        return post("/api/sessions/$sessionId/fork-before-message", payload, sessionId) {
            json.decodeFromString<SessionCreated>(it).session
        }
    }

    /** `GET /api/sessions/{id}/messages`. */
    fun messages(sessionId: String, limit: Int? = null, offset: Int? = null, order: String? = null): HermesResult<MessageHistory> {
        val params = buildList {
            limit?.let { add("limit=$it") }; offset?.let { add("offset=$it") }; order?.let { add("order=$it") }
        }.joinToString("&")
        return get("/api/sessions/$sessionId/messages" + if (params.isEmpty()) "" else "?$params", sessionId) { json.decodeFromString(it) }
    }

    /** Ascending append-only pages keep newly arriving messages from shifting older
     * offsets. Compression changes the resolved identity: restart against that lineage. */
    fun completeHistory(sessionId: String): HermesResult<MessageHistory> {
        if (snapshotPaging) return snapshotHistory(sessionId)
        repeat(3) {
            val rows = linkedMapOf<Int, Message>()
            var resolved: String? = null
            var offset = 0
            while (offset < 1_000_000) {
                val result = messages(sessionId, limit = 500, offset = offset, order = "oldest")
                if (result is HermesResult.Err) return result
                val page = (result as HermesResult.Ok).value
                if (offset == 0) resolved = page.sessionId
                if (resolved != page.sessionId) break
                val previousSize = rows.size
                page.data.forEach { rows[it.id] = it }
                if (page.data.size < 500) return HermesResult.Ok(page.copy(data = rows.values.toList(),
                    pagination = Pagination(limit = rows.size, offset = 0, order = "oldest", returned = rows.size)))
                if (previousSize == rows.size) return HermesResult.Err(HermesError.MalformedResponse("History paging did not advance."))
                offset += page.data.size
            }
        }
        return HermesResult.Err(HermesError.MalformedResponse("Conversation history changed while loading. Refresh to try again."))
    }

    private fun snapshotSessions(source: String?): HermesResult<SessionList> {
        val rows = linkedMapOf<String, Session>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val params = listOfNotNull(source?.let { "source=${urlPart(it)}" }, cursor?.let { "cursor=${urlPart(it)}" }).joinToString("&")
            val result = get("/api/bellpull/sessions?$params") { json.decodeFromString<SessionList>(it) }
            if (result !is HermesResult.Ok) return result
            val page = result.value
            page.data.forEach { rows[it.id] = it }
            cursor = page.nextCursor
            if (cursor == null && page.hasMore) return HermesResult.Err(HermesError.MalformedResponse("Missing conversation page cursor."))
            if (cursor != null && !seen.add(cursor)) return HermesResult.Err(HermesError.MalformedResponse("Conversation paging did not advance."))
            if (cursor == null) return HermesResult.Ok(page.copy(data = rows.values.toList(), offset = 0))
        } while (seen.size < 10000)
        return HermesResult.Err(HermesError.MalformedResponse("Conversation snapshot has too many pages."))
    }

    private fun snapshotHistory(sessionId: String): HermesResult<MessageHistory> {
        val rows = linkedMapOf<Int, Message>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val result = get("/api/bellpull/sessions/${urlPart(sessionId)}/messages" + (cursor?.let { "?cursor=${urlPart(it)}" } ?: ""), sessionId) {
                json.decodeFromString<MessageHistory>(it)
            }
            if (result !is HermesResult.Ok) return result
            val page = result.value
            page.data.forEach { rows[it.id] = it }
            cursor = page.pagination?.nextCursor
            if (cursor != null && !seen.add(cursor)) return HermesResult.Err(HermesError.MalformedResponse("History paging did not advance."))
            if (cursor == null) {
                if (page.pagination?.total?.let { it != rows.size } == true)
                    return HermesResult.Err(HermesError.MalformedResponse("History snapshot ended before all messages arrived."))
                return HermesResult.Ok(page.copy(data = rows.values.toList(),
                    pagination = Pagination(rows.size, 0, "oldest", rows.size, total = rows.size)))
            }
        } while (seen.size < 10000)
        return HermesResult.Err(HermesError.MalformedResponse("History snapshot has too many pages."))
    }

    /**
     * `POST /api/sessions/{id}/chat/stream`. Blocks on the calling thread, invoking [onEvent]
     * for each frame as it arrives. Run it off the main thread; cancel by calling [Streaming.cancel]
     * on the handle passed to [onStart].
     *
     * Returns [HermesError.StreamTruncated] when the stream ends without a terminal event —
     * which is what a dropped connection looks like. That is a failure even though events
     * were delivered, because the turn may still be running server-side and must not be
     * rendered as finished.
     *
     * No `Idempotency-Key` is sent. The header is documented for `/v1/runs`, and
     * `docs/hermes-api.md` is explicit that dedupe for this endpoint is **not verified** — so
     * sending it would imply a retry safety that has not been established.
     */
    fun streamTurn(
        sessionId: String,
        message: String,
        onStart: (Streaming) -> Unit = {},
        onEvent: (StreamEvent) -> Unit,
    ): HermesResult<Unit> {
        require(message.isNotBlank()) { "message must not be blank; Hermes rejects it with 400 missing_message" }
        val request = authed("/api/sessions/$sessionId/chat/stream")
            .post(json.encodeToString(mapOf("message" to message)).toRequestBody(JSON_MEDIA))
            .header("Accept", "text/event-stream")
            .build()

        val call = trackedCall(streamingHttpClient, request)
        try {
        onStart(Streaming(call))

        // Delivered outside the read so a mid-stream failure still reports how far it got.
        var delivered = 0
        var last: StreamEvent? = null

        val response = try {
            call.execute()
        } catch (e: IOException) {
            // Failed before any response: the server was never reached.
            return HermesResult.Err(mapIoError(e))
        }

        return response.use {
            if (!response.isSuccessful) {
                return HermesResult.Err(mapHttpError(response, sessionId))
            }
            try {
                SseReader(response.body.source()).read { event ->
                    last = event
                    delivered++
                    onEvent(event)
                }
            } catch (e: IOException) {
                // The connection died partway through. This is NOT an unreachable server --
                // we were connected and received [delivered] events, and the turn may still
                // be running server-side. Reporting it as a transport failure would be a lie
                // and would make a truncated turn look like it never started.
                return HermesResult.Err(
                    HermesError.StreamTruncated(delivered, e.message ?: e::class.simpleName.orEmpty())
                )
            }
            if (last?.isTerminal == true) {
                HermesResult.Ok(Unit)
            } else {
                HermesResult.Err(
                    HermesError.StreamTruncated(
                        delivered,
                        "stream closed after ${last?.let { it::class.simpleName } ?: "no"} event",
                    )
                )
            }
        }
        } finally { finished(call) }
    }

    /**
     * `GET /v1/runs/{run_id}`. Verified in Gate 0d.
     *
     * Note that a run stopped through [stopRun] settles here as `"completed"`, not
     * `"cancelled"`, so this cannot be used to discover that a turn was interrupted.
     */
    fun runStatus(runId: String): HermesResult<RunStatus> =
        get("/v1/runs/$runId") { json.decodeFromString(it) }

    /** Repeating this exact body/key recovers admission; never substitute a fresh key. */
    fun submitRun(sessionId: String, input: String, requestId: String, interactiveApprovals: Boolean = false,
        bellpull: BellpullRunOptions? = null): HermesResult<RunAdmission> {
        require(input.isNotBlank() && requestId.isNotBlank())
        val body = buildJsonObject {
            put("input", input); put("session_id", sessionId)
            if (interactiveApprovals) put("interactive_approvals", true)
            bellpull?.let { put("bellpull", json.encodeToJsonElement(it)) }
        }.toString()
        return execute(authed("/v1/runs").header("Idempotency-Key", requestId)
            .post(body.toRequestBody(JSON_MEDIA)).build(), sessionId) { json.decodeFromString(it) }
    }

    /** Current Hermes replays sequenced events; older queues require status/history recovery. */
    fun runEvents(runId: String, onStart: (Streaming) -> Unit = {}, lastEventId: Int? = null,
        onEvent: (StreamEvent) -> Unit): HermesResult<Unit> =
        events("/v1/runs/$runId/events", onStart, lastEventId, onEvent)

    /** Authenticated gateway proxy to the existing loopback ntfy broker. Wake hints only. */
    fun notificationEvents(onStart: (Streaming) -> Unit, onWake: (String, String) -> Unit): HermesResult<Unit> =
        events("/api/bellpull/events", onStart) { event ->
            if (event is StreamEvent.Unknown && event.event == "bellpull.wake") {
                val runId = event.raw["run_id"]?.jsonPrimitive?.content.orEmpty()
                val sessionId = event.raw["session_id"]?.jsonPrimitive?.content.orEmpty()
                if (runId.matches(Regex("run_[a-zA-Z0-9_-]{1,100}")) && sessionId.matches(Regex("[a-zA-Z0-9_-]{1,200}"))) onWake(runId, sessionId)
            }
        }

    private fun events(path: String, onStart: (Streaming) -> Unit, lastEventId: Int? = null,
        onEvent: (StreamEvent) -> Unit): HermesResult<Unit> {
        val call = trackedCall(streamingHttpClient, authed(path)
            .header("Accept", "text/event-stream").apply {
                lastEventId?.let { header("Last-Event-ID", it.toString()) }
            }.get().build())
        onStart(Streaming(call))
        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) HermesResult.Err(mapHttpError(response, null))
                else { SseReader(response.body.source()).read(onEvent); HermesResult.Ok(Unit) }
            }
        } catch (e: IOException) { HermesResult.Err(mapIoError(e)) }
        finally { finished(call) }
    }

    fun respondToApproval(runId: String, requestId: String, choice: String): HermesResult<Unit> {
        require(requestId.isNotBlank() && choice in setOf("once", "deny"))
        return post("/v1/runs/$runId/approval", buildJsonObject {
            put("request_id", requestId); put("choice", choice); put("resolve_all", false)
        }.toString()) { Unit }
    }

    /**
     * `POST /v1/runs/{run_id}/stop`. Verified in Gate 0d: it really does interrupt the turn,
     * within about 40 ms.
     *
     * Two things the caller has to know. A 409 comes back as
     * [HermesError.RunNotStoppableYet] rather than a generic error, because the run is
     * running — its agent just is not registered yet, which happens shortly after
     * `run.started`. And the run afterwards reports itself `completed` with
     * `interrupted: false`, so the caller must remember that it asked for the stop; nothing
     * in any later response will say so.
     */
    fun stopRun(runId: String): HermesResult<RunStatus> {
        val result = post("/v1/runs/$runId/stop", "{}") { json.decodeFromString<RunStatus>(it) }
        val error = (result as? HermesResult.Err)?.error
        return if (error is HermesError.ServerError && error.code == "run_not_active") {
            HermesResult.Err(HermesError.RunNotStoppableYet(runId))
        } else {
            result
        }
    }

    /** Handle for cancelling an in-flight stream. */
    class Streaming internal constructor(private val call: Call) {
        fun cancel() = call.cancel()
        val isCancelled: Boolean get() = call.isCanceled()
    }

    // ---- plumbing -------------------------------------------------------------

    private fun authed(path: String): Request.Builder {
        val builder = Request.Builder()
            .url(base + path)
            .header("Authorization", "Bearer ${keyProvider()}")
            .header("Accept", "application/json")
        memoryScopeKey?.let { builder.header("X-Hermes-Session-Key", it) }
        return builder
    }

    private fun <T> get(path: String, sessionId: String? = null, decode: (String) -> T): HermesResult<T> =
        execute(authed(path).get().build(), sessionId, decode)

    private fun <T> post(path: String, body: String, sessionId: String? = null, decode: (String) -> T): HermesResult<T> =
        execute(authed(path).post(body.toRequestBody(JSON_MEDIA)).build(), sessionId, decode)

    /**
     * Run a request, retrying a 429 up to [MAX_RATE_LIMIT_RETRIES] times. The delay comes from
     * the server's `Retry-After` header when present — no invented backoff curve — and the
     * final 429 is returned as a typed error rather than retried forever.
     */
    private fun <T> execute(request: Request, sessionId: String?, decode: (String) -> T): HermesResult<T> {
        var attempt = 0
        while (true) {
            val call = trackedCall(httpClient, request)
            val result = try {
                call.execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body.string()
                        runCatching { HermesResult.Ok(decode(body)) }.getOrElse { e ->
                            HermesResult.Err(HermesError.MalformedResponse(e.message ?: e::class.simpleName.orEmpty()))
                        }
                    } else {
                        HermesResult.Err(mapHttpError(response, sessionId))
                    }
                }
            } catch (e: IOException) {
                HermesResult.Err(mapIoError(e))
            } finally { finished(call) }

            val error = (result as? HermesResult.Err)?.error
            if (error is HermesError.RateLimited && attempt < MAX_RATE_LIMIT_RETRIES) {
                attempt++
                sleeper((error.retryAfterSeconds ?: 1L).coerceIn(1, 30) * 1000L)
                continue
            }
            return result
        }
    }

    /**
     * Map a non-2xx onto a typed error. Reads the body once and tolerates it not being JSON:
     * an unknown path really does return the plain text `404: Not Found`.
     */
    private fun mapHttpError(response: Response, sessionId: String?): HermesError {
        // Before anything else: a 3xx is not an error envelope and must not be followed.
        if (response.code in 300..399) {
            return HermesError.UnexpectedRedirect(response.code, response.header("Location"))
        }
        val raw = runCatching { response.body.string() }.getOrDefault("")
        val envelope = runCatching {
            json.parseToJsonElement(raw).jsonObject["error"]?.jsonObject
        }.getOrNull()

        if (envelope == null) {
            return if (response.code == 401) {
                HermesError.AuthFailed(raw.ifBlank { "no message" })
            } else {
                HermesError.UnparseableError(response.code, raw.take(200))
            }
        }

        fun field(name: String) = envelope[name]
            ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?.takeIf { it != "null" }

        val message = field("message") ?: "no message"
        val code = field("code")
        val retryAfter = response.header("Retry-After")?.toLongOrNull()
            ?: envelope["retry_after"]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }

        return when {
            response.code == 401 -> HermesError.AuthFailed(message)
            response.code == 429 -> HermesError.RateLimited(message, retryAfter)
            code == "session_not_found" -> HermesError.SessionNotFound(sessionId ?: message)
            response.code == 400 -> HermesError.BadRequest(message, code)
            else -> HermesError.ServerError(response.code, message, code)
        }
    }

    private fun mapIoError(e: IOException): HermesError {
        val kind = when (e) {
            is UnknownHostException -> HermesError.Unreachable.Kind.DNS
            is ConnectException -> HermesError.Unreachable.Kind.CONNECT_REFUSED
            is SocketTimeoutException -> HermesError.Unreachable.Kind.TIMEOUT
            is SSLException -> HermesError.Unreachable.Kind.TLS
            is InterruptedIOException -> HermesError.Unreachable.Kind.INTERRUPTED
            else -> HermesError.Unreachable.Kind.OTHER
        }
        // e.message can echo the request URL but never the Authorization header.
        return HermesError.Unreachable(kind, e.message ?: e::class.simpleName.orEmpty())
    }

    /** Feature flags from `/v1/capabilities`, plus the untouched payload. */
    data class Capabilities(val raw: JsonObject, val features: Map<String, Boolean>) {
        val sessionChatStreaming: Boolean get() = features["session_chat_streaming"] == true
        val sessionForkAtMessage: Boolean get() = features["session_fork_at_message"] == true
        val sessionForkBeforeMessage: Boolean get() = features["session_fork_before_message"] == true
        val interactiveApprovals: Boolean get() = features["run_interactive_approvals"] == true
        val notificationEvents: Boolean get() = features["bellpull_notification_events"] == true
        val durableRunRetention: Long? get() = runCatching {
            val flags = raw["features"]?.jsonObject?.get("runs_idempotency")?.jsonObject ?: return@runCatching null
            if (flags["supported"]?.jsonPrimitive?.booleanOrNull == true &&
                flags["durable"]?.jsonPrimitive?.booleanOrNull == true)
                flags["retention_seconds"]?.jsonPrimitive?.longOrNull?.takeIf { it > 60 }
            else null
        }.getOrNull()
    }

    companion object {
        private const val MAX_RATE_LIMIT_RETRIES = 3
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /**
         * Ordinary requests get a bounded read timeout. Only the SSE stream disables it, via
         * [streamingHttpClient] — an earlier version set it to zero for everything, which
         * meant a plain GET on a poisoned pooled connection could wait forever with nothing
         * to break the deadlock. A list or history fetch that never returns is worse than one
         * that fails.
         */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // Redirects are refused, not followed. OkHttp follows them by default and drops
            // the Authorization header only when the host changes, so a same-host redirect
            // would carry a root-equivalent key to wherever it pointed. The API server has no
            // legitimate reason to redirect, so this surfaces as UnexpectedRedirect instead.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        /**
         * The streaming variant: no read timeout, because a turn can legitimately sit quiet
         * for a long time while the model thinks or a tool runs, and a read timeout would kill
         * it. Derived from [defaultHttpClient] so it shares the connection pool and inherits
         * every other setting, redirect refusal included.
         */
        internal fun streamingClient(base: OkHttpClient): OkHttpClient =
            base.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    }
}
