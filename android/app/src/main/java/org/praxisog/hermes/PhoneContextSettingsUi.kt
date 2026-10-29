package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun PhoneContextSettingsUi(sync: PhoneContextSync?, repository: PhoneNotificationRepository,
    profile: ServerProfile?, calendarSharing: Boolean) {
    if (sync == null || profile == null) { Text("Choose a server profile to configure phone context."); return }
    val snapshot by repository.state.collectAsState()
    val config = sync.settings.value
    var confirm by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("New-chat phone context", style = MaterialTheme.typography.titleSmall)
        Text("For ${profile.name}. The plugin supplies recent notifications and upcoming phone-calendar events when a new chat starts. Its read-only phone-context tool can also check this cache during a chat. Syncing does not create chats or run a model.")
        PreferenceSwitch("Sync notifications for new chats", "Uploads up to 100 recent records from the last 24 hours, including message contents. Replaces per-message excerpt sharing.",
            profile.id in snapshot.policy.serverSharingProfiles,
            snapshot.ready && (profile.id in snapshot.policy.serverSharingProfiles || sync.supported && snapshot.policy.capture && snapshot.access)) {
            if (it) confirm = "notifications" else { repository.shareServer(profile.id, false); sync.request() }
        }
        PreferenceSwitch("Upcoming calendar in new chats", "Uses the calendars and busy-only setting selected in Today. Calendar sharing must also be enabled there.",
            config.calendarOnline, config.calendarOnline || sync.supported && calendarSharing) {
            if (it) confirm = "calendar" else sync.settings.update(config.copy(calendarOnline = false))
        }
        Text(sync.status, style = MaterialTheme.typography.bodySmall)
        Text("Server caches expire within 24 hours without refresh. Disabling sharing or clearing history queues a cache update; an offline server keeps its old cache until the update arrives or expires. Copies already used in chat/model requests are separate.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = sync::request, enabled = sync.supported) { Text("Sync / retry now") }
        HorizontalDivider()
        Text("Offline chat context", style = MaterialTheme.typography.titleSmall)
        PreferenceSwitch("Notifications in local chats", "Uses a small on-phone snapshot. This setting does not upload notification history.",
            config.notificationsLocal, snapshot.ready && (config.notificationsLocal || snapshot.policy.capture && snapshot.access)) {
            sync.settings.update(config.copy(notificationsLocal = it))
        }
        PreferenceSwitch("Calendar in local chats", "Uses selected phone calendars with your busy-only setting. Requires Android calendar read permission.",
            config.calendarLocal) { sync.settings.update(config.copy(calendarLocal = it)) }
        Text("Reading context does not authorize replies, notification dismissal or calendar changes. Assistant actions still need an explicit command.", style = MaterialTheme.typography.bodySmall)
    }
    if (confirm != null) AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Share context with ${profile.name}?") },
        text = { Text(if (confirm == "notifications")
            "Recent notification titles and contents will be cached at ${profile.url} and automatically supplied to its model provider for new chats. Exclusions and ongoing-alert settings apply. This is broader than sharing matching excerpts with one message."
            else "Upcoming selected phone-calendar events will be automatically supplied to ${profile.url} and its model provider for new chats. Your calendar sharing and busy-only controls still apply.") },
        confirmButton = { TextButton(onClick = {
            if (confirm == "notifications") repository.shareServer(profile.id, true)
            else sync.settings.update(sync.settings.value.copy(calendarOnline = true))
            confirm = null; sync.request()
        }) { Text("Enable") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
}
