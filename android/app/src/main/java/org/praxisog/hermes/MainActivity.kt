package org.praxisog.hermes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.HermesResult

class MainActivity : ComponentActivity() {
    /** Activity-scoped; cancelled in [onDestroy] so no call outlives the screen. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var status by mutableStateOf("Enter a server URL and API key.")
    private var checking by mutableStateOf(false)
    private val runtime get() = BellpullRuntime.get(applicationContext)
    private val store get() = runtime.store
    private var connectionStore: SecureConnectionStore? = null
    private var sessionCache: SessionCache? = null
    private var messageReader: MessageReader? = null
    private val chatDrafts get() = runtime.drafts
    private lateinit var preferences: BellpullSettings
    private var restoredSessionId: String? = null
    private var foreground by mutableStateOf(false)
    private var windowCutouts by mutableStateOf(emptyList<androidx.compose.ui.geometry.Rect>())
    private var focusChatRevision by mutableStateOf(0)
    private val notificationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        setNotifications(granted)
    }

    /**
     * Set when the launch came from the widget. Held rather than acted on immediately,
     * because a session cannot be created until the client is known good — the store does not
     * exist until the capabilities check passes.
     */
    private var pendingNewChat = false

    /**
     * State, not locals captured in onCreate: [disconnect] has to be able to blank them.
     * Holding them as captured vals meant the connection screen still showed the old key
     * after the user asked the app to forget it.
     */
    private var initialUrl by mutableStateOf("")
    private var initialKey by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = BellpullSettings(this)
        configureWindowChrome(window, preferences.value.immersiveMode)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            windowCutouts = insets.displayCutout?.boundingRects.orEmpty().map {
                androidx.compose.ui.geometry.Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
            }
            insets // Descendants still need the IME and system-bar insets.
        }
        androidx.core.view.ViewCompat.requestApplyInsets(window.decorView)
        val reader = MessageReader(this)
        messageReader = reader
        val secureStore = runCatching { SecureConnectionStore(this) }.getOrElse {
            status = "Secure storage is unavailable on this device."
            null
        }
        connectionStore = secureStore
        sessionCache = runtime.cache
        runtime.restore()
        restoredSessionId = ReplyNotifications.session(intent) ?: savedInstanceState?.getString("bellpull_open_session") ?: sessionCache?.lastSession()
        if (ReplyNotifications.session(intent) != null) focusChatRevision++
        initialUrl = runCatching { secureStore?.loadUrl().orEmpty() }.getOrElse {
            status = "Saved connection settings could not be read."
            ""
        }
        initialKey = runCatching { secureStore?.loadKey().orEmpty() }.getOrElse {
            status = "Saved API key could not be read."
            ""
        }

        pendingNewChat = WidgetIntents.isNewChat(intent)

        // Auto-connect when credentials are already stored. Without this the widget costs
        // three taps -- widget, Check connection, New chat -- and the accept criterion is a
        // fresh session in under two from cold start. It also means the app does not ask the
        // user to re-confirm a connection it already has on every launch.
        if (store == null && initialUrl.isNotBlank() && initialKey.isNotBlank()) {
            checkConnection(initialUrl, initialKey)
        }
        store?.let { current ->
            if (pendingNewChat) { pendingNewChat = false; current.newSession() }
            else restoredSessionId?.let(current::openSession)
            restoredSessionId = null
            loadInstalledApps(current)
        }

        setContent {
            DisposableEffect(preferences.value.immersiveMode) {
                configureWindowChrome(window, preferences.value.immersiveMode)
                onDispose { }
            }
            CompositionLocalProvider(LocalWindowCutouts provides windowCutouts) {
                val current = store
                DisposableEffect(current?.openSessionId) {
                    if (current != null) runCatching { sessionCache?.rememberSession(current.openSessionId) }
                    onDispose { reader.stop() }
                }
                if (current == null) {
                    ConnectionScreen(initialUrl, initialKey, status, checking, preferences.value, ::checkConnection)
                } else {
                    HermesApp(
                        sessions = current.sessions,
                        history = current.history,
                        turn = current.turn,
                        openSessionId = current.openSessionId,
                        openSession = current.openSession,
                        now = System.currentTimeMillis() / 1000.0,
                        onOpenSession = current::openSession,
                        onCloseSession = current::closeSession,
                        onSend = current::send,
                        onStop = current::stop,
                        onRetrySessions = current::loadSessions,
                        onRetryHistory = current::refreshHistory,
                        onNewSession = current::newSession,
                        creatingSession = current.creatingSession,
                        createError = current.createError,
                        offline = current.offline,
                        onDisconnect = ::disconnect,
                        query = current.searchQuery,
                        onQueryChange = current::setQuery,
                        apps = current.apps,
                        onLaunchApp = { app ->
                            if (!InstalledApps.launch(this, app)) {
                                // A launch can fail if the app was uninstalled since the list was
                                // read. Say so rather than appearing to do nothing.
                                status = "Could not open ${app.label}. It may have been uninstalled."
                            }
                        },
                        onAskHermes = current::askHermes,
                        prefill = current.pendingPrompt,
                        pendingUserText = current.pendingUserText,
                        unreadIds = current.readState.unreadIds,
                        completionRevision = current.completionRevision,
                        onReadThrough = current.readState::readThrough,
                        foreground = foreground,
                        runPresentation = current.runPresentation,
                        runLabels = current.runLabels,
                        onRetryRun = current::retryRun,
                        onAbandonRun = current::abandonRunAfterReview,
                        onApproval = current::respondToApproval,
                        notificationsAvailable = runtime.notificationEventsAvailable,
                        onNotificationsChange = ::requestNotifications,
                        focusChatRevision = focusChatRevision,
                        onVisibleSession = { runtime.visibleSessionId = it },
                        draft = current.draft,
                        onDraftChange = current::updateDraft,
                        preferences = preferences.value,
                        onPreferencesChange = preferences::update,
                        onReadMessage = { reader.read(it.id, it.content.orEmpty()) },
                        readingMessageId = reader.readingMessageId,
                        onBranchMessage = current::branchMessage,
                        branchingEnabled = current.supportsMessageBranching,
                        editingEnabled = current.supportsPromptEditing,
                        onForkEdit = current::forkAndEdit,
                        branchingSession = current.branchingSession,
                        branchError = current.branchError,
                        onDismissBranchError = current::dismissBranchError,
                    )
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::preferences.isInitialized) configureWindowChrome(window, preferences.value.immersiveMode)
    }

    private fun checkConnection(rawUrl: String, rawKey: String) {
        val baseUrl = HermesEndpoint.parse(rawUrl).getOrElse {
            status = it.message ?: "Enter a valid server URL."
            return
        }
        val key = rawKey.trim()
        if (key.isEmpty()) {
            status = "Enter the Hermes API key."
            return
        }
        val secureStore = connectionStore
        if (secureStore == null) {
            status = "Secure storage is unavailable on this device."
            return
        }
        val saved = runCatching { secureStore.save(baseUrl, key) }.getOrDefault(false)
        if (!saved) {
            status = "Could not save the API key securely."
            return
        }

        checking = true
        status = "Checking Hermes…"
        scope.launch {
            val client = HermesClient(baseUrl, { key }, memoryScopeKey = MEMORY_SCOPE)
            val outcome = withContext(Dispatchers.IO) {
                // SecurityException is not an IOException, so core-hermes does not catch it: a
                // pure Kotlin module has no business knowing what Android permission denial
                // means. Handled here, where it does.
                runCatching { client.capabilities() }
            }
            checking = false
            outcome.fold(
                onSuccess = { result ->
                    when (result) {
                        is HermesResult.Ok ->
                            if (result.value.sessionChatStreaming) {
                                status = "Authenticated. Session streaming is available."
                                // Only now is the client known good, so only now is a store built.
                                runtime.connect(client, result.value).also { newStore ->
                                    newStore.loadSessions()
                                    restoredSessionId?.let { newStore.openSession(it) }
                                    restoredSessionId = null
                                    loadInstalledApps(newStore)
                                    if (pendingNewChat) {
                                        pendingNewChat = false
                                        newStore.newSession()
                                    }
                                }
                            } else {
                                status =
                                    "Authenticated, but this Hermes server does not report session streaming."
                            }
                        is HermesResult.Err -> status = result.error.toUserMessage()
                    }
                },
                onFailure = { thrown ->
                    status = if (thrown is SecurityException) {
                        "Network access was denied by Android. Check app permissions."
                    } else {
                        "The Hermes request failed. Check the server and network settings."
                    }
                },
            )
        }
    }

    /**
     * Forget the credentials and the cache, and return to the connection screen.
     *
     * Both are cleared: the cache holds session titles and previews, so leaving it behind
     * after a key is rotated would keep the previous conversations readable on the device.
     */
    private fun disconnect() {
        stopService(Intent(this, NotificationListenerService::class.java))
        preferences.update(preferences.value.copy(notifications = false))
        runtime.disconnect()
        restoredSessionId = null
        pendingNewChat = false
        runCatching { connectionStore?.clear() }
        runCatching { sessionCache?.clear() }
        initialUrl = ""
        initialKey = ""
        status = "Disconnected. Enter a server URL and API key."
    }

    /**
     * The widget may be tapped while the app is already running. `SINGLE_TOP` means this
     * Activity is reused rather than recreated, so the new-chat request arrives here instead
     * of through [onCreate].
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ReplyNotifications.session(intent)?.let { id ->
            focusChatRevision++
            if (store != null) store?.openSession(id) else restoredSessionId = id
            return
        }
        if (!WidgetIntents.isNewChat(intent)) return
        val current = store
        if (current != null) {
            current.newSession()
        } else {
            // Not connected yet: remember it, and the connection path will pick it up.
            pendingNewChat = true
        }
    }

    /**
     * Reads the launchable apps off the main thread. A PackageManager enumeration is not free
     * on a device with many apps installed, and it is never urgent — the search field works
     * for sessions and for Hermes before the list arrives.
     */
    private fun loadInstalledApps(target: HermesStore) {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { InstalledApps.load(this@MainActivity) }
            target.updateApps(loaded)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("bellpull_open_session", store?.openSessionId)

    }

    override fun onStop() {
        runCatching { sessionCache?.rememberSession(if (store != null) store?.openSessionId else restoredSessionId) }
        messageReader?.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        preferences = BellpullSettings(this)
        foreground = true
        store?.loadSessions()
        store?.refreshHistory()
        runtime.refreshCapabilities()
        if (preferences.value.notifications) setNotifications(true)
    }

    override fun onPause() {
        foreground = false
        super.onPause()
    }

    override fun onDestroy() {
        messageReader?.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun requestNotifications(enabled: Boolean) {
        if (enabled && android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else setNotifications(enabled)
    }

    private fun setNotifications(enabled: Boolean) {
        preferences.update(preferences.value.copy(notifications = enabled))
        val listener = Intent(this, NotificationListenerService::class.java)
        if (enabled) {
            runCatching { startForegroundService(listener) }.onFailure {
                preferences.update(preferences.value.copy(notifications = false))
                android.widget.Toast.makeText(this, "Could not start notifications. Try enabling them again.", android.widget.Toast.LENGTH_LONG).show()
            }
            runtime.catchUpNotifications()
        } else stopService(listener)
    }

    private companion object {
        /**
         * One stable value for `X-Hermes-Session-Key`, which scopes Hermes' long-term memory
         * independently of the transcript session id. Capped at 256 chars by the contract.
         */
        const val MEMORY_SCOPE = "hermes-android"
    }
}
