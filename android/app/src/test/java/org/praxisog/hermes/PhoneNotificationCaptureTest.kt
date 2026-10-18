package org.praxisog.hermes

import android.app.Notification
import android.app.Person
import android.content.Context
import android.os.Process
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class PhoneNotificationCaptureTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun sbn(notification: Notification, pkg: String = "source.app") = StatusBarNotification(pkg, pkg, 1, "tag",
        Process.myUid(), 0, 0, notification, UserHandle.getUserHandleForUid(Process.myUid()), 1000)
    @Test fun exposedBigTextIsBoundedAndGroupOngoingMetadataSurvives() {
        val notification = Notification.Builder(context, "fixture").setContentTitle("Workshop")
            .setContentText("short").setStyle(Notification.BigTextStyle().bigText("x".repeat(8000)))
            .setGroup("workshop").setGroupSummary(true).setOngoing(true).build()
        notification.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "x".repeat(8000))
        val row = notificationRecord(sbn(notification), context, 2000)!!
        assertEquals("Workshop", row.title); assertEquals(4000, row.text.length)
        assertTrue(row.groupSummary); assertTrue(row.ongoing); assertNotNull(row.groupKey)
        assertEquals(1000L, row.postedAt); assertEquals(2000L, row.firstObserved)
    }
    @Test fun messagingStyleKeepsRecentSendersAndUnavailableTextIsHonest() {
        val style = Notification.MessagingStyle(Person.Builder().setName("You").build()).setConversationTitle("Design team")
        repeat(20) { style.addMessage("Update $it", 1000L + it, Person.Builder().setName("Alice").build()) }
        val notification = Notification.Builder(context, "fixture").setStyle(style).build()
        val row = notificationRecord(sbn(notification), context)!!
        assertEquals("Design team", row.title)
        assertTrue(row.text.endsWith("Alice: Update 19")); assertFalse(row.text.contains("Alice: Update 0\n"))
        val empty = notificationRecord(sbn(Notification.Builder(context, "fixture").build()), context)!!
        assertTrue(empty.textUnavailable)
        assertNull(notificationRecord(sbn(notification, context.packageName), context))
    }
    @Test @Config(sdk = [29]) fun androidTenFallsBackToExposedText() {
        val notification = Notification.Builder(context, "fixture").setContentTitle("Alice").setContentText("At noon").build()
        val row = notificationRecord(sbn(notification), context)!!
        assertEquals("Alice", row.title); assertEquals("At noon", row.text)
    }
}
