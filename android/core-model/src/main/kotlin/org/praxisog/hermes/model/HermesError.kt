package org.praxisog.hermes.model

/**
 * Every failure the client can report, as one closed set. A caller must be able to tell an
 * auth problem from an unreachable server from a deliberate server refusal without matching
 * on strings — the app's non-negotiable is that nothing fails silently or generically.
 *
 * The JSON-carrying cases map from the real error envelope
 * `{"error": {"message", "type", "param", "code"}}` recorded in
 * `mock-hermes/fixtures/error-responses.json`.
 */
sealed interface HermesError {
    /** Operator-facing summary. Not a user-facing string; the UI layer decides wording. */
    val summary: String

    /** 401. Missing header, wrong key, and a non-Bearer scheme all arrive as this. */
    data class AuthFailed(val message: String) : HermesError {
        override val summary get() = "Authentication failed: $message"
    }

    /** 429. [retryAfterSeconds] comes from the `Retry-After` header when the server sent one. */
    data class RateLimited(val message: String, val retryAfterSeconds: Long?) : HermesError {
        override val summary get() = "Rate limited: $message (retry after ${retryAfterSeconds ?: "unspecified"}s)"
    }

    /** 404 with `code == "session_not_found"`. */
    data class SessionNotFound(val sessionId: String) : HermesError {
        override val summary get() = "Session not found: $sessionId"
    }

    /** 400. [code] is nullable because the invalid-JSON response sends `code: null`. */
    data class BadRequest(val message: String, val code: String?) : HermesError {
        override val summary get() = "Rejected by Hermes: $message" + (code?.let { " ($it)" } ?: "")
    }

    /**
     * A non-2xx whose body was not the JSON error envelope. Real and load-bearing: an unknown
     * path returns the plain text `404: Not Found`, so assuming JSON on every error is a
     * parse crash waiting to happen.
     */
    data class UnparseableError(val status: Int, val bodySnippet: String) : HermesError {
        override val summary get() = "HTTP $status with a non-JSON body: $bodySnippet"
    }

    /** A 2xx whose body did not decode — a genuine contract break, not a transport problem. */
    data class MalformedResponse(val detail: String) : HermesError {
        override val summary get() = "Hermes sent a response this client could not parse: $detail"
    }

    /** Any other non-2xx carrying a well-formed error envelope. */
    data class ServerError(val status: Int, val message: String, val code: String?) : HermesError {
        override val summary get() = "Hermes returned HTTP $status: $message"
    }

    /** Transport-level failure, split so the UI can distinguish "wrong address" from "no route". */
    data class Unreachable(val kind: Kind, val detail: String) : HermesError {
        enum class Kind { DNS, CONNECT_REFUSED, TIMEOUT, TLS, INTERRUPTED, OTHER }
        override val summary get() = "Could not reach Hermes ($kind): $detail"
    }

    /**
     * The SSE stream ended without a terminal event. The turn may have continued server-side.
     * Distinct from a clean finish on purpose: a truncated turn must never render as finished.
     */
    data class StreamTruncated(val eventsReceived: Int, val detail: String) : HermesError {
        override val summary get() = "Stream ended after $eventsReceived events with no terminal event: $detail"
    }
}

/** Result of a client call. Success or exactly one [HermesError]. */
sealed interface HermesResult<out T> {
    data class Ok<out T>(val value: T) : HermesResult<T>
    data class Err(val error: HermesError) : HermesResult<Nothing>

    fun valueOrNull(): T? = (this as? Ok)?.value
    fun errorOrNull(): HermesError? = (this as? Err)?.error
}
