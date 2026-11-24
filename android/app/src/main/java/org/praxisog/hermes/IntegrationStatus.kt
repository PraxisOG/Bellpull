package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.praxisog.hermes.model.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val checkTime = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm:ss z")
private fun atTime(ms: Long) = checkTime.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))

internal fun integrationDescription(check: IntegrationCheck): String {
    val text = when (check.condition) {
        IntegrationCondition.READY -> if (check.endpoint == IntegrationEndpoint.MODEL_ROUTE) "Default route configured" else "Available"
        IntegrationCondition.DEGRADED -> "Degraded"
        IntegrationCondition.DENIED -> "Access denied. Check the saved API key and profile permissions."
        IntegrationCondition.MISSING -> "This endpoint is unavailable on the selected profile."
        IntegrationCondition.RESTART_REQUIRED -> "Bellpull requires a gateway restart. Wait for active work to finish."
        IntegrationCondition.PROVIDER_UNAVAILABLE -> "Hermes could not resolve the model provider. Check its configuration and credentials."
        IntegrationCondition.RATE_LIMITED -> "Rate limited. Check again later."
        IntegrationCondition.UNAVAILABLE -> "Service unavailable."
        IntegrationCondition.UNREACHABLE -> "Connection failed. Check the address and network."
        IntegrationCondition.INCOMPATIBLE -> "The response is incompatible with this version of Bellpull."
        IntegrationCondition.UNKNOWN -> "The server did not report a recognized state."
    }
    return check.httpStatus?.let { "$text (HTTP $it)" } ?: text
}

@Composable
internal fun IntegrationStatusButton(server: String, saved: () -> IntegrationHealth?, refresh: suspend () -> IntegrationHealth?) {
    var open by remember { mutableStateOf(false) }
    val health = saved() ?: IntegrationHealth()
    val plugin = health.checks[IntegrationEndpoint.PLUGIN]
    if (plugin != null && plugin.condition != IntegrationCondition.READY) {
        Text("Last Bellpull plugin check · " + integrationDescription(plugin), style = MaterialTheme.typography.bodySmall)
    }
    TextButton(onClick = { open = true }) { Text("Integration status") }
    if (open) {
        val scope = rememberCoroutineScope()
        var checking by remember { mutableStateOf(false) }
        var problem by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { open = false }, title = { Text("Integration status · $server") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IntegrationEndpoint.entries.forEach { endpoint ->
                    val check = health.checks[endpoint]
                    Text(when (endpoint) {
                        IntegrationEndpoint.GATEWAY -> "Hermes API"
                        IntegrationEndpoint.PLUGIN -> "Bellpull plugin"
                        IntegrationEndpoint.READINESS -> "Gateway readiness"
                        IntegrationEndpoint.MODEL_ROUTE -> "Model provider route"
                    }, style = MaterialTheme.typography.titleSmall)
                    if (check == null) Text("Not checked yet.")
                    else {
                        Text(integrationDescription(check))
                        Text("Checked ${atTime(check.checkedAt)}", style = MaterialTheme.typography.bodySmall)
                        if (check.condition != IntegrationCondition.READY) check.lastReadyAt?.let {
                            Text("Last available ${atTime(it)}", style = MaterialTheme.typography.bodySmall)
                        }
                        check.relayAvailable?.let { Text(if (it) "Plugin advertises notification events." else "Plugin does not advertise notification events.") }
                        check.modelConfigured?.let { Text(if (it) "Gateway reports a configured model." else "Gateway reports a model configuration problem.") }
                        check.activeRuns?.let { Text("Active API runs: $it") }
                        check.activeDelegations?.let { Text("Active delegations: $it") }
                        check.processCompletions?.let { Text("Queued process completions: $it") }
                        check.runtimeUpdatedAt?.let { Text("Gateway status updated ${atTime(it)}", style = MaterialTheme.typography.bodySmall) }
                        check.model?.let { Text("Default model · $it") }
                        check.provider?.let { Text("Provider · $it") }
                        check.vision?.let { Text(if (it) "Catalog advertises image input." else "Catalog does not advertise image input.") }
                    }
                }
                if (checking) LinearProgressIndicator()
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, dismissButton = { TextButton(enabled = !checking, onClick = {
            checking = true
            problem = null
            scope.launch {
                try {
                    val result = refresh()
                    if (result == null) problem = "This profile is no longer selected."
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { problem = "Could not complete the integration check." }
                finally { checking = false }
            }
        }) { Text("Check now") } }, confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } })
    }
}
