package org.praxisog.hermes

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.*

/** Android binds this only after notification access is granted. It never executes notification actions. */
class PhoneContextListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository by lazy { PhoneNotificationRepository.get(this) }
    private var connected = false
    private val appNames = mutableMapOf<String, String>()
    private fun record(sbn: StatusBarNotification) = notificationRecord(sbn, this, appName = appNames[sbn.packageName])?.also {
        if (appNames.size < 256) appNames[sbn.packageName] = it.appName
    }
    override fun onCreate() {
        super.onCreate()
        if (packageName.endsWith(".fixture")) fixtureInstance = this
        scope.launch {
            repository.state.collect { state ->
                if (state.ready && !state.policy.capture && connected) { connected = false; requestUnbind() }
            }
        }
    }
    override fun onListenerConnected() {
        connected = true
        scope.launch {
            // Storage initialization is asynchronous; do not lose the initial active snapshot.
            repository.state.collect { state ->
                if (state.ready) {
                    if (!state.policy.capture) { connected = false; requestUnbind() }
                    else if (connected) runCatching { activeNotifications?.mapNotNull(::record).orEmpty() }
                        .onSuccess(repository::connected).onFailure { repository.disconnected() }
                    cancel()
                } else if (state.error != null) { connected = false; requestUnbind(); cancel() }
            }
        }
    }
    override fun onListenerDisconnected() {
        connected = false
        repository.disconnected()
        if (repository.state.value.policy.capture && repository.state.value.access)
            requestRebind(ComponentName(this, PhoneContextListenerService::class.java))
    }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        record(sbn)?.let(repository::posted)
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        sbn ?: return
        val description = when (reason) {
            REASON_CANCEL, REASON_CANCEL_ALL -> "dismissed"
            REASON_APP_CANCEL, REASON_APP_CANCEL_ALL -> "removed by source app"
            REASON_CLICK -> "opened"
            REASON_SNOOZED -> "snoozed"
            REASON_TIMEOUT -> "expired"
            else -> "removed (Android reason $reason)"
        }
        repository.removed(notificationSourceKey(sbn), description, System.currentTimeMillis())
    }
    override fun onDestroy() {
        if (fixtureInstance === this) fixtureInstance = null
        scope.cancel(); repository.disconnected(); super.onDestroy()
    }
    companion object {
        /** Only the disposable test application exposes a service handle to instrumentation. */
        @Volatile internal var fixtureInstance: PhoneContextListenerService? = null
            private set
    }
}

internal fun notificationSourceKey(sbn: StatusBarNotification): String = sbn.key

/** Read only Android's exposed textual extras; never claim custom views/redacted content is complete. */
internal fun notificationRecord(sbn: StatusBarNotification, context: Context,
    now: Long = System.currentTimeMillis(), appName: String? = null): PhoneNotificationRecord? = runCatching {
    if (sbn.packageName == context.packageName) return null
    // Disposable on-device tests can grant access without recording personal notifications.
    if (context.packageName.endsWith(".fixture") && sbn.packageName != "com.android.shell") return null
    val notification = sbn.notification
    val extras = notification.extras
    fun text(key: String, limit: Int = 4000) = extras.getCharSequence(key)?.toString()?.take(limit).orEmpty()
    val title = text(Notification.EXTRA_CONVERSATION_TITLE, 300).ifBlank { text(Notification.EXTRA_TITLE, 300) }
    val exposedMessages = if (android.os.Build.VERSION.SDK_INT >= 30)
        extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.let { Notification.MessagingStyle.Message.getMessagesFromBundleArray(it) }.orEmpty()
        else emptyList()
    val messages = exposedMessages.takeLast(12).map { message ->
            val sender = message.senderPerson?.name?.toString().orEmpty().take(100)
            (if (sender.isBlank()) "" else "$sender: ") + message.text.toString().take(1000)
        }
    val body = if (messages.isNotEmpty()) messages.joinToString("\n").takeLast(4000) else text(Notification.EXTRA_BIG_TEXT).ifBlank {
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.takeLast(12)?.joinToString("\n") { it.toString().take(1000) }?.takeLast(4000).orEmpty()
    }.ifBlank { text(Notification.EXTRA_TEXT) }
    val label = appName ?: runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(sbn.packageName, 0)).toString().take(100) }
        .getOrDefault(sbn.packageName)
    PhoneNotificationRecord("", notificationSourceKey(sbn), sbn.packageName, label, title, body, sbn.postTime,
        now, now, sbn.groupKey.takeIf { sbn.isGroup }, notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
        sbn.isOngoing)
}.getOrNull()

internal fun requestPhoneNotificationBinding(context: Context) {
    runCatching { NotificationListenerService.requestRebind(ComponentName(context, PhoneContextListenerService::class.java)) }
}

internal fun openPhoneNotificationAccess(context: Context): Boolean {
    val component = ComponentName(context, PhoneContextListenerService::class.java)
    val detail = if (android.os.Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(detail); true }.getOrElse {
        runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }
}
