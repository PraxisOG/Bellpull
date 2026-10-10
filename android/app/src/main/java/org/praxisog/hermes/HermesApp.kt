package org.praxisog.hermes

import androidx.compose.foundation.layout.*
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
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onRetrySessions: () -> Unit,
    onRetryHistory: () -> Unit,
    onNewSession: () -> Unit,
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
) {
    var mode by rememberSaveable { mutableStateOf(WorkspaceMode.CHATS) }
    var drawerOnCover by rememberSaveable { mutableStateOf(openSessionId == null) }
    var expandedSearch by rememberSaveable { mutableStateOf(false) }
    var infoSheet by rememberSaveable { mutableStateOf(false) }
    var navigation by remember(openSessionId) { mutableStateOf<ChatNavigation?>(null) }
    val screenState = rememberSaveableStateHolder()
    var previousSessionId by rememberSaveable { mutableStateOf(openSessionId) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
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
        branchError?.let { error ->
            AlertDialog(onDismissRequest = onDismissBranchError,
                title = { Text("Could not create branch") }, text = { Text(error.toUserMessage()) },
                confirmButton = { TextButton(onClick = onDismissBranchError) { Text("Dismiss") } })
        }
        if (expandedSearch) WorkspaceSearch(
            (sessions as? UiState.Ready)?.value.orEmpty(), apps, now, openSessionId,
            query, onQueryChange, open, onLaunchApp,
            onAsk = { onAskHermes(it); expandedSearch = false; mode = WorkspaceMode.CHATS },
            onDismiss = { expandedSearch = false })
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val sideBySide = maxWidth >= 720.dp
                var showList by rememberSaveable { mutableStateOf(true) }
                var showContext by rememberSaveable { mutableStateOf(maxWidth >= 820.dp) }
                val visibleSession = openSessionId.takeIf { foreground && mode == WorkspaceMode.CHATS &&
                    (sideBySide || !drawerOnCover) && !expandedSearch && !infoSheet && branchError == null }
                DisposableEffect(visibleSession) {
                    onVisibleSession(visibleSession)
                    onDispose { onVisibleSession(null) }
                }
                BackHandler(enabled = !sideBySide && !drawerOnCover) { drawerOnCover = true }
                if (infoSheet && openSessionId != null) {
                    ModalBottomSheet(onDismissRequest = { infoSheet = false },
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                        ContextPanel(openSession, now, onDisconnect = onDisconnect,
                            modifier = Modifier.fillMaxWidth().fillMaxHeight(.8f), onClose = { infoSheet = false })
                    }
                }
                Row(Modifier.fillMaxSize()) {
                    if ((sideBySide && showList) || (!sideBySide && drawerOnCover)) {
                        Column(if (sideBySide) Modifier.width(240.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                            SessionListScreen(state = sessions, now = now, selectedId = openSessionId,
                                onOpen = open, onRetry = onRetrySessions, onNewSession = onNewSession,
                                creating = creatingSession, createError = createError, offline = offline,
                                modifier = Modifier.weight(1f), showNewChatButton = !sideBySide,
                                unreadIds = unreadIds, completionRevision = completionRevision,
                                runLabels = runLabels,
                                onExpandedSearch = { expandedSearch = true })
                            WorkspaceModes(mode) { mode = it; drawerOnCover = false }
                        }
                        if (sideBySide) VerticalDivider()
                    }
                    if (sideBySide || !drawerOnCover) {
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            ConversationToolbar(
                                title = if (mode == WorkspaceMode.CHATS) openSession?.title?.takeIf { it.isNotBlank() }
                                    ?: "Conversation" else mode.title,
                                showList = sideBySide && showList, sideBySide = sideBySide,
                                onNavigate = { if (sideBySide) showList = !showList else drawerOnCover = true },
                                onNewSession = onNewSession, creatingSession = creatingSession,
                                hasSession = mode == WorkspaceMode.CHATS && openSessionId != null,
                                hasHistory = mode == WorkspaceMode.CHATS && history is UiState.Ready,
                                onPrompts = { navigation = ChatNavigation.PROMPTS },
                                onSearch = { navigation = ChatNavigation.SEARCH },
                                showContext = sideBySide && showContext,
                                onContext = { if (sideBySide) showContext = !showContext else infoSheet = true })
                            if (createError != null) NewChatFailure(createError)
                            when (mode) {
                                WorkspaceMode.SETTINGS -> SettingsWorkspace(preferences, onPreferencesChange,
                                    notificationsAvailable, onNotificationsChange)
                                WorkspaceMode.TOOLS -> ToolsWorkspace({ expandedSearch = true },
                                    if (openSessionId == null) null else ({ infoSheet = true }))
                                WorkspaceMode.PROJECTS, WorkspaceMode.TODAY -> Column(Modifier.padding(24.dp)) {
                                    Text("Coming soon", style = MaterialTheme.typography.titleLarge)
                                    Text(if (mode == WorkspaceMode.PROJECTS) "Collect chats, notes and attachments in projects."
                                        else "Reminders and follow-ups will appear here.")
                                }
                                WorkspaceMode.CHATS -> if (openSessionId == null) EmptyDetail() else {
                                    RunControls(runPresentation, onRetryRun, onApproval, onAbandonRun)
                                    screenState.SaveableStateProvider(openSessionId) {
                                        SessionDetailScreen(history = history, turn = turn, onSend = onSend,
                                            onStop = onStop, onRetry = onRetryHistory, prefill = prefill,
                                            draft = draft, onDraftChange = onDraftChange,
                                            collapseThinking = preferences.collapseThinking, reduceMotion = preferences.reduceMotion,
                                            pendingUserText = pendingUserText,
                                            sendBlocked = runPresentation?.busy == true,
                                            readEnabled = foreground && !expandedSearch && !infoSheet && branchError == null,
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
                        if (sideBySide && showContext && openSessionId != null && mode == WorkspaceMode.CHATS) {
                            VerticalDivider()
                            ContextPanel(openSession, now, onDisconnect = onDisconnect,
                                modifier = Modifier.width(200.dp).fillMaxHeight(), onClose = { showContext = false })
                        }
                    }
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
