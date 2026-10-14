package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.text.input.TextFieldValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import org.praxisog.hermes.model.Message
import org.praxisog.hermes.model.Session

/**
 * The chat shell: session list, session detail, and the read-only context panel.
 *
 * Adaptive rather than optional — targetSdk 37 removed the large-screen orientation opt-out,
 * so the Fold's cover and unfolded widths are both first-class. Unfolded shows list and detail
 * side by side; folded shows one at a time with a back affordance. There is no navigation
 * library: three destinations driven by whether a session is open do not need a backstack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HermesApp(
    sessions: UiState<List<Session>>,
    history: UiState<List<Message>>,
    turn: TurnState,
    openSessionId: String?,
    openSession: Session?,
    now: Double,
    onOpenSession: (String) -> Unit,
    onCloseSession: () -> Unit,
    onSend: (String) -> Boolean,
    onStop: () -> Unit,
    onRetrySessions: () -> Unit,
    onRetryHistory: () -> Unit,
    onNewSession: () -> Unit,
    sessionSource: String? = "api_server",
    onSessionSourceChange: ((String?) -> Unit)? = null,
    creatingSession: Boolean = false,
    createError: org.praxisog.hermes.model.HermesError? = null,
    offline: OfflineState? = null,
    onDisconnect: (() -> Unit)? = null,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    apps: List<LaunchableApp> = emptyList(),
    onLaunchApp: (LaunchableApp) -> Unit = {},
    onAskHermes: (String) -> Unit = {},
    prefill: String = "",
    onReadMessage: (Message) -> Unit = {},
    onBranchMessage: (Message) -> Unit = {},
    readingMessageId: Int? = null,
    branchingEnabled: Boolean = false,
    editingEnabled: Boolean = false,
    onForkEdit: (Message, String) -> Unit = { _, _ -> },
    branchingSession: Boolean = false,
    branchError: org.praxisog.hermes.model.HermesError? = null,
    onDismissBranchError: () -> Unit = {},
    draft: TextFieldValue? = null,
    onDraftChange: (TextFieldValue) -> Unit = {},
    preferences: BellpullPreferences = BellpullPreferences(),
    onPreferencesChange: (BellpullPreferences) -> Unit = {},
    pendingUserText: String? = null,
    unreadIds: Set<String> = emptySet(),
    completionRevision: Long = 0,
    onReadThrough: (String, Int) -> Unit = { _, _ -> },
    foreground: Boolean = true,
    runPresentation: RunPresentation? = null,
    runLabels: Map<String, String> = emptyMap(),
    onRetryRun: () -> Unit = {},
    onApproval: (String, String) -> Unit = { _, _ -> },
    notificationsAvailable: Boolean = false,
    onNotificationsChange: (Boolean) -> Unit = {},
    focusChatRevision: Int = 0,
    onVisibleSession: (String?) -> Unit = {},
    onAbandonRun: () -> Unit = {},
    disconnected: Boolean = false,
    serverProfiles: (@Composable () -> Unit)? = null,
    settingsRevision: Int = 0,
    extraTools: (@Composable () -> Unit)? = null,
    planningView: (@Composable () -> Unit)? = null,
    modelControl: (@Composable () -> Unit)? = null,
    composerLeading: (@Composable () -> Unit)? = null,
    attachmentStrip: (@Composable () -> Unit)? = null,
    uploading: Boolean = false,
    previewPane: (@Composable (Modifier) -> Unit)? = null,
    previewDialog: (@Composable () -> Unit)? = null,
    artifacts: List<org.praxisog.hermes.model.Artifact> = emptyList(),
    onViewArtifact: (org.praxisog.hermes.model.Artifact) -> Unit = {},
    onDownloadArtifact: (org.praxisog.hermes.model.Artifact) -> Unit = {},
    projects: ProjectWorkspace? = null,
    onRetryProjectCreation: () -> Unit = {},
    pluginVersions: () -> List<ServerPluginVersion> = { emptyList() },
    speechLab: (@Composable () -> Unit)? = null,
    voiceSettings: (@Composable () -> Unit)? = null,
    voiceOutputSettings: (@Composable () -> Unit)? = null,
    voiceAnnouncementSettings: (@Composable () -> Unit)? = null,
    coding: CodingActivityWorkspace? = null,
) {
    var activityOpen by rememberSaveable(openSessionId) { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(if (projects?.selectedProject != null) WorkspaceMode.PROJECTS else WorkspaceMode.CHATS) }
    var drawerOnCover by rememberSaveable { mutableStateOf(openSessionId == null && !disconnected && projects?.selectedProject == null) }
    var expandedSearch by rememberSaveable { mutableStateOf(false) }
    var infoSheet by rememberSaveable { mutableStateOf(false) }
    var artifactsOpen by remember(openSessionId) { mutableStateOf(false) }
    var navigation by remember(openSessionId) { mutableStateOf<ChatNavigation?>(null) }
    var organizing by remember { mutableStateOf<Session?>(null) }
    val allSessions = (sessions as? UiState.Ready)?.value.orEmpty()
    val projectSessions = projects?.sessions(allSessions) ?: allSessions
    val scopedSessions = if (sessions is UiState.Ready) UiState.Ready(projectSessions) else sessions
    val projectUnread = projects?.unreadIds(allSessions, unreadIds) ?: unreadIds
    val projectLabels = projects?.runLabels(allSessions, runLabels) ?: runLabels
    val runningIds = runLabels.filterValues { it in setOf("Running", "Sending", "Reconnecting", "Stopping", "Needs approval") }.keys
    val selectProjectScope: (String) -> Unit = { id ->
        projects?.choose(id)
        expandedSearch = false
        if (id in setOf(ALL_PROJECT_CHATS, UNFILED_PROJECT_CHATS)) { mode = WorkspaceMode.CHATS; drawerOnCover = true }
        else { mode = WorkspaceMode.PROJECTS; drawerOnCover = false }
    }
    val screenState = rememberSaveableStateHolder()
    var previousSessionId by rememberSaveable { mutableStateOf(openSessionId) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val androidContext = androidx.compose.ui.platform.LocalContext.current
    val openSettings: () -> Unit = {
        mode = WorkspaceMode.SETTINGS; drawerOnCover = false; expandedSearch = false; infoSheet = false
    }
    LaunchedEffect(settingsRevision) { if (settingsRevision > 0) openSettings() }
    LaunchedEffect(openSessionId) {
        if (openSessionId != null && openSessionId != previousSessionId) {
            mode = WorkspaceMode.CHATS; drawerOnCover = false
        }
        previousSessionId = openSessionId
    }
    val open: (String) -> Unit = { mode = WorkspaceMode.CHATS; drawerOnCover = false; expandedSearch = false; onOpenSession(it) }
    LaunchedEffect(focusChatRevision) {
        if (focusChatRevision > 0) {
            mode = WorkspaceMode.CHATS; drawerOnCover = false; expandedSearch = false; infoSheet = false; navigation = null
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    HermesTheme(appearance = preferences.appearance, palette = preferences.palette, customAccent = preferences.customAccent) {
        DisposableEffect(coding, openSessionId, activityOpen, foreground, mode) {
            openSessionId?.let { coding?.observe(it, activityOpen && foreground && mode == WorkspaceMode.CHATS) }
            onDispose { openSessionId?.let { coding?.observe(it, false) } }
        }
        coding?.let { CodingWorkspaceDialog(it) }
        CompositionLocalProvider(LocalImmersiveHeaders provides preferences.immersiveMode,
            LocalLabeledControls provides preferences.labeledControls) {
            branchError?.let { error ->
                AlertDialog(onDismissRequest = onDismissBranchError,
                    title = { Text("Could not create branch") }, text = { Text(error.toUserMessage()) },
                    confirmButton = { TextButton(onClick = onDismissBranchError) { Text("Dismiss") } })
            }
            if (artifactsOpen) ConversationArtifactsDialog(artifacts, onDownloadArtifact,
                onView = { artifactsOpen = false; onViewArtifact(it) }, onClose = { artifactsOpen = false })
            organizing?.let { session -> projects?.let { workspace ->
                ProjectChatDialog(workspace, session) { organizing = null }
            } }
            if (expandedSearch) WorkspaceSearch(
                projectSessions, apps, now, openSessionId,
                query, onQueryChange, open, onLaunchApp,
                onAsk = { if (disconnected) openSettings() else {
                    onAskHermes(it); expandedSearch = false; mode = WorkspaceMode.CHATS
                } },
                onDismiss = { expandedSearch = false },
                scopeTitle = projects?.scopeTitle?.takeUnless { projects.selectedScope == ALL_PROJECT_CHATS },
                allConversations = allSessions)
            Surface(Modifier.fillMaxSize()) {
                val insets = if (preferences.immersiveMode)
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                        .union(WindowInsets.captionBar.only(WindowInsetsSides.Top))
                else WindowInsets.safeDrawing
                BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(insets)) {
                    val sideBySide = maxWidth >= 720.dp
                    var showList by rememberSaveable { mutableStateOf(true) }
                    var showContext by rememberSaveable { mutableStateOf(maxWidth >= 820.dp) }
                    val visibleSession = openSessionId.takeIf { foreground && mode == WorkspaceMode.CHATS &&
                        (sideBySide || !drawerOnCover) && !expandedSearch && !infoSheet && !artifactsOpen && organizing == null && branchError == null && previewPane == null && previewDialog == null }
                    DisposableEffect(visibleSession) {
                        onVisibleSession(visibleSession)
                        onDispose { onVisibleSession(null) }
                    }
                    BackHandler(enabled = mode != WorkspaceMode.SETTINGS && !expandedSearch && !infoSheet && !artifactsOpen &&
                        previewPane == null && previewDialog == null && (if (sideBySide) showList else drawerOnCover)) {
                        if (sideBySide) showList = false else drawerOnCover = false
                    }
                    BackHandler(enabled = mode == WorkspaceMode.SETTINGS && !expandedSearch && !infoSheet) {
                        mode = WorkspaceMode.CHATS
                        drawerOnCover = openSessionId == null && !disconnected
                        focusManager.clearFocus(force = true)
                        keyboard?.hide()
                    }
                    if (infoSheet && openSessionId != null) {
                        ModalBottomSheet(onDismissRequest = { infoSheet = false },
                            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                            ContextPanel(openSession, now, onDisconnect = if (serverProfiles != null) openSettings else onDisconnect,
                                modifier = Modifier.fillMaxWidth().fillMaxHeight(.8f), onClose = { infoSheet = false }, modelControl = modelControl)
                        }
                    }
                    val animate = paneMotionEnabled(preferences.reduceMotion)
                    val listPane: @Composable (Modifier) -> Unit = { paneModifier ->
                        Column(paneModifier) {
                            SessionListScreen(state = scopedSessions, now = now, selectedId = openSessionId,
                                onOpen = open, onRetry = onRetrySessions, onNewSession = onNewSession,
                                creating = creatingSession, createError = createError, offline = offline,
                                modifier = Modifier.weight(1f), showNewChatButton = !sideBySide,
                                unreadIds = projectUnread, completionRevision = completionRevision,
                                runLabels = projectLabels,
                                pinnedIds = projects?.pinnedIds(projectSessions).orEmpty(),
                                recentOrder = projects?.selectedProject != null,
                                projectFilters = projects?.let { workspace -> { closeFilters -> ProjectFilterItems(workspace, unreadIds, runningIds,
                                    { id -> closeFilters(); selectProjectScope(id) }, { closeFilters(); mode = WorkspaceMode.PROJECTS; drawerOnCover = false }) } },
                                onOrganize = projects?.let { { session -> organizing = session } },
                                titleOnly = preferences.titleOnlyConversations,
                                source = sessionSource, onSourceChange = onSessionSourceChange,
                                filterSummary = listOfNotNull(projects?.scopeTitle?.takeIf { projects.selectedScope != ALL_PROJECT_CHATS }, sessionSource).takeIf { it.isNotEmpty() }?.joinToString(" · "),
                                onExpandedSearch = { expandedSearch = true })
                            WorkspaceModes(mode) { mode = it; drawerOnCover = false }
                        }
                    }
                    val detailPane: @Composable (Modifier) -> Unit = { paneModifier ->
                        Column(paneModifier) {
                            ConversationToolbar(
                                title = if (mode == WorkspaceMode.CHATS) openSession?.title?.takeIf { it.isNotBlank() }
                                    ?: (if (disconnected) "Bellpull" else "Conversation") else mode.title,
                                showList = sideBySide && showList, sideBySide = sideBySide,
                                onNavigate = { if (sideBySide) showList = !showList else drawerOnCover = true },
                                onNewSession = { mode = WorkspaceMode.CHATS; drawerOnCover = false; onNewSession() }, creatingSession = creatingSession,
                                hasSession = mode == WorkspaceMode.CHATS && openSessionId != null,
                                hasHistory = mode == WorkspaceMode.CHATS && history is UiState.Ready,
                                onPrompts = { navigation = ChatNavigation.PROMPTS },
                                onSearch = { navigation = ChatNavigation.SEARCH },
                                onSettings = openSettings,
                                onArtifacts = { artifactsOpen = true },
                                onShare = if (history is UiState.Ready && history.value.isNotEmpty()) ({
                                    ArtifactSharing.text(androidContext, history.value.filter { it.role in setOf("user", "assistant") && !it.content.isNullOrBlank() }
                                        .joinToString("\n\n") { "${if (it.role == "user") "You" else "Hermes"}:\n${it.content}" })
                                }) else null,
                                showContext = sideBySide && showContext,
                                onContext = { if (sideBySide) showContext = !showContext else infoSheet = true })
                            if (createError != null) NewChatFailure(createError)
                            when (mode) {
                                WorkspaceMode.SETTINGS -> SettingsWorkspace(preferences, onPreferencesChange,
                                    notificationsAvailable, onNotificationsChange, serverProfiles, pluginVersions, speechLab, voiceSettings, voiceOutputSettings, voiceAnnouncementSettings)
                                WorkspaceMode.TOOLS -> ToolsWorkspace({ expandedSearch = true },
                                    if (openSessionId == null) null else ({ infoSheet = true }), extraTools)
                                WorkspaceMode.PROJECTS -> ProjectsPanel(projects, allSessions, now, unreadIds, runLabels, runningIds,
                                    selectProjectScope, open, onNewSession, { expandedSearch = true }, { organizing = it }, onRetryProjectCreation,
                                    preferences.titleOnlyConversations)
                                WorkspaceMode.TODAY -> if (planningView != null) planningView() else Column(Modifier.padding(24.dp)) {
                                    Text("Coming soon", style = MaterialTheme.typography.titleLarge)
                                    Text("Reminders and follow-ups will appear here.")
                                }
                                WorkspaceMode.CHATS -> if (openSessionId == null) {
                                    if (disconnected) DisconnectedWelcome({ open(BellpullExample.ID) }, openSettings)
                                    else EmptyDetail()
                                } else {
                                    if (disconnected) DisconnectedBanner(openSettings)
                                    RunControls(runPresentation, onRetryRun, onApproval, onAbandonRun)
                                    if (coding != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(selected = !activityOpen, onClick = { activityOpen = false }, label = { Text("Chat") })
                                        FilterChip(selected = activityOpen, onClick = { activityOpen = true }, label = { Text("Activity") }, modifier = Modifier.testTag("open-coding-activity"))
                                    }
                                    if (activityOpen && coding != null) CodingActivityPanel(coding.snapshot, coding.loading,
                                        coding.supported, coding.disconnected, coding.problem, runPresentation?.label,
                                        coding::retry, { coding.inspect(it) }, { activityOpen = false }, onStop,
                                        canStop = (turn as? TurnState.Streaming)?.canStop == true,
                                        modifier = Modifier.weight(1f))
                                    else screenState.SaveableStateProvider(openSessionId) {
                                        SessionDetailScreen(history = history, turn = turn, onSend = onSend,
                                            onStop = onStop, onRetry = onRetryHistory, prefill = prefill,
                                            draft = draft, onDraftChange = onDraftChange,
                                            collapseThinking = preferences.collapseThinking, reduceMotion = preferences.reduceMotion,
                                            denseChatWindow = preferences.denseChatWindow,
                                            pendingUserText = pendingUserText,
                                            sendBlocked = disconnected || runPresentation?.busy == true || uploading,
                                            composerLeading = composerLeading, attachmentStrip = attachmentStrip,
                                            artifacts = artifacts, onViewArtifact = onViewArtifact, onDownloadArtifact = onDownloadArtifact,
                                            readEnabled = foreground && !expandedSearch && !infoSheet && !artifactsOpen && organizing == null && branchError == null && previewDialog == null && previewPane == null,
                                            onReadThrough = { onReadThrough(openSessionId, it) },
                                            navigation = navigation, onDismissNavigation = { navigation = null },
                                            onReadMessage = onReadMessage, onBranchMessage = onBranchMessage,
                                            readingMessageId = readingMessageId, branchingEnabled = branchingEnabled,
                                            editingEnabled = editingEnabled, onForkEdit = onForkEdit,
                                            branchingSession = branchingSession, modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                    if (sideBySide) {
                        Row(Modifier.fillMaxSize()) {
                            SlidingPane(showList && previewPane == null, fromLeft = true, animate = animate) {
                                Row(Modifier.fillMaxHeight()) {
                                    listPane(Modifier.width(240.dp).fillMaxHeight())
                                    VerticalDivider()
                                }
                            }
                            detailPane(Modifier.weight(1f).fillMaxHeight())
                            SlidingPane(previewPane != null || (showContext && openSessionId != null && mode == WorkspaceMode.CHATS),
                                fromLeft = false, animate = animate) {
                                Row(Modifier.fillMaxHeight()) {
                                    VerticalDivider()
                                    if (previewPane != null) previewPane(Modifier.width(360.dp).fillMaxHeight())
                                    else ContextPanel(openSession, now, onDisconnect = if (serverProfiles != null) openSettings else onDisconnect,
                                        modifier = Modifier.width(200.dp).fillMaxHeight(), onClose = { showContext = false }, modelControl = modelControl)
                                }
                            }
                        }
                    } else {
                        AnimatedContent(drawerOnCover, label = "Conversations navigation",
                            transitionSpec = {
                                if (!animate) EnterTransition.None togetherWith ExitTransition.None
                                else {
                                    val direction = if (targetState) AnimatedContentTransitionScope.SlideDirection.Right
                                        else AnimatedContentTransitionScope.SlideDirection.Left
                                    slideIntoContainer(direction, tween(180)) togetherWith slideOutOfContainer(direction, tween(180))
                                }
                            }, modifier = Modifier.fillMaxSize()) { listVisible ->
                            if (listVisible) listPane(Modifier.fillMaxSize()) else detailPane(Modifier.fillMaxSize())
                        }
                    }
                    if (!sideBySide && previewPane != null) previewPane(Modifier.fillMaxSize())
                    previewDialog?.invoke()
                }
            }
        }
    }
}

@Composable
private fun NewChatFailure(error: org.praxisog.hermes.model.HermesError) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Text("Could not start a new chat. ${error.toUserMessage()}",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun EmptyDetail() {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, androidx.compose.ui.Alignment.CenterVertically),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        HermesMark(size = 56.dp)
        Text("Pick a session", style = MaterialTheme.typography.titleLarge)
        Text("Continue a conversation, or make room for a new idea.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
