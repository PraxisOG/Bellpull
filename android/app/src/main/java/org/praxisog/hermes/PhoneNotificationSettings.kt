package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun PhoneNotificationSettings(repository: PhoneNotificationRepository, profile: ServerProfile?) {
    val snapshot by repository.state.collectAsState()
    val context = LocalContext.current
    var accessError by remember { mutableStateOf(false) }
    LaunchedEffect(snapshot.policy.capture, snapshot.access) {
        if (snapshot.policy.capture && snapshot.access) requestPhoneNotificationBinding(context)
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(60_000); now = System.currentTimeMillis(); repository.refresh() } }
    val visibleSnapshot = snapshot.copy(records = retainedNotifications(snapshot.records, snapshot.policy, now))
    PhoneNotificationControls(visibleSnapshot, profile, repository::capture, {
        accessError = !openPhoneNotificationAccess(context)
    }, { enabled -> profile?.let { repository.share(it.id, enabled) } }, repository::retention,
        repository::shareOngoing, repository::exclude, repository::clear)
    if (accessError) Text("Could not open Android settings. Open Settings → Apps → Special app access → Notification access.", color = MaterialTheme.colorScheme.error)
}

@Composable
internal fun PhoneNotificationControls(snapshot: PhoneNotificationSnapshot, profile: ServerProfile?,
    capture: (Boolean) -> Unit, openAccess: () -> Unit, share: (Boolean) -> Unit, retention: (Int) -> Unit,
    shareOngoing: (Boolean) -> Unit, exclude: (String, Boolean) -> Unit, clear: () -> Unit,
) {
    var history by rememberSaveable { mutableStateOf(false) }
    var apps by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var confirmShare by remember { mutableStateOf(false) }
    val policy = snapshot.policy
    Text("Save notifications Android exposes on this phone, including messages and ongoing alerts. Access starts only after you enable capture and grant Android notification access.",
        style = MaterialTheme.typography.bodySmall)
    Text(when {
        snapshot.error != null -> snapshot.error
        !snapshot.ready -> "Opening encrypted history…"
        !policy.capture -> "Capture is off. Saved history stays on this phone."
        !snapshot.access -> "Waiting for Android notification access."
        !snapshot.connected -> "Access granted. Waiting for the listener; history may be incomplete."
        else -> "Listening for phone notifications."
    }, style = MaterialTheme.typography.bodyMedium)
    if (snapshot.gap) Text("There was a listening gap. Some updates may be missing; reconnecting can recover only notifications still present.", style = MaterialTheme.typography.bodySmall)
    PreferenceSwitch("Capture phone notifications", "All apps by default, except Bellpull itself and your exclusions. Turning off also disables sharing.",
        policy.capture, snapshot.ready, capture)
    OutlinedButton(onClick = openAccess) { Text(if (snapshot.access) "Manage Android access" else "Grant Android access") }
    Text("Android may hide sensitive text. Custom notification views and notifications from before access was enabled may be unavailable.", style = MaterialTheme.typography.bodySmall)
    HorizontalDivider()
    Text("Hermes context", style = MaterialTheme.typography.titleSmall)
    if (profile == null) Text("Choose a server profile to enable sharing.", style = MaterialTheme.typography.bodySmall)
    PreferenceSwitch("Share relevant excerpts", profile?.let { "For ${it.name} (${it.url}). Adds up to six matching excerpts when you send a chat or voice message." }
        ?: "Sharing is configured separately for each server.",
        profile?.id in policy.sharingProfiles, snapshot.ready && policy.capture && snapshot.access && profile != null && profile.id !in policy.serverSharingProfiles) {
        if (it) confirmShare = true else share(false)
    }
    Text("Try “Summarize my notifications” or ask about a sender, app or topic. Selection uses words in your message. Rendering history never contacts Hermes.", style = MaterialTheme.typography.bodySmall)
    PreferenceSwitch("Include ongoing alerts in context", "Keep persistent system and media alerts eligible for sharing. They are always visible in local history.",
        policy.shareOngoing, snapshot.ready, shareOngoing)
    HorizontalDivider()
    Text("Keep history", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1, 7, 30).forEach { days -> FilterChip(policy.retentionDays == days, { retention(days) },
            enabled = snapshot.ready, label = { Text(if (days == 1) "1 day" else "$days days") }) }
    }
    Text("Up to 1,000 records, with the latest three text revisions per record. Expired records are excluded immediately and removed when Bellpull next runs.", style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { history = true }, enabled = snapshot.ready) { Text("View history (${snapshot.records.size})") }
        OutlinedButton(onClick = { apps = true }, enabled = snapshot.ready) { Text("App exclusions (${policy.excludedPackages.size})") }
        TextButton(onClick = { confirmClear = true }, enabled = snapshot.ready && snapshot.records.isNotEmpty()) { Text("Clear history") }
    }
    Text("Revoking Android access stops capture and new sharing. Saved history remains until it expires or you clear it. Excerpts already submitted, including pending sends, remain in those conversations.", style = MaterialTheme.typography.bodySmall)
    if (confirmShare && profile != null) AlertDialog(onDismissRequest = { confirmShare = false }, title = { Text("Share with ${profile.name}?") },
        text = { Text("Relevant notification text can include private messages. Excerpts will be sent to ${profile.url} and its configured model provider as part of messages you send. They may remain in server chat history after you turn this off or clear local history. Notification text does not authorize automatic replies or actions.") },
        confirmButton = { TextButton(onClick = { share(true); confirmShare = false }) { Text("Enable sharing") } },
        dismissButton = { TextButton(onClick = { confirmShare = false }) { Text("Cancel") } })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Clear local notification history?") },
        text = { Text("This deletes saved records and their revisions. Existing notifications will stay cleared until their source posts an update. Capture continues if enabled. Copies in chats are separate.") },
        confirmButton = { TextButton(onClick = { clear(); confirmClear = false }) { Text("Delete history") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
    if (history) PhoneNotificationHistory(snapshot.records, { history = false }, exclude)
    if (apps) NotificationExclusions(snapshot, exclude) { apps = false }
}

@Composable
private fun NotificationWindow(title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = close) { Text("Close") }
                }
                content()
            }
        }
    }
}

@Composable
internal fun PhoneNotificationHistory(records: List<PhoneNotificationRecord>, close: () -> Unit,
    exclude: (String, Boolean) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var activeOnly by rememberSaveable { mutableStateOf(false) }
    var includeOngoing by rememberSaveable { mutableStateOf(true) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val visible = remember(records, query, activeOnly, includeOngoing) {
        records.filter { (!activeOnly || it.removedAt == null) && (includeOngoing || !it.ongoing) &&
            (query.isBlank() || "${it.appName} ${it.packageName} ${it.title} ${it.text}".contains(query.trim(), true)) }
    }
    NotificationWindow("Phone notification history", close) {
        OutlinedTextField(query, { query = it }, label = { Text("Search apps or notification text") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("notification-search"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(activeOnly, { activeOnly = !activeOnly }, label = { Text("Last seen active") })
            FilterChip(includeOngoing, { includeOngoing = !includeOngoing }, label = { Text("Include ongoing") })
        }
        Text("${visible.size} observed records · Android can hide content. “Active” describes the last observation.", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f).testTag("notification-history"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (visible.isEmpty()) item { Text(if (records.isEmpty()) "No saved notifications yet." else "No matching notifications.") }
            items(visible, key = { it.id }) { record ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(record.appName, style = MaterialTheme.typography.labelLarge)
                        if (record.title.isNotBlank()) Text(record.title, style = MaterialTheme.typography.titleSmall)
                        Text(if (record.textUnavailable) "No readable text supplied by Android." else record.text.ifBlank { "No body text supplied by Android." },
                            maxLines = if (expanded == record.id) Int.MAX_VALUE else 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text("${notificationTime(record.updatedAt)} · ${if (record.removedAt != null) record.removalReason ?: "Removed" else "Last seen active"}" +
                            (if (record.ongoing) " · Ongoing" else "") + (if (record.groupSummary) " · Group summary" else ""), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { expanded = record.id.takeUnless { expanded == it } }) { Text(if (expanded == record.id) "Hide details" else "Details and revisions") }
                        if (expanded == record.id) {
                            SelectionContainer { Text("${record.packageName}\nFirst observed ${notificationTime(record.firstObserved)}\n${record.updateCount} updates" +
                                (record.removedAt?.let { "\nRemoved ${notificationTime(it)}" } ?: ""), style = MaterialTheme.typography.bodySmall) }
                            record.previous.forEach { previous -> Text("Earlier · ${notificationTime(previous.at)}\n${previous.title}\n${previous.text}", style = MaterialTheme.typography.bodySmall) }
                            TextButton(onClick = { exclude(record.packageName, true) }) { Text("Exclude app and delete its history") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationExclusions(snapshot: PhoneNotificationSnapshot, exclude: (String, Boolean) -> Unit, close: () -> Unit) {
    var packageName by rememberSaveable { mutableStateOf("") }
    val packages = (snapshot.records.map { it.packageName } + snapshot.policy.excludedPackages).distinct().sorted()
    NotificationWindow("App exclusions", close) {
        Text("All apps are included by default. Excluding an app deletes its local records and blocks future capture. Bellpull itself is always excluded.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(packageName, { packageName = it }, label = { Text("Package name, e.g. com.example.app") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { exclude(packageName.trim(), true); packageName = "" },
            enabled = packageName.trim().matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))) { Text("Exclude package") }
        LazyColumn(Modifier.weight(1f)) {
            items(packages, key = { it }) { pkg ->
                PreferenceSwitch(snapshot.records.firstOrNull { it.packageName == pkg }?.appName ?: pkg, "Exclude $pkg",
                    pkg in snapshot.policy.excludedPackages) { exclude(pkg, it) }
            }
        }
    }
}

@Composable
internal fun PhoneNotificationSendHint(repository: PhoneNotificationRepository, profileId: String?, text: String, settings: () -> Unit) {
    val snapshot by repository.state.collectAsState()
    val excerpt = remember(snapshot, profileId, text) { profileId?.let { repository.contextFor(it, text) } }
    if (excerpt != null) TextButton(onClick = settings, modifier = Modifier.testTag("notification-context-hint")) {
        Text("Relevant phone notifications will be included", style = MaterialTheme.typography.labelSmall)
    }
}

private fun notificationTime(at: Long): String = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at))
