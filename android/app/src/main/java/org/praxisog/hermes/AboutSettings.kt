package org.praxisog.hermes

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val MINIMUM_BELLPULL_PLUGIN = "0.3.0"
internal data class ServerPluginVersion(val name: String, val url: String, val version: String?, val checkedAt: Double?)
internal data class InstalledAppVersion(val name: String, val code: Long)

@Suppress("DEPRECATION")
internal fun installedAppVersion(context: Context): InstalledAppVersion = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).let { InstalledAppVersion(it.versionName ?: "Unknown", it.longVersionCode) }
}.getOrDefault(InstalledAppVersion("Unknown", 0))

internal fun pluginVersionStatus(version: String?, checked: Boolean): String {
    if (!checked) return "Not checked yet. Connect this server to check its plugin."
    if (version == null) return "Plugin version not reported. Reconnect after installing the Bellpull plugin."
    val parsed = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([A-Za-z0-9.-]+))?").matchEntire(version)
        ?: return "Unknown version format; compatibility has not been established."
    val actual = parsed.groupValues.drop(1).take(3).map { it.toLongOrNull() ?: return "Unknown version format." }
    val minimum = MINIMUM_BELLPULL_PLUGIN.split('.').map(String::toLong)
    val comparison = actual.zip(minimum).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a.compareTo(b) } ?: 0
    return when {
        comparison < 0 || comparison == 0 && parsed.groupValues[4].isNotEmpty() -> "Update needed. This app requires plugin $MINIMUM_BELLPULL_PLUGIN or newer."
        else -> "Meets this app's minimum plugin version ($MINIMUM_BELLPULL_PLUGIN)."
    }
}

@Composable
internal fun AboutSettings(versions: () -> List<ServerPluginVersion>) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("About") }
    if (open) {
        val context = LocalContext.current
        val app = remember { installedAppVersion(context) }
        var servers by remember { mutableStateOf<List<ServerPluginVersion>?>(null) }
        LaunchedEffect(Unit) { servers = withContext(Dispatchers.IO) { versions() } }
        AboutDialog(app, servers) { open = false }
    }
}

@Composable
internal fun AboutDialog(app: InstalledAppVersion, servers: List<ServerPluginVersion>?, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text("About Bellpull") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Installed app: ${app.name} (build ${app.code})", style = MaterialTheme.typography.titleMedium)
            Text("Hermes plugins", style = MaterialTheme.typography.titleSmall)
            Text("Versions are last reported by each saved server. Update checks compare with this app's minimum, not the latest GitHub release.",
                style = MaterialTheme.typography.bodySmall)
            if (servers == null) CircularProgressIndicator()
            else if (servers.isEmpty()) Text("No saved servers.")
            servers?.forEach { server ->
                HorizontalDivider()
                Text(server.name, style = MaterialTheme.typography.titleSmall)
                Text(server.url, style = MaterialTheme.typography.bodySmall)
                Text("Bellpull plugin: ${server.version ?: "Unknown"}")
                Text(pluginVersionStatus(server.version, server.checkedAt != null), style = MaterialTheme.typography.bodySmall)
                server.checkedAt?.takeIf { it > 0 }?.let {
                    val date = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm 'UTC'", java.util.Locale.US)
                        .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.ofEpochMilli((it * 1000).toLong()))
                    Text("Last checked: $date", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Close") } })
}
