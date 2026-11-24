package org.praxisog.hermes.model

import kotlinx.serialization.Serializable

enum class IntegrationEndpoint { GATEWAY, PLUGIN, READINESS, MODEL_ROUTE }

enum class IntegrationCondition {
    READY, DEGRADED, DENIED, MISSING, RESTART_REQUIRED, PROVIDER_UNAVAILABLE, UNAVAILABLE, UNREACHABLE, INCOMPATIBLE, RATE_LIMITED, UNKNOWN
}

/** Only typed evidence may enter the profile cache. HTTP bodies can contain private host details. */
@Serializable data class IntegrationCheck(
    val endpoint: IntegrationEndpoint, val checkedAt: Long, val condition: IntegrationCondition,
    val httpStatus: Int? = null, val lastReadyAt: Long? = null,
    val relayAvailable: Boolean? = null, val modelConfigured: Boolean? = null,
    val activeRuns: Int? = null, val processCompletions: Int? = null, val activeDelegations: Int? = null,
    val runtimeUpdatedAt: Long? = null,
    val provider: String? = null, val model: String? = null, val vision: Boolean? = null,
)

@Serializable data class IntegrationHealth(val checks: Map<IntegrationEndpoint, IntegrationCheck> = emptyMap()) {
    fun record(check: IntegrationCheck): IntegrationHealth {
        val previous = checks[check.endpoint]
        val latest = if (previous != null && previous.checkedAt > check.checkedAt) previous else check
        val lastReady = listOfNotNull(previous?.lastReadyAt,
            check.checkedAt.takeIf { check.condition == IntegrationCondition.READY }).maxOrNull()
        return copy(checks = checks + (check.endpoint to latest.copy(lastReadyAt = lastReady)))
    }
}
