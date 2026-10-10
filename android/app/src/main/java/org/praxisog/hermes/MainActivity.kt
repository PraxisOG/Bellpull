package org.praxisog.hermes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtime get() = BellpullRuntime.get(applicationContext)
    private val store get() = runtime.store
    private lateinit var profiles: ServerProfiles
    private lateinit var preferences: BellpullSettings
    private var messageReader: MessageReader? = null
    private var foreground by mutableStateOf(false)
    private var windowCutouts by mutableStateOf(emptyList<androidx.compose.ui.geometry.Rect>())
    private var focusChatRevision by mutableStateOf(0)
    private var pendingNewChat = false
    private var pendingNotification: Pair<String, String>? = null
    private var permissionProfile: String? = null
    private val notificationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (permissionProfile != null && permissionProfile == runtime.profileId) setNotifications(granted)
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
        val reader = MessageReader(this)
        messageReader = reader
        profiles = ServerProfiles(this, scope, runtime, onLeaving = {
            reader.stop()
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
                    var exampleId by rememberSaveable { mutableStateOf<String?>(null) }
                    var exampleDraft by remember { mutableStateOf(TextFieldValue()) }
                    var exampleQuery by remember { mutableStateOf("") }
                    DisposableEffect(current?.openSessionId, profiles.activeId) {
                        current?.let { runtime.cache?.rememberSession(it.openSessionId) }
                        onDispose { reader.stop() }
                    }
                    HermesApp(
                        sessions = current?.sessions ?: UiState.Ready(listOf(BellpullExample.session)),
                        history = current?.history ?: UiState.Ready(if (exampleId == null) emptyList() else BellpullExample.messages),
                        turn = current?.turn ?: TurnState.Idle,
                        openSessionId = current?.openSessionId ?: exampleId.takeIf { current == null },
                        openSession = current?.openSession ?: BellpullExample.session.takeIf { current == null && exampleId != null },
                        now = System.currentTimeMillis() / 1000.0,
                        onOpenSession = { if (current == null) exampleId = BellpullExample.ID else current.openSession(it) },
                        onCloseSession = { if (current == null) exampleId = null else current.closeSession() },
                        onSend = { current?.send(it) }, onStop = { current?.stop() },
                        onRetrySessions = { current?.loadSessions() }, onRetryHistory = { current?.refreshHistory() },
                        onNewSession = { if (current == null) { exampleId = null; exampleDraft = TextFieldValue() } else current.newSession() },
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
                        onReadThrough = { id, message -> current?.readState?.readThrough(id, message) },
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
                        serverProfiles = {
                            ServerProfilesPanel(profiles.profiles, profiles.activeId, profiles.status, profiles.checking,
                                profiles::add, { profiles.connect(it) }, profiles::signOut, profiles::remove,
                                profiles::removeAll, profiles::rename)
                        },
                    )
                }
            }
        }
    }

    private fun connected(current: HermesStore) {
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
        store?.loadSessions(); store?.refreshHistory(); runtime.refreshCapabilities()
        if (preferences.value.notifications && store != null) setNotifications(true)
    }
    override fun onPause() { foreground = false; super.onPause() }
    override fun onDestroy() {
        profiles.close()
        messageReader?.close()
        scope.cancel()
        super.onDestroy()
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
