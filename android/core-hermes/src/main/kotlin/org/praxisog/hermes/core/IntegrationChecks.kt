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
        IntegrationEndpoint.NOTIFICATION_DISPATCH -> {
            if (doc.field("version")?.intOrNull != 1) return check(INCOMPATIBLE)
            val observation = notificationDispatch(doc)
            val condition = when {
                observation.transport == RelayHealth.UNKNOWN -> UNKNOWN
                observation.transport != RelayHealth.HEALTHY || observation.backlog != ReceiptAvailability.AVAILABLE -> DEGRADED
                else -> READY
            }
            check(condition).copy(notificationDispatch = observation)
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

private fun notificationDispatch(doc: JsonObject): NotificationDispatch {
    val relay = doc["transport"] as? JsonObject
    val journal = doc["backlog"] as? JsonObject
    val transport = when ((relay?.get("state") as? JsonPrimitive)?.contentOrNull) {
        "healthy" -> RelayHealth.HEALTHY
        "unconfigured" -> RelayHealth.UNCONFIGURED
        "unavailable" -> RelayHealth.UNAVAILABLE
        "unreachable" -> RelayHealth.UNREACHABLE
        else -> RelayHealth.UNKNOWN
    }
    fun number(key: String) = (journal?.get(key) as? JsonPrimitive)?.takeUnless { it.isString }
    fun millis(key: String) = number(key)?.doubleOrNull?.takeIf { it > 0 && it < 253402300800.0 }?.let { (it * 1000).toLong() }
    val pending = number("pending")?.longOrNull?.takeIf { it >= 0 }
    val oldest = millis("oldest_pending_at")
    val accepted = millis("last_accepted_at")
    val complete = pending != null &&
        (if (pending == 0L) journal?.get("oldest_pending_at") == JsonNull else oldest != null) &&
        (journal?.get("last_accepted_at") == JsonNull || accepted != null)
    val backlog = when ((journal?.get("state") as? JsonPrimitive)?.contentOrNull) {
        "available" -> if (complete) ReceiptAvailability.AVAILABLE else ReceiptAvailability.UNKNOWN
        "unavailable" -> ReceiptAvailability.UNAVAILABLE
        "unsupported" -> ReceiptAvailability.UNSUPPORTED
        else -> ReceiptAvailability.UNKNOWN
    }
    val code = (relay?.get("http_status") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in 100..599 }
    if (backlog != ReceiptAvailability.AVAILABLE) return NotificationDispatch(transport, backlog, code)
    return NotificationDispatch(transport, backlog, code, pending, oldest, accepted)
}
