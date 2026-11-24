package org.praxisog.hermes.core

import kotlinx.serialization.json.*
import org.praxisog.hermes.model.*
import org.praxisog.hermes.model.IntegrationCondition.*
import java.time.Instant

internal fun integrationCheck(endpoint: IntegrationEndpoint, started: Long, result: HermesResult<JsonObject>): IntegrationCheck {
    fun check(condition: IntegrationCondition, http: Int? = null) = IntegrationCheck(endpoint, started, condition, http)
    if (result is HermesResult.Err) {
        val error = result.error
        val http = when (error) {
            is HermesError.AuthFailed -> 401
            is HermesError.RateLimited -> 429
            is HermesError.ServerError -> error.status
            is HermesError.UnparseableError -> error.status
            is HermesError.UnexpectedRedirect -> error.status
            is HermesError.BadRequest -> 400
            else -> null
        }
        val condition = when {
            error is HermesError.ServerError && error.code == "bellpull_restart_required" && endpoint == IntegrationEndpoint.PLUGIN -> RESTART_REQUIRED
            error is HermesError.ServerError && error.code == "bellpull_model_provider_unavailable" && endpoint == IntegrationEndpoint.MODEL_ROUTE -> PROVIDER_UNAVAILABLE
            http == 401 || http == 403 -> DENIED
            http == 404 || http == 501 -> MISSING
            http == 429 -> RATE_LIMITED
            error is HermesError.Unreachable -> UNREACHABLE
            error is HermesError.MalformedResponse -> INCOMPATIBLE
            else -> UNAVAILABLE
        }
        return check(condition, http)
    }
    val doc = (result as HermesResult.Ok).value
    fun JsonObject.obj(key: String) = this[key] as? JsonObject
    fun JsonObject.field(key: String) = this[key] as? JsonPrimitive
    return when (endpoint) {
        IntegrationEndpoint.GATEWAY -> check(if (doc.obj("features") != null) READY else INCOMPATIBLE)
        IntegrationEndpoint.PLUGIN -> {
            val flags = doc.obj("features")
            if (doc.field("version")?.intOrNull != 1 || flags == null) check(INCOMPATIBLE)
            else check(READY).copy(relayAvailable = flags.field("bellpull_notification_events")?.booleanOrNull)
        }
        IntegrationEndpoint.MODEL_ROUTE -> {
            val models = doc["models"] as? JsonArray
            val route = doc.obj("default")
            if (doc.field("version")?.intOrNull != 1 || models == null || route == null) return check(INCOMPATIBLE)
            // Persist identifiers from the public catalog only, never URLs, options or provider errors.
            val name = Regex("[A-Za-z0-9][A-Za-z0-9_./:@+-]{0,199}")
            fun id(key: String) = route.field(key)?.takeIf { it.isString }?.content?.takeIf { name.matches(it) && "://" !in it && '@' !in it }
            val provider = id("provider")
            val model = id("model")
            val entry = models.filterIsInstance<JsonObject>().firstOrNull {
                provider != null && model != null && it.field("provider")?.contentOrNull == provider && it.field("model")?.contentOrNull == model
            }
            val condition = when (entry?.field("configured")?.booleanOrNull) {
                true -> READY
                false -> DEGRADED
                else -> UNKNOWN
            }
            check(condition).copy(provider = provider, model = model, vision = entry?.field("vision")?.booleanOrNull)
        }
        IntegrationEndpoint.READINESS -> {
            val readiness = doc.obj("readiness")
            val condition = when (readiness?.field("status")?.contentOrNull) {
                "ok" -> READY
                "degraded" -> DEGRADED
                else -> UNKNOWN
            }
            val checks = readiness?.obj("checks")
            val queue = checks?.obj("background_queues")
            fun count(key: String) = queue?.field(key)?.intOrNull?.takeIf { it >= 0 }
            val configured = when (checks?.obj("model")?.field("status")?.contentOrNull) {
                "ok" -> true
                "degraded" -> false
                else -> null
            }
            val updated = doc.field("updated_at")?.contentOrNull?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            check(condition).copy(modelConfigured = configured, activeRuns = count("active_api_runs"),
                processCompletions = count("process_completions"), activeDelegations = count("active_delegations"),
                runtimeUpdatedAt = updated)
        }
    }
}
