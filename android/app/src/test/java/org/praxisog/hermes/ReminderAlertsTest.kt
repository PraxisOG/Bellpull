package org.praxisog.hermes

import android.app.Notification
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.praxisog.hermes.model.*

@RunWith(RobolectricTestRunner::class) @Config(sdk=[30])
class ReminderAlertsTest {
    @Test fun oneAlarmReplacesItselfAndOfflineDeliverySurvivesNewCacheInstance() {
        val context = RuntimeEnvironment.getApplication() as Context
        BellpullSettings(context).let { it.update(it.value.copy(notifications=true)) }
        val cache = SessionCache(context, "alarm-test", context.getSharedPreferences("alarm-test", Context.MODE_PRIVATE))
        cache.clear()
        val now = System.currentTimeMillis()
        val reminder = Reminder("a".repeat(32), "Offline reminder", now+60000, "pending", 1)
        val state = cache.updateDelivery { it.reconcile(PlanningSnapshot(1, listOf(reminder), observedAt=1.0), now) }
        val alerts = ReminderAlerts(context, "alarm-test")
        repeat(100) { alerts.schedule(state, now) }
        val alarmManager = shadowOf(context.getSystemService(AlarmManager::class.java))
        assertEquals(1, alarmManager.scheduledAlarms.size)
        cache.close()
        val restored = SessionCache(context, "alarm-test", context.getSharedPreferences("alarm-test", Context.MODE_PRIVATE))
        assertEquals(reminder, restored.readDelivery().reminders().single())
        val delivered = alerts.deliver(restored, now+60001)
        assertEquals(1, delivered.entries.size)
        assertTrue(delivered.due(now+60001).isEmpty())
        repeat(100) { alerts.deliver(restored, now+60001) }
        assertEquals(1, shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.size)
        alerts.schedule(delivered, now+60001)
        assertEquals(0, alarmManager.scheduledAlarms.size)
        restored.clear(); alerts.cancel()
    }
    @Test fun reschedulingThePostedReminderClearsItWhileAnEarlierDismissedReminderStaysDue() {
        val context = RuntimeEnvironment.getApplication() as Context
        BellpullSettings(context).let { it.update(it.value.copy(notifications = true)) }
        val cache = SessionCache(context, "post-test", context.getSharedPreferences("post-test", Context.MODE_PRIVATE))
        val manager = context.getSystemService(NotificationManager::class.java)
        val alerts = ReminderAlerts(context, "post-test")
        val tag = "reminders:post-test"
        val earlier = Reminder("a".repeat(32), "Same title", 1000, "pending", 1)
        val later = Reminder("b".repeat(32), "Same title", 2000, "pending", 1)
        fun sync(reminder: Reminder, now: Long) {
            cache.updateDelivery { it.reconcile(PlanningSnapshot(1, listOf(earlier, reminder), observedAt = 1.0), now) }
            alerts.deliver(cache, now)
        }
        try {
            sync(later, 1500)
            manager.cancel(tag, 0)
            sync(later, 2100)
            assertEquals(1, manager.activeNotifications.size)
            sync(later.copy(dueMs = 5000, revision = 2), 2200)
            assertTrue(manager.activeNotifications.isEmpty())
            sync(later.copy(dueMs = 5000, revision = 2), 5100)
            assertEquals(1, manager.activeNotifications.size)
        } finally { alerts.cancel(); cache.clear(); cache.close() }
    }

    @Test fun anActiveGroupAddsNewArrivalsAndShrinksWithoutRepostingAfterDismissal() {
        val context = RuntimeEnvironment.getApplication() as Context
        BellpullSettings(context).let { it.update(it.value.copy(notifications = true)) }
        val cache = SessionCache(context, "group-test", context.getSharedPreferences("group-test", Context.MODE_PRIVATE))
        val manager = context.getSystemService(NotificationManager::class.java)
        val alerts = ReminderAlerts(context, "group-test")
        val a = Reminder("a".repeat(32), "First", 1000, "pending", 1)
        val b = Reminder("b".repeat(32), "Second", 1000, "pending", 1)
        fun sync(vararg rows: Reminder) {
            cache.updateDelivery { it.reconcile(PlanningSnapshot(1, rows.toList(), observedAt = 1.0), 2000) }
            alerts.deliver(cache, 2000)
        }
        fun text() = manager.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TEXT)
        try {
            sync(a)
            assertEquals("First", text())
            sync(a, b)
            assertTrue(text().toString().startsWith("2 reminders"))
            sync(a.copy(state = "completed", revision = 2), b.copy(title = "Renamed", revision = 2))
            assertEquals("Renamed", text())
            // A pre-upgrade alert identifies its one item only by the visible text.
            val legacy = Notification.Builder(context, ReminderAlerts.CHANNEL)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle("Reminder").setContentText("Renamed").build()
            manager.notify("reminders:group-test", 0, legacy)
            sync(a, b.copy(dueMs = 8000, title = "Postponed", revision = 3))
            assertTrue(manager.activeNotifications.isEmpty())
            sync(a, b)
            assertEquals("Second", text())
            manager.cancel("reminders:group-test", 0)
            sync(a, b)
            assertTrue(manager.activeNotifications.isEmpty())
        } finally { alerts.cancel(); cache.clear(); cache.close() }
    }

    @Test fun notificationsDisabledRetainsUndeliveredReminderWithoutAlarm() {
        val context = RuntimeEnvironment.getApplication() as Context
        BellpullSettings(context).let { it.update(it.value.copy(notifications=false)) }
        val cache = SessionCache(context, "disabled-test", context.getSharedPreferences("disabled-test", Context.MODE_PRIVATE)); cache.clear()
        cache.updateDelivery { it.reconcile(PlanningSnapshot(1, listOf(Reminder("b".repeat(32), "Saved", 1000, "pending", 1)), observedAt=1.0), 2000) }
        val alerts = ReminderAlerts(context, "disabled-test")
        val value = alerts.deliver(cache, 2000)
        assertEquals(1, value.entries.size)
        assertTrue(value.displayed.isEmpty())
        alerts.schedule(value, 2000)
        assertEquals(0, shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
        cache.clear()
    }
}
