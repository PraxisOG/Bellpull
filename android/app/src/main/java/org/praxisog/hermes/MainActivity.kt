package org.praxisog.hermes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
    private var store by mutableStateOf<HermesStore?>(null)
    private var connectionStore: SecureConnectionStore? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val secureStore = runCatching { SecureConnectionStore(this) }.getOrElse {
            status = "Secure storage is unavailable on this device."
            null
        }
        connectionStore = secureStore
        val initialUrl = runCatching { secureStore?.loadUrl().orEmpty() }.getOrElse {
            status = "Saved connection settings could not be read."
            ""
        }
        val initialKey = runCatching { secureStore?.loadKey().orEmpty() }.getOrElse {
            status = "Saved API key could not be read."
            ""
        }

        setContent {
            val current = store
            if (current == null) {
                ConnectionScreen(initialUrl, initialKey, status, checking, ::checkConnection)
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
                )
            }
        }
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
                                store = HermesStore(scope, client).also { newStore ->
                                    newStore.loadSessions()
                                    loadInstalledApps(newStore)
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

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        /**
         * One stable value for `X-Hermes-Session-Key`, which scopes Hermes' long-term memory
         * independently of the transcript session id. Capped at 256 chars by the contract.
         */
        const val MEMORY_SCOPE = "hermes-android"
    }
}
