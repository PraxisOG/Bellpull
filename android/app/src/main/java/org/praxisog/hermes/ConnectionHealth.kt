package org.praxisog.hermes

import org.praxisog.hermes.model.HermesError
import org.praxisog.hermes.model.HermesResult

/** Foreground reachability, independent of cached lists and the model's running state. */
internal data class ConnectionHealth(
    val phase: Phase = Phase.CHECKING,
    val failedSince: Long? = null,
    val failures: Int = 0,
    val outageMs: Long = 0,
    val latencyMs: Long? = null,
    val error: HermesError? = null,
) {
    enum class Phase { CHECKING, ONLINE, RECONNECTING, OFFLINE, ATTENTION }
    val offline get() = phase == Phase.OFFLINE
    val unavailable get() = offline || phase == Phase.ATTENTION
    val localEligible get() = offline && outageMs >= 60_000
    val nextProbeMs: Long get() = when (phase) {
        Phase.CHECKING, Phase.RECONNECTING, Phase.OFFLINE -> 2_000
        Phase.ONLINE -> 12_000
        Phase.ATTENTION -> (error as? HermesError.RateLimited)?.retryAfterSeconds
            ?.coerceIn(1, 300)?.times(1000) ?: 30_000
    }

    fun record(result: HermesResult<Unit>, now: Long, durationMs: Long): ConnectionHealth {
        if (result is HermesResult.Ok) return ConnectionHealth(Phase.ONLINE, latencyMs = durationMs)
        val problem = (result as HermesResult.Err).error
        val temporary = when (problem) {
            is HermesError.Unreachable -> problem.kind != HermesError.Unreachable.Kind.TLS
            is HermesError.ServerError -> problem.status in 500..599
            is HermesError.UnparseableError -> problem.status in 500..599
            else -> false
        }
        // Authentication, throttling and incompatible responses are not an invitation
        // to change models. Keep their actual error visible and honour Retry-After.
        if (!temporary) return ConnectionHealth(Phase.ATTENTION, error = problem, latencyMs = durationMs)
        val since = failedSince ?: now
        val count = (failures + 1).coerceAtMost(1000)
        val age = (now - since).coerceAtLeast(0)
        return ConnectionHealth(if (count >= 3 && age >= 10_000) Phase.OFFLINE else Phase.RECONNECTING,
            since, count, age, durationMs, problem)
    }
}
