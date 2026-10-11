package org.praxisog.hermes.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ConversationProject(
    val id: String,
    val name: String,
    val description: String = "",
    val color: String? = null,
    val icon: String? = null,
    val archived: Boolean = false,
    @SerialName("created_at") val createdAt: Double = 0.0,
    @SerialName("updated_at") val updatedAt: Double = 0.0,
)

@Serializable
data class ProjectMembership(
    @SerialName("session_id") val sessionId: String,
    @SerialName("session_ids") val sessionIds: List<String> = emptyList(),
    @SerialName("project_id") val projectId: String? = null,
    val pinned: Boolean = false,
) {
    fun contains(id: String) = id == sessionId || id in sessionIds
}

@Serializable
data class ProjectSnapshot(
    val version: Int = 1,
    val revision: Long = 0,
    val projects: List<ConversationProject> = emptyList(),
    val memberships: List<ProjectMembership> = emptyList(),
) {
    fun membership(sessionId: String) = memberships.singleOrNull { it.contains(sessionId) }
    fun projectFor(sessionId: String) = membership(sessionId)?.projectId
}

@Serializable
data class ProjectMutation(
    val operation: String,
    @SerialName("operation_id") val operationId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    val name: String? = null,
    val description: String? = null,
    val color: String? = null,
    val icon: String? = null,
    val archived: Boolean? = null,
    val pinned: Boolean? = null,
)

@Serializable
data class PendingProjectChat(
    val id: String,
    @SerialName("project_id") val projectId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    val title: String? = null,
    val selection: ModelSelection? = null,
)
