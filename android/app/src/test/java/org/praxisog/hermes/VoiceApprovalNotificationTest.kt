package org.praxisog.hermes

import android.app.Notification
import android.app.NotificationManager
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.core.HermesClient
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class VoiceApprovalNotificationTest {
    @Test fun activeVoiceQueuesSilentAttentionEvenForTheVisibleChat() {
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = HermesClient("https://fixture.invalid", { "fixture-key" })
        val store = HermesStore(scope, client)
        val settings = BellpullSettings(context)
        settings.update(settings.value.copy(notifications = true))
        val manager = context.getSystemService(NotificationManager::class.java)
        var voiceActive = true
        val notifications = ReplyNotifications(context, scope, null, store, "fixture", { voiceActive }, { true })
        try {
            notifications.attention("one", "approval:one")
            val notification = shadowOf(manager).allNotifications.single()
            assertEquals(ReplyNotifications.VOICE_ATTENTION, notification.channelId)
            assertEquals(Notification.GROUP_ALERT_SUMMARY, notification.groupAlertBehavior)
            val channel = manager.getNotificationChannel(notification.channelId)
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
            assertNull(channel.sound); assertFalse(channel.shouldVibrate())
            manager.cancelAll(); voiceActive = false
            notifications.attention("one", "approval:two")
            assertTrue(shadowOf(manager).allNotifications.isEmpty())
        } finally { scope.cancel(); client.close() }
    }
}
