package org.praxisog.hermes

import android.app.*
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*
import org.praxisog.hermes.core.HermesClient

/** User-visible ntfy listener, reusing the verified GrapheneOS special-use service approach. */
class NotificationListenerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var stream: HermesClient.Streaming? = null
    private var listening = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = BellpullSettings(this)
        if (intent?.action == STOP) {
            prefs.update(prefs.value.copy(notifications = false))
            stopSelf()
            return START_NOT_STICKY
        }
        if (!prefs.value.notifications) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Notification connection", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, NotificationListenerService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(2401, Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Bellpull notifications").setContentText("Listening for replies and requests needing attention")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Turn off", stop).build()).build())
        if (!listening) {
            listening = true
            val runtime = BellpullRuntime.get(applicationContext)
            runtime.restore()
            scope.launch {
                while (isActive) {
                    val client = runtime.client
                    if (client != null && runtime.notificationEventsAvailable) {
                        withContext(Dispatchers.IO) {
                            client.notificationEvents(onStart = { stream = it; if (!scope.isActive) it.cancel() }) { runId, sessionId ->
                                scope.launch { if (runtime.client === client) runtime.wake(runId, sessionId) }
                            }
                        }
                    }
                    delay(if (runtime.notificationEventsAvailable) 5_000 else 60_000)
                }
            }
            scope.launch {
                while (isActive) { runtime.store?.loadSessions(); runtime.catchUpNotifications(); delay(60_000) }
            }
        }
        return START_STICKY
    }
    override fun onDestroy() {
        stream?.cancel()
        scope.cancel()
        super.onDestroy()
    }
    companion object {
        const val STOP = "org.praxisog.hermes.STOP_NOTIFICATIONS"
        private const val CHANNEL = "bellpull_connection"
    }
}
