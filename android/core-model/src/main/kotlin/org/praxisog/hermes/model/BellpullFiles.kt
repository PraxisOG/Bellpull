package org.praxisog.hermes.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModelSelection(val model: String, val provider: String,
    @SerialName("model_options") val options: ModelOptions = ModelOptions())
@Serializable data class ModelOptions(val reasoning: ReasoningChoice? = null)
@Serializable data class ReasoningChoice(val enabled: Boolean = true, val effort: String? = null)
@Serializable data class ReasoningCapabilities(val efforts: List<String> = emptyList(),
    @SerialName("can_disable") val canDisable: Boolean = false,
    @SerialName("can_enable") val canEnable: Boolean = false, val note: String = "")
@Serializable data class ModelRoute(val model: String, val provider: String, val configured: Boolean,
    val vision: Boolean = false, val reasoning: ReasoningCapabilities = ReasoningCapabilities())
@Serializable data class ModelCatalog(val version: Int, val models: List<ModelRoute>,
    val default: ModelSelection, val note: String = "")
@Serializable data class SessionModel(@SerialName("session_id") val sessionId: String, val selection: ModelSelection)

/** Serialized before POST; these exact choices and file IDs belong to this request. */
@Serializable data class BellpullRunOptions(@kotlinx.serialization.Required val version: Int = 1, val selection: ModelSelection,
    @SerialName("artifact_ids") val artifactIds: List<String> = emptyList())

@Serializable data class Artifact(
    val id: String,
    @SerialName("session_id") val sessionId: String,
    val name: String,
    val mime: String,
    val size: Long,
    val sha256: String,
    @SerialName("created_at") val createdAt: Double,
    val source: String,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("message_id") val messageId: Int? = null,
    val available: Boolean = true,
    val deleted: Boolean = false,
    @SerialName("session_ids") val sessionIds: List<String> = emptyList(),
    @SerialName("message_ids") val messageIds: Map<String, Int> = emptyMap(),
) {
    fun forSession(id: String): Artifact? = when {
        id == sessionId -> this
        id in sessionIds -> copy(sessionId = id, messageId = messageIds[id])
        else -> null
    }
}
@Serializable data class ArtifactWarning(val name: String, val message: String,
    @SerialName("request_id") val requestId: String,
    @SerialName("created_at") val createdAt: Double)
@Serializable data class ArtifactStorage(@SerialName("used_bytes") val usedBytes: Long,
    @SerialName("quota_bytes") val quotaBytes: Long)
@Serializable data class ArtifactList(val data: List<Artifact>, val warnings: List<ArtifactWarning> = emptyList(),
    val storage: ArtifactStorage? = null)

/** Metadata is encrypted with the profile; content lives in profile-private files. */
@Serializable data class DraftFile(val id: String, val sessionId: String, val name: String, val mime: String,
    val size: Long, val uploaded: Boolean = false)
