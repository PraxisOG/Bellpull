package org.praxisog.hermes.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Status of one run. Shapes verified live 2026-09-16 (Gate 0d).
 *
 * `GET /v1/runs/{id}` returns every field plus `usage` once the run settles.
 * `POST /v1/runs/{id}/stop` returns only `run_id` and `status` — no `object`, no timestamps —
 * which is why almost everything here is optional.
 *
 * **[status] cannot be used to tell a stopped turn from a finished one.** A run stopped
 * through the API settles as `"completed"`, not `"cancelled"`. See `docs/hermes-api.md`
 * § Gate 0d; the client tracks user-requested stops itself.
 */
@Serializable
data class RunStatus(
    @SerialName("run_id") val runId: String,
    val status: String,
    val `object`: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    val model: String? = null,
    @SerialName("last_event") val lastEvent: String? = null,
    @SerialName("created_at") val createdAt: Double? = null,
    @SerialName("updated_at") val updatedAt: Double? = null,
    /** Server run aggregates; absent counters remain unavailable (see per-turn info contract). */
    val usage: JsonObject? = null,
    val output: String? = null,
    val error: String? = null,
    val approval: RunApproval? = null,
    @SerialName("bellpull_phase") val bellpullPhase: String? = null,
    val runtime: JsonElement? = null,
    @SerialName("bellpull_model_fallback") val modelFallback: JsonElement? = null,
) {
    /** Statuses observed live. A stop moves a run here before it settles. */
    val isStopping: Boolean get() = status == "stopping"
    val isTerminal: Boolean get() = status in TERMINAL


    companion object {
        private val TERMINAL = setOf("completed", "failed", "cancelled", "stopped", "interrupted")
    }
}

@Serializable
data class RunAdmission(
    @SerialName("run_id") val runId: String,
    val status: String,
    val replayed: Boolean = false,
)

@Serializable
data class RunApproval(
    @SerialName("request_id") val requestId: String = "",
    val command: String? = null,
    val description: String? = null,
    val choices: List<String> = emptyList(),
)

/** Exact submitted payload identity, saved before the first network request. */
@Serializable
data class RunSubmission(
    val requestId: String,
    val sessionId: String,
    val text: String,
    val createdAt: Double,
    val draftRevision: Long,
    val previousIds: Set<Int>,
    val runId: String? = null,
    val status: String = "submitting",
    val stopRequested: Boolean = false,
    val historySynced: Boolean = false,
    val error: String? = null,
    val interactiveApprovals: Boolean = false,
    val bellpull: BellpullRunOptions? = null,
    /** Server evidence survives restart while the final transcript transaction catches up. */
    val completionOutput: String? = null,
    val serverCreatedAt: Double? = null,
    val historyChecks: Int = 0,
    val historyNeedsReview: Boolean = false,
    /** Explicit user release of tracking, distinct from a verified transcript match. */
    val trackingDismissed: Boolean = false,
    /** Optional server evidence for per-turn info; never session-total or estimated tokens. */
    val completionUsage: JsonObject? = null,
    val serverCompletedAt: Double? = null,
    val servedModel: RunModel? = null,
    val modelFallback: ProviderFallback? = null,
) {
    val terminal: Boolean get() = status in setOf("completed", "failed", "cancelled", "stopped", "interrupted", "rejected", "abandoned")
}

@Serializable data class RunModel(val provider: String, val model: String, val vision: Boolean? = null)
@Serializable data class ProviderFallback(val from: RunModel, val to: RunModel)

/** Persist only route identifiers and explicit capability evidence from the authenticated run. */
fun JsonElement?.runModel(): RunModel? {
    val fields = this as? JsonObject ?: return null
    fun identifier(key: String, limit: Int): String? {
        val text = (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return text.takeIf { it.isNotBlank() && it.length <= limit && it.none(Char::isISOControl) && "://" !in it }
    }
    return RunModel(identifier("provider", 80) ?: return null, identifier("model", 200) ?: return null,
        (fields["vision"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull)
}

fun JsonElement?.providerFallback(): ProviderFallback? {
    val fields = this as? JsonObject ?: return null
    return ProviderFallback(fields["from"].runModel() ?: return null, fields["to"].runModel() ?: return null)
}

fun RunSubmission.withRouteEvidence(status: RunStatus, acceptFallback: Boolean = true): RunSubmission = copy(
    servedModel = if (status.status == "completed") status.runtime.runModel() ?: servedModel else servedModel,
    modelFallback = if (acceptFallback) status.modelFallback.providerFallback() ?: modelFallback else modelFallback,
)
