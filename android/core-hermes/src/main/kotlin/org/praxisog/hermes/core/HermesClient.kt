package org.praxisog.hermes.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.praxisog.hermes.model.HermesError
import org.praxisog.hermes.model.HermesResult
import org.praxisog.hermes.model.MessageHistory
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

        val call = httpClient.newCall(request)
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
    }

    companion object {
        private const val MAX_RATE_LIMIT_RETRIES = 3
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /**
         * No read timeout, because an SSE turn can legitimately sit quiet while the model
         * thinks; a read timeout here would kill long turns. Connect and call timeouts stay
         * bounded so an unreachable server still fails fast.
         */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
