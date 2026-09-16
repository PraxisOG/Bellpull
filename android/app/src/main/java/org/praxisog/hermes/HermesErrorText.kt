package org.praxisog.hermes

import org.praxisog.hermes.model.HermesError

/**
 * Turns a [HermesError] into text a person should read.
 *
 * `HermesError.summary` is deliberately operator-facing — it names error types and HTTP
 * codes — so it is not shown to users. This is the one place that decides user wording, and
 * the `when` is exhaustive over the sealed interface, so adding an error case fails the build
 * here instead of silently falling through to something generic.
 *
 * The strings for cases that existed before the core-hermes migration are carried over from
 * HermesProbe unchanged, so the wording the app shipped with does not drift.
 */
internal fun HermesError.toUserMessage(): String = when (this) {
    is HermesError.AuthFailed ->
        "Authentication failed. Check the Hermes API key."

    is HermesError.RateLimited ->
        if (retryAfterSeconds != null) {
            "Hermes is rate limiting requests. Try again in ${retryAfterSeconds}s."
        } else {
            "Hermes is rate limiting requests. Try again later."
        }

    is HermesError.SessionNotFound ->
        "That session no longer exists on Hermes."

    is HermesError.BadRequest ->
        "Hermes rejected the request: $message"

    is HermesError.UnexpectedRedirect ->
        "Hermes redirected the request; check the server URL."

    is HermesError.UnparseableError ->
        "Hermes returned HTTP $status with an unexpected response. Check the server URL."

    is HermesError.MalformedResponse ->
        "Hermes sent a response this app could not read. It may be a different version than expected."

    is HermesError.ServerError ->
        "Hermes returned HTTP $status."

    // The turn may still be running server-side, so this must not read like a failed request.
    is HermesError.StreamTruncated ->
        "The connection dropped mid-reply. The turn may still be running on Hermes."

    is HermesError.Unreachable -> when (kind) {
        HermesError.Unreachable.Kind.DNS ->
            "Server name could not be resolved. Check the address or Tailscale."
        HermesError.Unreachable.Kind.CONNECT_REFUSED ->
            "Could not connect to Hermes. Check that its API is reachable."
        HermesError.Unreachable.Kind.TIMEOUT ->
            "Hermes did not respond in time. Check the network path."
        HermesError.Unreachable.Kind.TLS ->
            "TLS failed. Check the server certificate and address."
        HermesError.Unreachable.Kind.INTERRUPTED ->
            "The request was interrupted before it finished."
        HermesError.Unreachable.Kind.OTHER ->
            "The Hermes request failed. Check the server and network settings."
    }
}
