package org.praxisog.hermes

import android.content.ComponentName
import android.content.Context
import android.app.NotificationManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Device-wide history; sharing permission is separate for each server profile. All disk work is on IO. */
internal class PhoneNotificationRepository internal constructor(
    private val storageFactory: () -> PhoneNotificationStorage,
    private val accessGranted: () -> Boolean,
    private val ownPackage: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<() -> Unit>(256)
    private val dropped = AtomicBoolean(false)
    private var storage: PhoneNotificationStorage? = null
    private val mutable = MutableStateFlow(PhoneNotificationSnapshot())
    val state: StateFlow<PhoneNotificationSnapshot> = mutable
    private val worker: Job
    init {
        worker = scope.launch {
            runCatching {
                val db = storageFactory().also { storage = it }
                val policy = db.policy()
                mutable.value = PhoneNotificationSnapshot(ready = true, policy = policy, records = db.read(clock(), policy))
                auditAccess()
            }.onFailure { failStorage() }
            for (command in commands) runCatching { command() }.onFailure { failStorage() }
        }
        scope.launch { while (isActive) { delay(15 * 60_000); control { refreshNow() } } }
    }
    private fun failStorage() {
        mutable.value = mutable.value.copy(ready = false, error = "Notification storage is unavailable. Capture and sharing are paused; free some space and reopen Bellpull.")
    }
    private fun control(block: () -> Unit) { if (!commands.trySend(block).isSuccess) scope.launch { commands.send(block) } }
    private fun event(block: () -> Unit) { if (!commands.trySend(block).isSuccess) dropped.set(true) }
    private fun publish(records: List<PhoneNotificationRecord> = mutable.value.records) {
        mutable.value = mutable.value.copy(records = retainedNotifications(records, mutable.value.policy, clock()), gap = mutable.value.gap || dropped.getAndSet(false))
    }
    private fun setPolicy(value: PhoneNotificationPolicy) {
        checkNotNull(storage).policy(value)
        mutable.value = mutable.value.copy(policy = value)
    }
    private fun auditAccess() {
        val granted = accessGranted()
        val policy = mutable.value.policy
        if (!granted && policy.accessWasGranted) {
            setPolicy(policy.copy(capture = false, accessWasGranted = false, sharingProfiles = emptySet(), serverSharingProfiles = emptySet()))
        } else if (granted && !policy.accessWasGranted) setPolicy(policy.copy(accessWasGranted = true))
        mutable.value = mutable.value.copy(access = granted, connected = mutable.value.connected && granted)
    }
    private fun refreshNow() {
        if (!mutable.value.ready) return
        auditAccess()
        storage?.prune(clock(), mutable.value.policy)
        publish()
    }
    fun refresh() = control { refreshNow() }
    fun capture(enabled: Boolean) = control {
        if (!mutable.value.ready) return@control
        auditAccess()
        setPolicy(mutable.value.policy.copy(capture = enabled,
            sharingProfiles = if (enabled) mutable.value.policy.sharingProfiles else emptySet(),
            serverSharingProfiles = if (enabled) mutable.value.policy.serverSharingProfiles else emptySet()))
        if (!enabled) mutable.value = mutable.value.copy(connected = false)
    }
    fun share(profile: String, enabled: Boolean) = control {
        if (!mutable.value.ready) return@control
        auditAccess()
        val policy = mutable.value.policy
        if (enabled && (!policy.capture || !mutable.value.access)) return@control
        setPolicy(policy.copy(sharingProfiles = if (enabled) policy.sharingProfiles + profile else policy.sharingProfiles - profile))
    }
    fun shareServer(profile: String, enabled: Boolean) = control {
        if (!mutable.value.ready) return@control
        auditAccess()
        val policy = mutable.value.policy
        if (enabled && (!policy.capture || !mutable.value.access)) return@control
        setPolicy(policy.copy(serverSharingProfiles = if (enabled) policy.serverSharingProfiles + profile else policy.serverSharingProfiles - profile,
            sharingProfiles = if (enabled) policy.sharingProfiles - profile else policy.sharingProfiles))
    }
    fun retention(days: Int) = control {
        if (!mutable.value.ready) return@control
        setPolicy(mutable.value.policy.copy(retentionDays = days))
        refreshNow()
    }
    fun shareOngoing(enabled: Boolean) = control {
        if (mutable.value.ready) setPolicy(mutable.value.policy.copy(shareOngoing = enabled))
    }
    fun exclude(packageName: String, excluded: Boolean) = control {
        if (!mutable.value.ready || !packageName.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))) return@control
        val policy = mutable.value.policy
        setPolicy(policy.copy(excludedPackages = if (excluded) policy.excludedPackages + packageName else policy.excludedPackages - packageName))
        if (excluded) storage?.remove(mutable.value.records.filter { it.packageName == packageName }.map { it.id })
        publish()
    }
    fun clear() = control {
        if (!mutable.value.ready) return@control
        // Persist the cutoff first, so reconnect/queued events cannot restore a cleared notification.
        setPolicy(mutable.value.policy.copy(clearedBefore = clock()))
        storage?.clear()
        mutable.value = mutable.value.copy(records = emptyList(), gap = false)
    }
    fun connected(active: List<PhoneNotificationRecord>) = control {
        if (!mutable.value.ready) return@control
        auditAccess()
        if (!mutable.value.access || !mutable.value.policy.capture) return@control
        mutable.value = mutable.value.copy(connected = true)
        val keys = active.map { it.sourceKey }.toSet()
        val now = clock()
        val reconciled = mutable.value.records.map {
            if (it.removedAt == null && it.sourceKey !in keys) it.copy(removedAt = now, removalReason = "no longer present after reconnect").also { row -> storage?.put(row) } else it
        }
        publish(reconciled)
        active.forEach(::postedNow)
    }
    fun disconnected() = control {
        mutable.value = mutable.value.copy(connected = false, gap = mutable.value.gap || mutable.value.policy.capture)
        if (mutable.value.ready) auditAccess()
    }
    fun posted(record: PhoneNotificationRecord) = event { postedNow(record) }
    private fun postedNow(record: PhoneNotificationRecord) {
        val snapshot = mutable.value
        if (!snapshot.ready || !snapshot.policy.capture || !accessGranted() || record.packageName == ownPackage ||
            record.packageName in snapshot.policy.excludedPackages || record.postedAt <= snapshot.policy.clearedBefore ||
            record.postedAt <= clock() - snapshot.policy.retentionDays * NOTIFICATION_DAY_MS) return
        val old = snapshot.records.firstOrNull { it.sourceKey == record.sourceKey && it.removedAt == null }
        val next = old?.let { mergeNotification(it, record) } ?: record.copy(id = UUID.randomUUID().toString())
        if (old == next) return
        storage?.put(next)
        val records = listOf(next) + snapshot.records.filterNot { it.id == next.id }
        val retained = retainedNotifications(records, snapshot.policy, clock())
        storage?.remove((records.map { it.id }.toSet() - retained.map { it.id }.toSet()))
        publish(retained)
    }
    fun removed(key: String, reason: String, at: Long) = event {
        if (!mutable.value.ready || !mutable.value.policy.capture || !accessGranted()) return@event
        val next = mutable.value.records.map { row ->
            if (row.sourceKey == key && row.removedAt == null) row.copy(removedAt = at, removalReason = reason).also { storage?.put(it) } else row
        }
        publish(next)
    }
    /** No disk or network on send. Permission is rechecked even if Settings was not reopened. */
    fun contextFor(profile: String, query: String): String? {
        val snapshot = state.value
        if (!snapshot.ready || !snapshot.policy.capture || profile !in snapshot.policy.sharingProfiles || !accessGranted()) return null
        return notificationContextText(relevantNotifications(snapshot.records, snapshot.policy, query, clock()), snapshot.connected)
    }
    suspend fun awaitIdle() { val done = CompletableDeferred<Unit>(); commands.send { done.complete(Unit) }; done.await() }
    suspend fun close() { commands.close(); worker.join(); scope.cancel(); storage?.close() }
    companion object {
        @Volatile private var instance: PhoneNotificationRepository? = null
        fun get(context: Context): PhoneNotificationRepository = instance ?: synchronized(this) {
            val app = context.applicationContext
            instance ?: PhoneNotificationRepository({ PhoneNotificationStorage(app) }, {
                app.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(app, PhoneContextListenerService::class.java))
            }, app.packageName).also { instance = it }
        }
    }
}
