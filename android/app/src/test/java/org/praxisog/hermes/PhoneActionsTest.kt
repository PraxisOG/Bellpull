package org.praxisog.hermes

import android.content.Intent
import android.provider.AlarmClock
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneActionsTest {
    @Test fun timersAcceptSpokenAndNumericDurationsWithoutOverflow() {
        assertEquals(PhoneAction.Timer(600), PhoneActions.parse("Set a timer for ten minutes."))
        assertEquals(PhoneAction.Timer(5400), PhoneActions.parse("timer for one hour and thirty minutes"))
        assertEquals(PhoneAction.Timer(1260), PhoneActions.parse("start a timer for twenty-one minutes"))
        for (text in listOf("timer for 0 seconds", "timer for -5 minutes", "timer for 999999999999999999999 minutes", "timer for 25 hours", "timer for five elephants"))
            assertTrue(text, PhoneActions.parse(text) is PhoneAction.Clarify)
    }
    @Test fun alarmsRequireUnambiguousTimeAndRejectUnimplementedRecurrence() {
        assertEquals(PhoneAction.Alarm(7, 30), PhoneActions.parse("set an alarm for 7:30 a.m."))
        assertEquals(PhoneAction.Alarm(0, 0), PhoneActions.parse("alarm for 12 am"))
        assertEquals(PhoneAction.Alarm(12, 0), PhoneActions.parse("alarm for 12 pm"))
        assertEquals(PhoneAction.Alarm(19, 30), PhoneActions.parse("alarm at 19:30"))
        for (text in listOf("alarm for 7", "alarm for 24:00", "alarm for 3:75 pm", "alarm for 18 pm", "alarm for tomorrow at 7 am", "alarm for 7 am every Monday"))
            assertTrue(text, PhoneActions.parse(text) is PhoneAction.Clarify)
    }
    @Test fun clockIntentsCarryExactValuesAndShowTheClockUi() {
        val timer = PhoneActions.intent(PhoneAction.Timer(90))!!
        assertEquals(AlarmClock.ACTION_SET_TIMER, timer.action)
        assertEquals(90, timer.getIntExtra(AlarmClock.EXTRA_LENGTH, 0))
        assertFalse(timer.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true))
        val alarm = PhoneActions.intent(PhoneAction.Alarm(19, 30))!!
        assertEquals(19, alarm.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        assertEquals(30, alarm.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
        assertFalse(alarm.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true))
    }
    @Test fun websitesCannotOpenArbitrarySchemesOrEmbeddedCredentials() {
        assertEquals(PhoneAction.Website("https://example.com"), PhoneActions.parse("open example.com"))
        for (text in listOf("open javascript:alert(1)", "open file:///etc/passwd", "open https://user:password@example.com", "open https://", "open https://example.com:99999"))
            assertTrue(text, PhoneActions.parse(text) is PhoneAction.Clarify)
        assertEquals(PhoneAction.App("Maps"), PhoneActions.parse("Open Maps."))
        assertEquals(PhoneAction.App("Dr. Panda"), PhoneActions.parse("launch Dr. Panda"))
        assertEquals(PhoneAction.App("YouTube Music"), PhoneActions.parse("open YouTube Music"))
    }
    @Test fun messagesAndCallsAreHandoffsAndDoNotSendOrDialAutomatically() {
        val dial = PhoneActions.intent(PhoneActions.parse("call +1 (555) 123-4567"))!!
        assertEquals(Intent.ACTION_DIAL, dial.action)
        assertEquals("tel", dial.data!!.scheme)
        assertEquals("+15551234567", dial.data!!.schemeSpecificPart)
        val message = PhoneActions.intent(PhoneActions.parse("text 5551234567: Running late"))!!
        assertEquals(Intent.ACTION_SENDTO, message.action)
        assertEquals("Running late", message.getStringExtra("sms_body"))
        assertTrue(PhoneActions.parse("call Mom") is PhoneAction.Clarify)
        assertTrue(PhoneActions.parse("dial *123#") is PhoneAction.Clarify)
    }
    @Test fun destinationsAndSharingKeepUserContentAsData() {
        val maps = PhoneActions.intent(PhoneActions.parse("navigate to A&B #2"))!!
        assertEquals("geo:0,0?q=A%26B%20%232", maps.data.toString())
        assertEquals(PhoneAction.Share("Are you coming?"), PhoneActions.parse("share Are you coming?"))
        assertEquals(PhoneAction.Message("5551234567", "Running late!"), PhoneActions.parse("text 5551234567: Running late!"))
        assertEquals(Intent.ACTION_CHOOSER, PhoneActions.intent(PhoneActions.parse("share hello world"))!!.action)
        assertEquals(PhoneAction.Conversation, PhoneActions.parse("How does a timer work?"))
    }
    @Test fun manifestAdvertisesAssistantWithoutRequestingCallOrSmsPermission() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val results = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_ASSIST).setPackage(context.packageName), 0)
        assertTrue(results.any { it.activityInfo.name == MainActivity::class.java.name })
        val permissions = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue("com.android.alarm.permission.SET_ALARM" in permissions)
        assertFalse("android.permission.CALL_PHONE" in permissions)
        assertFalse("android.permission.SEND_SMS" in permissions)
    }
}
