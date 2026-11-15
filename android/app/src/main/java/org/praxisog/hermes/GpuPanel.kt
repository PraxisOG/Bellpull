package org.praxisog.hermes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import java.text.DateFormat
import java.util.Date

/** Composed in Settings. Leaving Settings or pausing the Activity cancels polling. */
@Composable
internal fun LiveGpuPanel(client: HermesClient, foreground: Boolean) {
    var readings by remember(client) { mutableStateOf<GpuTelemetry?>(null) }
    var error by remember(client) { mutableStateOf<String?>(null) }
    LaunchedEffect(client, foreground) {
        if (!foreground) return@LaunchedEffect
        while (true) {
            when (val result = withContext(Dispatchers.IO) { client.gpuTemperatures() }) {
                is HermesResult.Ok -> { readings = result.value; error = null }
                is HermesResult.Err -> {
                    val missing = (result.error as? HermesError.UnparseableError)?.status == 404 ||
                        (result.error as? HermesError.ServerError)?.status == 404
                    error = if (missing)
                        "Install the Bellpull GPU plugin on this Hermes server to see temperatures."
                    else result.error.toUserMessage()
                }
            }
            delay((readings?.refreshSeconds ?: 15).coerceIn(15, 120) * 1000L)
        }
    }
    GpuPanel(readings, error, System.currentTimeMillis() / 1000.0)
}

@Composable
internal fun GpuPanel(readings: GpuTelemetry?, error: String?, now: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("GPU temperatures", style = MaterialTheme.typography.titleMedium)
        if (readings == null && error == null) Text("Reading server sensors…", style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        readings?.cards?.forEach { card -> key(card.id) {
            val stale = error != null || card.stale || now - card.sampledAt > 45
            var expanded by rememberSaveable(card.id) { mutableStateOf(false) }
            val primary = primaryGpuSensor(card)
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button) { expanded = !expanded }
                    .semantics { contentDescription = "${card.name} sensor details"; stateDescription = if (expanded) "Expanded" else "Collapsed" }
                    .padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(card.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (stale) Text("Stale", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        Text(if (expanded) "  ▴" else "  ▾")
                    }
                    if (primary != null) TemperatureBar(primary, stale, details = false)
                    else Text("Temperature unavailable", style = MaterialTheme.typography.labelSmall)
                }
                if (expanded) Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp).testTag("gpu-details-${card.id}"),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(card.busId.ifBlank { card.id }, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    card.sensors.forEach { sensor -> TemperatureBar(sensor, stale) }
                    Text((if (stale) "Stale · " else "Sample · ") + DateFormat.getTimeInstance(DateFormat.MEDIUM)
                        .format(Date((card.sampledAt * 1000).toLong())), style = MaterialTheme.typography.labelSmall,
                        color = if (stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    readings.scaleNote.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        } }
        readings?.errors?.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (readings != null && readings.cards.isEmpty() && readings.errors.isEmpty() && error == null)
            Text("No GPU sensors available.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TemperatureBar(sensor: GpuSensor, stale: Boolean, details: Boolean = true) {
    val value = sensor.celsius?.takeIf { it.isFinite() }
    val markerColor = MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(sensor.name.replaceFirstChar { it.uppercase() }, Modifier.width(60.dp), style = MaterialTheme.typography.labelSmall)
        Canvas(Modifier.weight(1f).height(12.dp)) {
            drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF3C9564), Color(0xFFE5BF4D), Color(0xFFC74747))),
                cornerRadius = CornerRadius(4.dp.toPx()), alpha = if (stale || value == null) .35f else .8f)
            if (value != null && sensor.scaleMax > sensor.scaleMin) {
                val x = ((value - sensor.scaleMin) / (sensor.scaleMax - sensor.scaleMin)).coerceIn(0.0, 1.0).toFloat() * size.width
                drawLine(markerColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3.dp.toPx())
            }
        }
        Text(value?.let { "${it.toInt()}°C" } ?: "N/A", Modifier.width(40.dp), style = MaterialTheme.typography.labelSmall)
    }
    if (details) Text("${sensor.scaleMin.toInt()}–${sensor.scaleMax.toInt()}°C" +
        (sensor.slowdownCelsius?.let { " · driver slowdown ${it.toInt()}°C" } ?: ""),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Prefer the core/edge reading; label a fallback sensor honestly when that is unavailable. */
internal fun primaryGpuSensor(card: GpuCard): GpuSensor? {
    val valid = card.sensors.filter { it.celsius?.isFinite() == true }
    return valid.firstOrNull { it.name.lowercase() in setOf("edge", "gpu", "core") }
        ?: valid.maxByOrNull { it.celsius!! } ?: card.sensors.firstOrNull()
}
