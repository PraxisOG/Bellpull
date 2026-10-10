package org.praxisog.hermes.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GpuTelemetry(
    val version: Int,
    @SerialName("checked_at") val checkedAt: Double,
    @SerialName("refresh_seconds") val refreshSeconds: Int = 15,
    val cards: List<GpuCard> = emptyList(),
    val errors: List<String> = emptyList(),
    @SerialName("scale_note") val scaleNote: String = "",
)

@Serializable
data class GpuCard(
    val id: String,
    val vendor: String,
    val index: String,
    val name: String,
    @SerialName("bus_id") val busId: String = "",
    @SerialName("sampled_at") val sampledAt: Double,
    val stale: Boolean = false,
    val sensors: List<GpuSensor> = emptyList(),
)

@Serializable
data class GpuSensor(
    val name: String,
    val celsius: Double? = null,
    @SerialName("scale_min") val scaleMin: Double,
    @SerialName("scale_max") val scaleMax: Double,
    @SerialName("slowdown_celsius") val slowdownCelsius: Double? = null,
)
