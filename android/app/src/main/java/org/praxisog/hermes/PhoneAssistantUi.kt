package org.praxisog.hermes

import android.Manifest
import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@Composable
internal fun PhoneAssistantSettings(foreground: Boolean, onOpen: () -> Unit) {
    val context = LocalContext.current
    val roles = remember { context.getSystemService(RoleManager::class.java) }
    var held by remember { mutableStateOf(roles?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        held = roles?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
    }
    LaunchedEffect(foreground) { if (foreground) held = roles?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true }
    Text(if (held) "Bellpull is your default assistant" else "Use Bellpull as your phone assistant", style = MaterialTheme.typography.titleMedium)
    Text("Open Bellpull with your phone’s assistant gesture. Speak or type a phone action, or continue in Hermes or a local chat. Gesture and button availability depend on your phone.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            error = null
            if (!launchSystemSettings(assistantSettingsIntents()) { picker.launch(it) })
                error = "Open Android Settings → Default apps → Digital assistant app and select Bellpull."
        }) { Text(if (held) "Assistant settings" else "Choose default assistant") }
        OutlinedButton(onClick = onOpen, modifier = Modifier.testTag("open-phone-assistant")) { Text("Try assistant") }
    }
    Text("Phone actions work without Hermes. Speech uses your Voice input setting. This version listens while the panel is open; it has no wake word or screen-reading feature.", style = MaterialTheme.typography.bodySmall)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneAssistantPanel(runtime: BellpullRuntime, foreground: Boolean, draft: String, onDraftChange: (String) -> Unit, onDismiss: () -> Unit,
    onHermes: ((String) -> Unit)?, onLocal: ((String) -> Unit)?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var candidates by remember { mutableStateOf(emptyList<LaunchableApp>()) }
    var apps by remember { mutableStateOf(emptyList<LaunchableApp>()) }
    var appsReady by remember { mutableStateOf(false) }
    var autoListenAttempted by rememberSaveable { mutableStateOf(false) }
    val locked = { context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { InstalledApps.load(context) }; appsReady = true }
    val run: (String) -> Unit = { text ->
        candidates = emptyList(); notice = null
        if (locked()) notice = "Unlock your phone to use the assistant."
        else when (val action = PhoneActions.parse(text)) {
            is PhoneAction.Clarify -> notice = action.message
            PhoneAction.Conversation -> notice = "Continue this request in a chat."
            is PhoneAction.App -> {
                val exact = apps.filter { it.label.equals(action.query, ignoreCase = true) }
                val matches = exact.ifEmpty { searchApps(apps, action.query) }
                if (!appsReady) notice = "Still loading apps. Try again in a moment."
                else if (exact.size == 1) {
                    if (InstalledApps.launch(context, exact.single())) notice = "Opened ${exact.single().label}." else notice = "Could not open ${exact.single().label}."
                } else {
                    candidates = matches
                    notice = if (matches.isEmpty()) "No matching installed app. Try its full name." else "Choose the app you meant."
                }
            }
            else -> notice = PhoneActions.launch(context, action) ?: when (action) {
                is PhoneAction.Timer, is PhoneAction.Alarm -> "Opened Clock with your request. Check Clock for the result."
                is PhoneAction.Dial -> "Opened the dialer. Place the call there."
                is PhoneAction.Message -> "Opened your message draft. Send it in your messaging app."
                is PhoneAction.Maps -> "Opened maps for your destination."
                is PhoneAction.Share -> "Opened sharing options."
                else -> "Opened the website."
            }
        }
    }
    val currentRun by rememberUpdatedState(run)
    val speech = remember(runtime.profileId) {
        AssistantSpeech(scope, AndroidVoiceCapture(scope), {
            check(runtime.voice == null && runtime.voiceRequestedSession == null) { "End the current voice conversation before speaking here." }
            check(context.getSystemService(android.media.AudioManager::class.java).mode == android.media.AudioManager.MODE_NORMAL) { "Finish the current call before speaking here." }
            val config = runtime.profileId?.let { VoiceSettings(context, it).read() } ?: VoiceConfiguration()
            if (config.transcribeViaServer) {
                val connection = runtime.speechConnection()
                ServerVoiceTranscriber(ProfileSpeechTransport(connection), allowLocalFallback = config.allowLocalTranscriptionFallback) {
                    runtime.speechLab.fallbackTranscriber()
                }
            } else if (config.recognition == VoiceRecognition.LOCAL) runtime.speechLab.voiceTranscriber()
            else HttpVoiceTranscriber(config)
        }, { text -> onDraftChange(text); currentRun(text) })
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && foreground && !locked()) speech.start() else notice = "Microphone permission is needed to speak. You can still type."
    }
    DisposableEffect(speech) { onDispose { speech.cancel() } }
    LaunchedEffect(foreground) {
        if (!foreground) speech.cancel()
        else if (!autoListenAttempted) {
            autoListenAttempted = true
            if (draft.isBlank() && !locked() && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) speech.start()
        }
    }
    ModalBottomSheet(onDismissRequest = { speech.cancel(); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Bellpull assistant", style = MaterialTheme.typography.headlineSmall)
            Text("Try ‘timer for 10 minutes’, ‘alarm for 7:30 am’, ‘open Maps’ or ‘navigate to Union Station’.")
            OutlinedTextField(draft, { onDraftChange(it); notice = null; candidates = emptyList() }, label = { Text("Your request") },
                modifier = Modifier.fillMaxWidth().testTag("assistant-request"), minLines = 2, maxLines = 5,
                enabled = !speech.listening && !speech.busy)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { run(draft) }, enabled = foreground && draft.isNotBlank() && !speech.listening && !speech.busy,
                    modifier = Modifier.testTag("assistant-run")) { Text("Go") }
                OutlinedButton(onClick = {
                    if (speech.listening) speech.finish()
                    else if (speech.busy) speech.cancel()
                    else if (locked()) notice = "Unlock your phone to speak."
                    else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) speech.start()
                    else permission.launch(Manifest.permission.RECORD_AUDIO)
                }, enabled = foreground) { Text(if (speech.listening) "Done speaking" else if (speech.busy) "Cancel recognition" else "Speak") }
                TextButton(onClick = { speech.cancel(); onDismiss() }) { Text("Close") }
            }
            if (speech.listening) Text("Listening…")
            if (speech.busy) Text("Recognizing…")
            speech.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            notice?.let { Text(it, modifier = Modifier.testTag("assistant-result")) }
            candidates.forEach { app ->
                OutlinedButton(onClick = {
                    if (locked()) notice = "Unlock your phone to open an app."
                    else { notice = if (InstalledApps.launch(context, app)) "Opened ${app.label}." else "Could not open ${app.label}."; candidates = emptyList() }
                }) { Text("${app.label} · ${app.packageName}") }
            }
            HorizontalDivider()
            Text("Continue in a new chat", style = MaterialTheme.typography.titleSmall)
            Text("Your words open as a draft. Existing chat drafts stay saved.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { if (!locked()) { speech.cancel(); onHermes?.invoke(draft) } },
                    enabled = foreground && draft.isNotBlank() && !speech.listening && !speech.busy && onHermes != null) { Text("Hermes chat") }
                OutlinedButton(onClick = { if (!locked()) { speech.cancel(); onLocal?.invoke(draft) } },
                    enabled = foreground && draft.isNotBlank() && !speech.listening && !speech.busy && onLocal != null) { Text("Local chat") }
            }
            Text("You can also open a website, ‘dial 5551234567’, ‘text 5551234567: Running late’, or ‘share’ followed by text.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
