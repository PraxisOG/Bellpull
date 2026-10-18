package org.praxisog.hermes.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class OfflineMemory(val version: Int = 1, val revision: String = "", val text: String = "",
    @SerialName("synced_at") val syncedAt: Long = 0)
@Serializable data class OfflineHandoffMessage(val role: String, val content: String)
@Serializable data class OfflineHandoff(
    @SerialName("operation_id") val operationId: String,
    @SerialName("source_id") val sourceId: String? = null,
    val title: String, val messages: List<OfflineHandoffMessage>, val brief: String = "")
@Serializable data class OfflineReceipt(@SerialName("session_id") val sessionId: String,
    @SerialName("operation_id") val operationId: String, val created: Boolean)
