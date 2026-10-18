package org.praxisog.hermes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.*
import org.praxisog.hermes.model.Artifact

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtime get() = BellpullRuntime.get(applicationContext)
    private val store get() = runtime.store
    private lateinit var profiles: ServerProfiles
    private lateinit var preferences: BellpullSettings
    private var messageReader: MessageReader? = null
    private var foreground by mutableStateOf(false)
    private var windowCutouts by mutableStateOf(emptyList<androidx.compose.ui.geometry.Rect>())
    private var focusTodayRevision by mutableStateOf(0)
    private var focusChatRevision by mutableStateOf(0)
    private var pendingNewChat = false
    private var pendingShare by mutableStateOf<IncomingShare?>(null)
    private var shareBusy by mutableStateOf(false)
    private var shareError by mutableStateOf<String?>(null)
    private var showShare by mutableStateOf(true)
    private var pendingNotification: Pair<String, String>? = null
    private var voiceOpen by mutableStateOf(false)
    private data class VoicePermissionRequest(val profile: String, val session: String, val mode: VoiceMode)
    private var microphoneRequest: VoicePermissionRequest? = null
    private var voiceNotificationRequest: VoicePermissionRequest? = null
    private val voiceNotificationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
        val request = voiceNotificationRequest; voiceNotificationRequest = null
        if (request != null && request.profile == runtime.profileId && request.session == store?.openSessionId) startVoice(request.mode)
    }
    private val microphonePermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        val request = microphoneRequest
        microphoneRequest = null
        if (request != null && request.profile == runtime.profileId && request.session == store?.openSessionId) {
            if (granted) continueVoice(request.mode) else runtime.voiceStartError = "Microphone permission is required to start voice."
        }
    }
    private var permissionProfile: String? = null
    private val notificationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (permissionProfile != null && permissionProfile == runtime.profileId) setNotifications(granted)
    }
    private var calendarPermissionProfile: String? = null
    private val calendarPermissions = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
        if (calendarPermissionProfile==runtime.profileId) runtime.planning?.permissionChanged()
        calendarPermissionProfile=null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = BellpullSettings(this)
        configureWindowChrome(window, preferences.value.immersiveMode)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            windowCutouts = insets.displayCutout?.boundingRects.orEmpty().map {
                androidx.compose.ui.geometry.Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
            }
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(window.decorView)
        val reader = MessageReader(this) { usage ->
            check(runtime.voice == null) { "End voice conversation before using Read aloud." }
            runtime.voiceOutputs.output(runtime.profileId?.let { VoiceSettings(this, it).read() } ?: VoiceConfiguration(), usage)
        }
        messageReader = reader
        profiles = ServerProfiles(this, scope, runtime, onLeaving = {
            reader.stop()
            voiceOpen = false
            pendingNotification = null
            pendingNewChat = false
        }, onConnected = ::connected)
        handleIntent(intent)

        setContent {
            DisposableEffect(preferences.value.immersiveMode) {
                configureWindowChrome(window, preferences.value.immersiveMode)
                onDispose { }
            }
            CompositionLocalProvider(LocalWindowCutouts provides windowCutouts) {
                // Discard all saved UI navigation/selection when the account changes.
                key(profiles.activeId) {
                    val current = store
                    val savedVoiceSettings = remember(profiles.activeId) { profiles.activeId?.let { VoiceSettings(applicationContext, it) } }
                    var voiceConfig by remember(savedVoiceSettings) { mutableStateOf(savedVoiceSettings?.read() ?: VoiceConfiguration()) }
                    if (voiceOpen && profiles.activeId != null) {
                        HermesTheme(appearance = preferences.value.appearance, palette = preferences.value.palette) {
                            VoicePanel(runtime.voice, voiceConfig, runtime.voiceStartError,
                                { savedVoiceSettings!!.save(it); voiceConfig = savedVoiceSettings.read() }, ::requestVoice, { voiceOpen = false },
                                onReturnToChat = { runtime.voice?.let { current?.openSession(it.sessionId) }; voiceOpen = false },
                                speechLab = runtime.speechLab, outputLab = runtime.voiceOutputs)
                        }
                    }
                    pendingShare?.takeIf { showShare }?.let { incoming ->
                        HermesTheme(appearance = preferences.value.appearance, palette = preferences.value.palette) {
                            IncomingShareDialog(incoming, (current?.sessions as? UiState.Ready)?.value.orEmpty(), current != null,
                                shareBusy, shareError, ::acceptShare,
                                { showShare = false; profiles.showSettings() }, { pendingShare = null; intent?.putExtra(IncomingShare.CONSUMED, true) })
                        }
                    }
                    val files = runtime.phase3
                    var preview by remember { mutableStateOf<Artifact?>(null) }
                    var previewDocked by remember { mutableStateOf(false) }
                    val downloadArtifact = files?.let { rememberArtifactDownload(it) }
                    val showPreview: (Artifact) -> Unit = { item ->
                        if (current?.openSessionId != item.sessionId) current?.openSession(item.sessionId)
                        preview = item
                        previewDocked = false
                        focusChatRevision++
                    }
                    LaunchedEffect(files, current?.openSessionId) { files?.open(current?.openSessionId) }
                    var exampleId by rememberSaveable { mutableStateOf<String?>(null) }
                    var exampleDraft by remember { mutableStateOf(TextFieldValue()) }
                    var exampleQuery by remember { mutableStateOf("") }
                    DisposableEffect(current?.openSessionId, profiles.activeId) {
                        current?.let { runtime.cache?.rememberSession(it.openSessionId) }
                        onDispose { reader.stop() }
                    }
                    HermesApp(
                        focusTodayRevision = focusTodayRevision,
                        planningView = runtime.planning?.let { planning -> {
                            PlanningScreen(planning,foreground) { write ->
                                calendarPermissionProfile=runtime.profileId
                                calendarPermissions.launch(if (write) arrayOf(android.Manifest.permission.READ_CALENDAR,android.Manifest.permission.WRITE_CALENDAR) else arrayOf(android.Manifest.permission.READ_CALENDAR))
                            }
                        } },
                        projects = runtime.projects,
                        onRetryProjectCreation = { runtime.projects?.let { workspace -> scope.launch {
                            when (val result = workspace.retryPending()) {
                                is org.praxisog.hermes.model.HermesResult.Ok -> { current?.loadSessions(); current?.openSession(result.value.id) }
                                is org.praxisog.hermes.model.HermesResult.Err -> Unit
                            }
                        } } },
                        pluginVersions = profiles::pluginVersions,
                        speechLab = { SpeechExperiments(runtime.speechLab) { runtime.voice?.close(); reader.stop() } },
                        voiceSettings = {
                            val profile = profiles.activeId
                            if (profile == null) androidx.compose.material3.Text("Select a server profile to configure voice conversations.")
                            else {
                                VoiceSettingsEditor(voiceConfig, runtime.voice == null,
                                    { savedVoiceSettings!!.save(it); voiceConfig = savedVoiceSettings.read() }, runtime.speechLab)
                                androidx.compose.material3.TextButton(onClick = ::toggleVoice) {
                                    androidx.compose.material3.Text(if (preferences.value.debuggingVoiceInterface) "Open voice conversation" else if (runtime.voice != null) "End voice" else "Start voice conversation")
                                }
                                runtime.voiceStartError?.let { androidx.compose.material3.Text(it) }
                            }
                        },
                        voiceAnnouncementSettings = {
                            VoiceAnnouncementSettings(voiceConfig, savedVoiceSettings != null) {
                                savedVoiceSettings?.save(it); voiceConfig = savedVoiceSettings?.read() ?: it
                            }
                        },
                        voiceOutputSettings = {
                            VoiceOutputSettings(voiceConfig, runtime.voiceOutputs, runtime.voice == null,
                                canSave = savedVoiceSettings != null, beforeChange = { reader.stop() }) {
                                savedVoiceSettings?.save(it); voiceConfig = savedVoiceSettings?.read() ?: it
                            }
                        },
                        sessionSource = current?.sessionSource,
                        onSessionSourceChange = current?.let { currentStore -> { source ->
                            runtime.cache?.rememberSessionSource(source)
                            currentStore.chooseSessionSource(source)
                        } },
                        sessions = current?.sessions ?: UiState.Ready(listOf(BellpullExample.session)),
                        history = current?.history ?: UiState.Ready(if (exampleId == null) emptyList() else BellpullExample.messages),
                        turn = current?.turn ?: TurnState.Idle,
                        messageStats = current?.messageStats.orEmpty(),
                        openSessionId = current?.openSessionId ?: exampleId.takeIf { current == null },
                        openSession = current?.openSession ?: BellpullExample.session.takeIf { current == null && exampleId != null },
                        now = System.currentTimeMillis() / 1000.0,
                        onOpenSession = { preview = null; if (current == null) exampleId = BellpullExample.ID else current.openSession(it) },
                        onCloseSession = { if (current == null) exampleId = null else current.closeSession() },
                        onSend = { text -> current?.let { if (files != null) files.send(it, text) else it.send(text) } ?: false }, onStop = { current?.stop() },
                        onRetrySessions = { current?.loadSessions() }, onRetryHistory = { current?.refreshHistory() },
                        onNewSession = { preview = null; if (current == null) { exampleId = null; exampleDraft = TextFieldValue() } else current.newSession() },
                        creatingSession = current?.creatingSession ?: false,
                        createError = current?.createError, offline = current?.offline,
                        onDisconnect = profiles::showSettings,
                        query = current?.searchQuery ?: exampleQuery,
                        onQueryChange = { if (current == null) exampleQuery = it else current.setQuery(it) },
                        apps = current?.apps.orEmpty(), onLaunchApp = { InstalledApps.launch(this, it) },
                        onAskHermes = { current?.askHermes(it) },
                        prefill = current?.pendingPrompt.orEmpty(), pendingUserText = current?.pendingUserText,
                        unreadIds = current?.readState?.unreadIds.orEmpty(),
                        completionRevision = current?.completionRevision ?: 0,
                        onReadThrough = { id, message -> current?.readState?.readThroughAliases(runtime.projects?.aliases(id) ?: setOf(id), message) },
                        foreground = foreground, runPresentation = current?.runPresentation,
                        runLabels = current?.runLabels.orEmpty(), onRetryRun = { current?.retryRun() },
                        onAbandonRun = { current?.abandonRunAfterReview() },
                        onApproval = { id, choice -> current?.respondToApproval(id, choice) },
                        notificationsAvailable = current != null && runtime.notificationEventsAvailable,
                        onNotificationsChange = ::requestNotifications,
                        focusChatRevision = focusChatRevision,
                        onVisibleSession = { runtime.visibleSessionId = it.takeIf { current != null } },
                        draft = current?.draft ?: exampleDraft,
                        onDraftChange = { if (current == null) exampleDraft = it else current.updateDraft(it) },
                        preferences = preferences.value, onPreferencesChange = preferences::update,
                        onReadMessage = { reader.read(it.id, it.content.orEmpty()) }, readingMessageId = reader.readingMessageId,
                        onBranchMessage = { current?.branchMessage(it) },
                        branchingEnabled = current?.supportsMessageBranching ?: false,
                        editingEnabled = current?.supportsPromptEditing ?: false,
                        onForkEdit = { message, text -> current?.forkAndEdit(message, text) },
                        branchingSession = current?.branchingSession ?: false, branchError = current?.branchError,
                        onDismissBranchError = { current?.dismissBranchError() },
                        disconnected = current == null, settingsRevision = profiles.settingsRevision,
                        coding = runtime.coding,
                        extraTools = runtime.client?.let { client -> {
                            FileSharingStatus(files?.filesSupported == true, files?.runAttachmentsSupported == true,
                                runtime::refreshCapabilities)
                            if (files?.filesSupported == true) AttachmentBrowser(files.artifacts,
                                (current?.sessions as? UiState.Ready)?.value.orEmpty(), files.errors["artifacts"],
                                files::refreshArtifacts, showPreview, { preview = null; current?.openSession(it); focusChatRevision++ },
                                files.storage, files.warnings, if (files.lifecycleSupported) files::deleteArtifact else null)
                            LiveGpuPanel(client, foreground)
                        } },
                        modelControl = files?.takeIf { it.modelsSupported }?.let { workspace -> current?.openSessionId?.let { id ->
                            { ModelControl(workspace, id) }
                        } },
                        composerLeading = current?.openSessionId?.let { id -> {
                            androidx.compose.foundation.layout.Row {
                                val active = runtime.voice != null || runtime.voiceRequestedSession != null
                                VoiceButton(active, active || preferences.value.debuggingVoiceInterface || runtime.voiceProblem(voiceConfig) == null,
                                    preferences.value.reduceMotion, ::toggleVoice, ::holdVoice, ::releaseVoice)
                                if (files != null) {
                                    if (!files.runAttachmentsSupported) UnavailableAttachmentsButton(files.filesSupported)
                                    else AttachButton(files, id) { target ->
                                        if (current.drafts[target].text.isBlank()) current.drafts.set(target, TextFieldValue("Attached files"))
                                    }
                                }
                            }
                        } },
                        attachmentStrip = current?.openSessionId?.let { id -> {
                            val problem = runtime.voiceProblem(voiceConfig)
                            VoiceInlineStatus(runtime.voice, runtime.voiceStartError ?: problem,
                                profiles::showSettings, {
                                    runtime.voice?.let { voice ->
                                        if (voice.sessionId == id) voice.putWordsInDraft()
                                    }
                                })
                            files?.let { AttachmentStrip(it, id) }
                        } },
                        uploading = files?.sending?.get(current?.openSessionId) == true || (files?.importing?.get(current?.openSessionId) ?: 0) > 0,
                        previewPane = preview?.takeIf { previewDocked }?.let { item -> files?.let { workspace ->
                            { modifier -> ArtifactPreview(workspace, item, modifier) { preview = null } }
                        } },
                        previewDialog = preview?.takeUnless { previewDocked }?.let { item -> files?.let { workspace -> {
                            ArtifactPreviewDialog(onClose = { preview = null }) {
                                ArtifactPreview(workspace, item, onDock = { previewDocked = true }, onClose = { preview = null })
                            }
                        } } },
                        artifacts = files?.artifacts.orEmpty().mapNotNull { item -> current?.openSessionId?.let(item::forSession) }
                            .filter { files?.drafts?.none { draft -> draft.id == it.id } == true },
                        onViewArtifact = showPreview,
                        onDownloadArtifact = { downloadArtifact?.invoke(it) },
                        serverProfiles = {
                            ServerProfilesPanel(profiles.profiles, profiles.activeId, profiles.status, profiles.checking,
                                profiles::add, { profiles.connect(it) }, profiles::signOut, profiles::remove,
                                profiles::removeAll, profiles::rename)
                            if (files?.modelsSupported == true) ModelControl(files, null)
                        },
                    )
                }
            }
        }
    }

    private fun connected(current: HermesStore) {
        showShare = true
        pendingNotification?.let { (profile, session) ->
            if (profile == runtime.profileId) { current.openSession(session); focusChatRevision++ }
        }
        pendingNotification = null
        if (pendingNewChat) { pendingNewChat = false; current.newSession() }
        scope.launch {
            val apps = withContext(Dispatchers.IO) { InstalledApps.load(this@MainActivity) }
            if (store === current) current.updateApps(apps)
        }
        if (foreground && preferences.value.notifications) setNotifications(true)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(ReminderAlerts.OPEN_INBOX, false) == true) {
            if (intent.getStringExtra(ReminderAlerts.PROFILE) == profiles.activeId) focusTodayRevision++
            else profiles.showSettings()
            return
        }
        if (intent?.action == VoiceService.OPEN) {
            runtime.voice?.let { store?.openSession(it.sessionId) }
            voiceOpen = preferences.value.debuggingVoiceInterface
            return
        }
        IncomingShare.from(intent)?.let {
            pendingShare = it; shareError = null; showShare = true
            return
        }
        ReplyNotifications.session(intent)?.let { session ->
            if (!ReplyNotifications.belongsTo(intent, profiles.activeId)) {
                profiles.showSettings()
                android.widget.Toast.makeText(this, "Switch to the notification's server profile to open this chat.",
                    android.widget.Toast.LENGTH_LONG).show()
                return
            }
            if (store != null) { store?.openSession(session); focusChatRevision++ }
            else pendingNotification = profiles.activeId!! to session
            return
        }
        if (WidgetIntents.isNewChat(intent)) {
            if (store != null) store?.newSession()
            else if (profiles.activeId != null) pendingNewChat = true
        }
    }

    private fun acceptShare(selectedId: String?) {
        val incoming = pendingShare ?: return
        val current = store ?: return
        val client = runtime.client ?: return
        val files = runtime.phase3
        if (incoming.uris.isNotEmpty() && files?.runAttachmentsSupported != true) {
            shareError = "This server needs the Bellpull attachment extension to accept shared files."
            return
        }
        if (shareBusy) return
        shareBusy = true; shareError = null
        scope.launch {
            try {
                val id = selectedId ?: when (val result = withContext(Dispatchers.IO) { client.createSession(selection = files?.defaultSelection) }) {
                    is org.praxisog.hermes.model.HermesResult.Ok -> result.value.id
                    is org.praxisog.hermes.model.HermesResult.Err -> { shareError = result.error.toUserMessage(); return@launch }
                }
                if (store !== current) { shareError = "The server profile changed. Choose the destination again."; return@launch }
                val addition = incoming.text.ifBlank { "Attached files" }
                val combined = listOf(current.drafts[id].text, addition).filter { it.isNotBlank() }.joinToString("\n\n")
                current.drafts.set(id, TextFieldValue(combined, androidx.compose.ui.text.TextRange(combined.length)))
                files?.addUris(id, incoming.uris)
                current.loadSessions(); current.openSession(id); focusChatRevision++
                pendingShare = null; intent?.putExtra(IncomingShare.CONSUMED, true)
            } finally { shareBusy = false }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::preferences.isInitialized) configureWindowChrome(window, preferences.value.immersiveMode)
    }
    override fun onStop() {
        store?.let { runtime.cache?.rememberSession(it.openSessionId) }
        messageReader?.stop()
        super.onStop()
    }
    override fun onResume() {
        super.onResume()
        foreground = true
        runtime.delivery?.refresh()
        store?.loadSessions(); store?.refreshHistory(); runtime.refreshCapabilities()
        if (preferences.value.notifications && store != null) setNotifications(true)
    }
    override fun onPause() { foreground = false; if (runtime.voiceButtonHeld) releaseVoice(true); super.onPause() }
    override fun onDestroy() {
        profiles.close()
        messageReader?.close()
        scope.cancel()
        super.onDestroy()
    }
    private fun requestVoice(mode: VoiceMode) {
        if (!voiceReady()) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val profile = runtime.profileId; val session = store?.openSessionId
            if (profile == null || session == null) { runtime.voiceStartError = "Open a conversation before starting voice."; return }
            microphoneRequest = VoicePermissionRequest(profile, session, mode)
            microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO)
        } else continueVoice(mode)
    }
    private fun toggleVoice() {
        messageReader?.stop()
        if (preferences.value.debuggingVoiceInterface) { voiceOpen = true; return }
        runtime.voice?.let { if (it.dictationToDraft && it.holdTurn) it.endPushToTalk() else it.close(); return }
        runtime.voiceDictationToDraft = false
        requestVoice(VoiceMode.CALL)
    }
    private fun holdVoice() {
        messageReader?.stop()
        val voice = runtime.voice
        if (voice != null && voice.sessionId != store?.openSessionId) {
            runtime.voiceStartError = "End voice in its original conversation before dictating here."; return
        }
        runtime.voiceButtonHeld = true
        if (voice != null) voice.beginPushToTalk(toDraft = true)
        else { runtime.voiceDictationToDraft = true; requestVoice(VoiceMode.PUSH_TO_TALK) }
    }
    private fun releaseVoice(canceled: Boolean) {
        runtime.voiceButtonHeld = false
        if (microphoneRequest?.mode == VoiceMode.PUSH_TO_TALK) microphoneRequest = null
        if (voiceNotificationRequest?.mode == VoiceMode.PUSH_TO_TALK) voiceNotificationRequest = null
        runtime.voice?.takeIf { it.dictationToDraft }?.endPushToTalk(canceled)
        if (runtime.voice == null && runtime.voiceDictationToDraft) runtime.voiceRequestedSession = null
    }
    private fun continueVoice(mode: VoiceMode) {
        if (!voiceReady()) return
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val profile = runtime.profileId; val session = store?.openSessionId
            if (profile != null && session != null) {
                voiceNotificationRequest = VoicePermissionRequest(profile, session, mode)
                voiceNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        } else startVoice(mode)
    }
    private fun startVoice(mode: VoiceMode) {
        if (!voiceReady()) return
        val id = store?.openSessionId
        if (id == null) { runtime.voiceStartError = "Open or create a conversation before starting voice."; return }
        messageReader?.stop()
        runtime.voiceRequestedSession = id; runtime.voiceRequestedMode = mode
        runCatching { startForegroundService(Intent(this, VoiceService::class.java)) }.onFailure {
            runtime.voiceRequestedSession = null
            runtime.voiceStartError = "Could not start voice. Try again with Bellpull open."
        }
    }
    private fun voiceReady(): Boolean {
        val profile = runtime.profileId
        if (profile == null) { runtime.voiceStartError = "Open a conversation before starting voice."; return false }
        runtime.voiceStartError = runtime.voiceProblem(VoiceSettings(this, profile).read())
        return runtime.voiceStartError == null
    }
    private fun requestNotifications(enabled: Boolean) {
        if (enabled && android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissionProfile = runtime.profileId
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else setNotifications(enabled)
    }
    private fun setNotifications(enabled: Boolean) {
        val allow = enabled && store != null && runtime.notificationEventsAvailable
        preferences.update(preferences.value.copy(notifications = allow))
        runtime.delivery?.refresh()
        val listener = Intent(this, NotificationListenerService::class.java)
        if (allow) {
            runCatching { startForegroundService(listener) }.onFailure {
                preferences.update(preferences.value.copy(notifications = false))
                android.widget.Toast.makeText(this, "Could not start notifications. Try enabling them again.", android.widget.Toast.LENGTH_LONG).show()
            }
            runtime.catchUpNotifications()
        } else stopService(listener)
    }
}
