package org.praxisog.hermes

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
