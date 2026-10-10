package org.praxisog.hermes

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.HermesResult

/** Coordinates explicit account changes; late probes cannot attach to another profile. */
internal class ServerProfiles(
    private val context: Context,
    private val scope: CoroutineScope,
    private val runtime: BellpullRuntime,
    private val onLeaving: () -> Unit,
    private val onConnected: (HermesStore) -> Unit,
) {
    private val secure = runCatching { SecureConnectionStore(context) }.getOrNull()
    var profiles by mutableStateOf(emptyList<ServerProfile>())
        private set
    var activeId by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf("Explore Bellpull, or add your own server.")
        private set
    var checking by mutableStateOf(false)
        private set
    var settingsRevision by mutableStateOf(0)
        private set
    private var revision = 0
    private var probe: HermesClient? = null
    private var job: Job? = null

    init {
        if (secure == null) {
            runtime.disconnect()
            status = "Secure storage is unavailable. You can still explore Bellpull."
        } else runCatching {
            reload()
            if (activeId == null) runtime.disconnect() else connect(activeId!!, openSettings = false)
        }.onFailure {
            runtime.disconnect()
            status = "Saved profiles could not be opened. You can still explore Bellpull."
        }
    }

    fun showSettings() { settingsRevision++ }
    private fun reload() { profiles = secure?.profiles().orEmpty(); activeId = secure?.activeId() }

    fun add(name: String, rawUrl: String, rawKey: String): Boolean {
        val url = HermesEndpoint.parse(rawUrl).getOrElse { status = it.message ?: "Enter a valid server URL."; return false }
        if (name.isBlank() || rawKey.isBlank()) { status = "Enter a profile name and API key."; return false }
        val storage = secure ?: run { status = "Secure storage is unavailable."; return false }
        val added = runCatching { storage.add(name, url, rawKey.trim()) }.getOrElse {
            status = "Could not save this profile securely."; return false
        }
        reload()
        connect(added.id)
        return true
    }

    fun connect(id: String, openSettings: Boolean = true) {
        if (openSettings) showSettings()
        val connection = runCatching { secure?.connection(id) }.getOrNull() ?: return
        if (!runCatching { secure?.activate(id) == true }.getOrDefault(false)) {
            status = "Could not select this profile securely."; return
        }
        cancelProbe()
        if (runtime.profileId != id) {
            onLeaving()
            context.stopService(Intent(context, NotificationListenerService::class.java))
        }
        reload()
        try {
            runtime.prepare(id)
            runtime.restore(connection)
        } catch (_: Exception) {
            runtime.disconnect()
            status = "Could not open this profile's secure local storage."; return
        }
        if (runtime.store != null) {
            status = "Using ${connection.profile.name}."
            onConnected(runtime.store!!)
            return
        }
        val token = revision
        val client = HermesClient(connection.profile.url, { connection.key }, memoryScopeKey = "hermes-android")
        probe = client
        checking = true
        status = "Connecting to ${connection.profile.name}…"
        job = scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { client.capabilities() }.getOrNull() }
            if (token != revision || activeId != id) { client.close(); return@launch }
            checking = false
            probe = null
            if (result is HermesResult.Ok && result.value.sessionChatStreaming) {
                status = "Using ${connection.profile.name}."
                onConnected(runtime.connect(client, result.value))
            } else {
                client.close()
                status = when (result) {
                    is HermesResult.Err -> result.error.toUserMessage()
                    is HermesResult.Ok -> "This server does not report Hermes session streaming."
                    else -> "Could not connect. Check the server, network and Android permissions."
                }
            }
        }
    }

    fun signOut() {
        if (!runCatching { secure?.activate(null) == true }.getOrDefault(false)) {
            status = "Could not save sign-out. Try again."; return
        }
        detach()
        reload()
        status = "Signed out. Saved profiles are available when you need them."
        showSettings()
    }

    fun remove(id: String) {
        if (activeId == id) {
            signOut()
            if (activeId != null) return
        }
        // Clear local account data before forgetting its index, so failures can be retried.
        val removed = runCatching {
            check(SessionCache(context, id).clearChecked())
            check(secure?.remove(id) == true)
        }.isSuccess
        reload()
        status = if (removed) "Profile, credentials and local account data removed."
            else "Could not finish removing this profile. Try again."
    }

    fun removeAll() {
        signOut()
        if (activeId != null) return
        val removed = runCatching {
            (profiles.map { it.id } + SecureConnectionStore.LEGACY).distinct().forEach {
                check(SessionCache(context, it).clearChecked())
            }
            check(secure?.clear() == true)
        }.isSuccess
        reload()
        status = if (removed) "All saved servers, credentials and local account data removed."
            else "Could not finish removing local account data. Try again."
    }

    fun rename(id: String, name: String) {
        if (name.isBlank()) return
        val renamed = runCatching { secure?.rename(id, name) == true }.getOrDefault(false)
        reload()
        status = if (renamed) "Profile renamed." else "Could not rename this profile."
    }

    private fun detach() {
        cancelProbe()
        onLeaving()
        context.stopService(Intent(context, NotificationListenerService::class.java))
        runtime.disconnect()
    }

    private fun cancelProbe() {
        revision++
        probe?.close(); probe = null
        job?.cancel(); job = null
        checking = false
    }
    fun close() { cancelProbe() }
}
