package org.praxisog.hermes

import android.content.Context
import android.net.*
import android.os.SystemClock
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import java.util.UUID

/** Profile-scoped delivery coordinator. One sync at a time; no inference calls. */
internal class DeliveryWorkspace(private val context: Context, private val profileId: String,
    private val scope: CoroutineScope, private val cache: SessionCache, private val client: HermesClient,
    var supported: Boolean, private val onSnapshot: (PlanningSnapshot) -> Unit = {}) {
    var state by mutableStateOf(DeliveryState()); private set
    var problem by mutableStateOf<String?>(null); private set
    var syncing by mutableStateOf(false); private set
    var ready by mutableStateOf(false); private set
    var alertStatus by mutableStateOf(""); private set
    private val alerts = ReminderAlerts(context, profileId)
    private val lock = Mutex()
    private var nextAttempt = 0L
    private var failures = 0
    private var closed = false
    private val network = context.getSystemService(ConnectivityManager::class.java)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { refresh() }
    }
    private var registered = false
    init {
        scope.launch {
            try {
                state = withContext(Dispatchers.IO) { cache.readDelivery() }
                ready = true
                refreshLocal()
                sync(true)
            } catch (error: Exception) { failed(error) }
        }
        registered = runCatching { network.registerDefaultNetworkCallback(callback); true }.getOrDefault(false)
    }
    fun refresh() { if (!closed) scope.launch { sync(true) } }
    fun tick() { if (!closed) scope.launch { sync(false) } }
    private fun failed(error: Exception) {
        if (error is CancellationException) throw error
        problem = error.message ?: "Inbox storage is unavailable."
    }
    suspend fun refreshLocal() {
        if (closed) return
        state = withContext(Dispatchers.IO) {
            val current = alerts.deliver(cache)
            alerts.schedule(current)
            current
        }
        alertStatus = when {
            !alerts.enabled() -> "Reminder notifications are off. Enable Bellpull notifications in Settings."
            !alerts.exact() -> "Saved locally · Android may delay alerts. Allow Alarms & reminders for precise timing."
            else -> "Saved reminders are scheduled on this phone, including offline."
        }
    }
    suspend fun sync(force: Boolean = false) {
        if (closed || !ready || !supported || (!force && SystemClock.elapsedRealtime() < nextAttempt) || !lock.tryLock()) return
        syncing = true
        try {
            refreshLocal()
            for (operation in state.operations.filter { it.status == "pending" }) {
                val response = withContext(Dispatchers.IO) {
                    client.planningAction("reminders", buildJsonObject {
                        put("operation_id", operation.operationId); put("action", operation.action)
                        put("id", operation.reminderId); put("revision", operation.revision)
                        operation.dueMs?.let { put("due_ms", it) }
                    })
                }
                when (response) {
                    is HermesResult.Ok -> update { value -> value.copy(operations = value.operations.map {
                        if (it.operationId == operation.operationId) it.copy(status = "acknowledged", problem = null) else it
                    }) }
                    is HermesResult.Err -> {
                        val error = response.error
                        val permanent = error is HermesError.BadRequest || error is HermesError.ServerError && error.status in setOf(400,404,409,422)
                        if (permanent) update { value -> value.copy(operations = value.operations.map {
                            if (it.operationId == operation.operationId) it.copy(status = "blocked", problem = error.toUserMessage()) else it
                        }) } else { problem = error.toUserMessage(); backoff(); return }
                    }
                }
            }
            when (val response = withContext(Dispatchers.IO) { client.planning() }) {
                is HermesResult.Ok -> {
                    update { previous ->
                        val now = System.currentTimeMillis()
                        response.value.routines.fold(previous.reconcile(response.value, now)) { value, routine ->
                            val old = previous.planning?.routines?.find { it.id == routine.id }
                            if (routine.last == null || old == null || (old.last == routine.last && old.lastStatus == routine.lastStatus)) value
                            else value.receive(InboxEntry("routine:${routine.id}:${routine.last}:${routine.lastStatus}", "routine",
                                routine.name ?: "Routine result", "${routine.lastStatus ?: "Finished"}\n${routine.lastError.orEmpty()}\n${routine.lastDeliveryError.orEmpty()}".trim(), now))
                        }
                    }
                    withContext(Dispatchers.IO) { cache.writePlanning(response.value) }
                    onSnapshot(response.value)
                    failures = 0; problem = null; nextAttempt = SystemClock.elapsedRealtime() + SYNC_INTERVAL_MS
                    refreshLocal()
                }
                is HermesResult.Err -> { problem = response.error.toUserMessage(); backoff() }
            }
        } catch (error: Exception) { failed(error); backoff() }
        finally { syncing = false; lock.unlock() }
    }
    private fun backoff() {
        failures = (failures + 1).coerceAtMost(4)
        nextAttempt = SystemClock.elapsedRealtime() + SYNC_INTERVAL_MS * (1L shl failures)
    }
    private suspend fun update(change: (DeliveryState) -> DeliveryState) {
        state = withContext(Dispatchers.IO) { cache.updateDelivery(change) }
    }
    fun action(reminder: Reminder, action: String, due: Long?) {
        if (!ready || closed) return
        scope.launch {
            try {
                lock.withLock {
                    update { it.enqueue(ReminderOperation(UUID.randomUUID().toString().replace("-", ""), reminder.id,
                        reminder.revision, action, due)) }
                    refreshLocal()
                }
                sync(true)
            } catch (error: Exception) { failed(error) }
        }
    }
    suspend fun archiveReply(id: String, message: Message): Boolean = try {
        lock.withLock { update { it.receive(InboxEntry("reply:$id:${message.id}", "reply", "Reply",
            message.content.orEmpty(), System.currentTimeMillis(), id)) } }
        true
    } catch (error: Exception) { failed(error); false }
    fun receive(entry: InboxEntry) {
        if (closed) return
        scope.launch { try { lock.withLock { update { it.receive(entry) } } } catch (error: Exception) { failed(error) } }
    }
    fun mark(id: String, dismiss: Boolean = false) {
        scope.launch { try { lock.withLock { update { value -> value.copy(entries = value.entries.map {
            if (it.id != id) it else if (dismiss) it.copy(dismissedAt = System.currentTimeMillis()) else it.copy(openedAt = System.currentTimeMillis())
        }) } } } catch (error: Exception) { failed(error) } }
    }
    fun discardBlocked(id: String) {
        scope.launch { try { lock.withLock { update { value -> value.copy(operations = value.operations.filterNot {
            it.operationId == id && it.status == "blocked"
        }) } }; sync(true) } catch (error: Exception) { failed(error) } }
    }
    fun close() {
        if (closed) return
        closed = true
        if (registered) network.unregisterNetworkCallback(callback)
        alerts.cancel()
    }
    companion object { const val SYNC_INTERVAL_MS = 60_000L }
}
