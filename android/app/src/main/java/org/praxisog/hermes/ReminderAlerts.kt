package org.praxisog.hermes

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import org.praxisog.hermes.model.*
import java.util.concurrent.Executors

/** One OS alarm per connection, independent of network, voice and Activity lifetime. */
internal class ReminderAlerts(private val context: Context, private val profileId: String) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private fun alarmIntent() = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).setAction(DUE)
            .setData(Uri.parse("bellpull://reminders/${Uri.encode(profileId)}")).putExtra(PROFILE, profileId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun exact() = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
    fun enabled(): Boolean = BellpullSettings(context).value.notifications && manager.areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    fun cancel() { alarms.cancel(alarmIntent()); manager.cancel("reminders:$profileId", 0) }
    fun schedule(state: DeliveryState, now: Long = System.currentTimeMillis()) {
        alarms.cancel(alarmIntent())
        if (!enabled()) return
        val next = state.reminders().filter { it.state == "pending" && DeliveryState.occurrence(it) !in state.displayed }.minOfOrNull { it.dueMs } ?: return
        val time = maxOf(next, now + 1000)
        if (exact()) {
            try { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, alarmIntent()); return }
            catch (_: SecurityException) { /* permission may have changed since the check */ }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, alarmIntent())
    }
    /** Called on IO. Store receipt after posting; stable tag + onlyAlertOnce makes a crash retry quiet. */
    fun deliver(cache: SessionCache, now: Long = System.currentTimeMillis()): DeliveryState = cache.updateDelivery { previous ->
        val state = previous.receivedReminders(now)
        val due = state.due(now)
        if (due.isEmpty() || !enabled()) return@updateDelivery state
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        val text = if (due.size == 1) due.first().title else "${due.size} reminders are due. Open Today to review."
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
            .setData(Uri.parse("bellpull://inbox/${Uri.encode(profileId)}"))
            .putExtra(PROFILE, profileId).putExtra(OPEN_INBOX, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify("reminders:$profileId", 0, Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(if (due.size == 1) "Reminder" else "Missed reminders")
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Bellpull").setContentText("Reminders are due").build()).build())
        state.copy(displayed = state.displayed + due.map { DeliveryState.occurrence(it) })
    }
    companion object {
        const val CHANNEL = "bellpull_reminders"
        const val DUE = "org.praxisog.hermes.REMINDERS_DUE"
        const val PROFILE = "bellpull_reminder_profile"
        const val OPEN_INBOX = "bellpull_open_inbox"
    }
}

/** Only local disk/alarm work here: no server requests or starting a foreground service at boot. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        executor.execute {
            try {
                val profile = SecureConnectionStore(context).activeId() ?: return@execute
                if (intent.action == ReminderAlerts.DUE && intent.getStringExtra(ReminderAlerts.PROFILE) != profile) return@execute
                val cache = SessionCache(context, profile)
                try {
                    val alerts = ReminderAlerts(context, profile)
                    alerts.schedule(alerts.deliver(cache))
                } finally { cache.close() }
            } catch (_: Exception) {
                // Keep durable records intact; next foreground sync exposes storage failures and retries.
            } finally { pending.finish() }
        }
    }
    companion object { private val executor = Executors.newSingleThreadExecutor() }
}
