package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun ServerProfilesPanel(
    profiles: List<ServerProfile>, activeId: String?, status: String, checking: Boolean,
    onAdd: (String, String, String) -> Boolean, onSelect: (String) -> Unit,
    onSignOut: () -> Unit, onRemove: (String) -> Unit, onRemoveAll: () -> Unit,
    onRename: (String, String) -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    // Deliberately not rememberSaveable: keys must never enter saved-instance-state bundles.
    var key by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<ServerProfile?>(null) }
    var removeAll by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ServerProfile?>(null) }
    var renamed by remember { mutableStateOf("") }
    Text("Server profiles", style = MaterialTheme.typography.titleMedium)
    Text(status, style = MaterialTheme.typography.bodySmall)
    if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
    profiles.forEach { profile ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(profile.name + if (activeId == profile.id) " · Selected" else "", style = MaterialTheme.typography.titleSmall)
                Text(profile.url, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onSelect(profile.id) }, enabled = !checking || activeId != profile.id) {
                        Text(if (activeId == profile.id) "Reconnect" else "Use profile")
                    }
                    TextButton(onClick = { renaming = profile; renamed = profile.name }) { Text("Rename") }
                    TextButton(onClick = { removing = profile }) { Text("Remove") }
                }
            }
        }
    }
    if (adding) {
        OutlinedTextField(name, { name = it }, label = { Text("Profile name") },
            placeholder = { Text("Desktop or compute server") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(key, { key = it }, label = { Text("API key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (onAdd(name, url, key)) { key = ""; url = ""; name = ""; adding = false }
            }, enabled = name.isNotBlank() && url.isNotBlank() && key.isNotBlank()) { Text("Save and connect") }
            TextButton(onClick = { key = ""; url = ""; name = ""; adding = false }) { Text("Cancel") }
        }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { adding = true }) { Text("Add server") }
            if (activeId != null) OutlinedButton(onClick = onSignOut) { Text("Sign out") }
        }
    }
    if (profiles.isNotEmpty()) {
        Text("Sign out keeps saved profiles. Switching servers leaves remote work running; it is recovered when you return.",
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { removeAll = true }) { Text("Remove all credentials") }
    } else Text("No connection needed to explore. Add your own Hermes server whenever you're ready.",
        style = MaterialTheme.typography.bodySmall)
    HorizontalDivider()

    if (removing != null || removeAll) AlertDialog(
        onDismissRequest = { removing = null; removeAll = false },
        title = { Text(if (removeAll) "Remove all saved servers?" else "Remove ${removing?.name}?") },
        text = { Text("Deletes saved URLs, API keys, cached chats, drafts and recovery data for " +
            (if (removeAll) "every profile" else "this profile") +
            " on this device. Remote chats and agents are unchanged. To revoke a key, rotate it on its server.") },
        confirmButton = { TextButton(onClick = {
            if (removeAll) onRemoveAll() else removing?.let { onRemove(it.id) }
            removing = null; removeAll = false; key = ""; url = ""; name = ""; adding = false
        }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removing = null; removeAll = false }) { Text("Cancel") } },
    )
    renaming?.let { profile -> AlertDialog(onDismissRequest = { renaming = null },
        title = { Text("Rename profile") },
        text = { OutlinedTextField(renamed, { renamed = it }, label = { Text("Profile name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onRename(profile.id, renamed); renaming = null },
            enabled = renamed.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }) }
}
