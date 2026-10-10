package org.praxisog.hermes.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    init {
        require(memoryScopeKey == null || memoryScopeKey.length <= 256) {
            "X-Hermes-Session-Key is capped at 256 characters"
        }
    }

    // ---- endpoints ------------------------------------------------------------

    /**
     * `GET /v1/capabilities`. Returns the feature flags. This is the cheapest authenticated
     * call, so it doubles as the connection check.
     */
    fun capabilities(): HermesResult<Capabilities> = get("/v1/capabilities") { body ->
        val obj = json.parseToJsonElement(body).jsonObject
        val features = obj["features"]?.jsonObject ?: JsonObject(emptyMap())
        Capabilities(
            raw = obj,
            features = features.mapValues { (_, v) ->
                runCatching { v.jsonPrimitive.booleanOrNull ?: false }.getOrDefault(false)
            },
        )
    }

    /**
     * `GET /api/sessions`.
     *
     * [includeChildren] defaults to false and [source] is worth setting, because without them
     * the list fills with cron, tool, API and subagent sessions rather than human chats.
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

    /** `POST /api/sessions`. Unwraps the `session` envelope. */
    fun createSession(title: String? = null): HermesResult<Session> {
        val payload = if (title == null) "{}" else json.encodeToString(mapOf("title" to title))
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
    fun messages(sessionId: String): HermesResult<MessageHistory> =
        get("/api/sessions/$sessionId/messages", sessionId) { json.decodeFromString(it) }

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

        val call = streamingHttpClient.newCall(request)
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
    fun submitRun(sessionId: String, input: String, requestId: String, interactiveApprovals: Boolean = false): HermesResult<RunAdmission> {
        require(input.isNotBlank() && requestId.isNotBlank())
        val body = buildJsonObject {
            put("input", input); put("session_id", sessionId)
            if (interactiveApprovals) put("interactive_approvals", true)
        }.toString()
        return execute(authed("/v1/runs").header("Idempotency-Key", requestId)
            .post(body.toRequestBody(JSON_MEDIA)).build(), sessionId) { json.decodeFromString(it) }
    }

    /** Status/history remain authoritative: this queue cannot replay consumed events. */
    fun runEvents(runId: String, onStart: (Streaming) -> Unit = {}, onEvent: (StreamEvent) -> Unit): HermesResult<Unit> =
        events("/v1/runs/$runId/events", onStart, onEvent)

    /** Authenticated gateway proxy to the existing loopback ntfy broker. Wake hints only. */
    fun notificationEvents(onStart: (Streaming) -> Unit, onWake: (String, String) -> Unit): HermesResult<Unit> =
        events("/api/bellpull/events", onStart) { event ->
            if (event is StreamEvent.Unknown && event.event == "bellpull.wake") {
                val runId = event.raw["run_id"]?.jsonPrimitive?.content.orEmpty()
                val sessionId = event.raw["session_id"]?.jsonPrimitive?.content.orEmpty()
                if (runId.matches(Regex("run_[a-zA-Z0-9_-]{1,100}")) && sessionId.matches(Regex("[a-zA-Z0-9_-]{1,200}"))) onWake(runId, sessionId)
            }
        }

    private fun events(path: String, onStart: (Streaming) -> Unit, onEvent: (StreamEvent) -> Unit): HermesResult<Unit> {
        val call = streamingHttpClient.newCall(authed(path)
            .header("Accept", "text/event-stream").get().build())
        onStart(Streaming(call))
        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) HermesResult.Err(mapHttpError(response, null))
                else { SseReader(response.body.source()).read(onEvent); HermesResult.Ok(Unit) }
            }
        } catch (e: IOException) { HermesResult.Err(mapIoError(e)) }
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
            val result = try {
                httpClient.newCall(request).execute().use { response ->
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
            }

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
