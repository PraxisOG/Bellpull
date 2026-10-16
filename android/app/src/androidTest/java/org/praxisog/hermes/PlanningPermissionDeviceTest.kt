package org.praxisog.hermes

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.HermesResult

/** Run separately after shell revokes READ/WRITE_CALENDAR from the fixture package. */
@RunWith(AndroidJUnit4::class)
class PlanningPermissionDeviceTest {
    @Test fun realPermissionDenialAndGrantLeaveSharingAndWritesOptIn() = DeviceFixtureUi.withWindows {
        val ui = DeviceFixtureUi
        val app = ApplicationProvider.getApplicationContext<Context>()
        assertFalse("Revoke fixture calendar permissions before this test", PhoneCalendar(app).canRead())
        val runtime = BellpullRuntime.get(app)
        ui.main { runtime.disconnect() }
        val secure = SecureConnectionStore(app); secure.clear()
        val profile = secure.add("Calendar permission fixture", "http://127.0.0.1:18649", "bellpull-planning-device-fixture")
        secure.activate(profile.id)
        val client = HermesClient(profile.url, { "bellpull-planning-device-fixture" })
        val caps = (client.capabilities() as HermesResult.Ok).value
        val cache = SessionCache(app, profile.id)
        try { cache.writeCapabilities(caps.raw.toString()) } finally { cache.close() }
        fun permissionButton(id: String): AccessibilityNodeInfo? = ui.find {
            it.viewIdResourceName?.endsWith("/" + id) == true && it.isVisibleToUser
        }
        fun clickApp(label: String) {
            ui.await("Missing permission settings control: $label") { ui.text(label)?.isVisibleToUser == true }
            var target = checkNotNull(ui.text(label))
            while (!target.isClickable && target.parent != null) target = target.parent
            assertTrue("Permission settings click failed: $label", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                ui.awaitMain("Fixture did not connect") { runtime.planning != null }
                clickApp("Today"); clickApp("Calendar"); clickApp("Phone calendar access")
                clickApp("Allow phone calendar reading")
                ui.await("Android permission denial button missing") { permissionButton("permission_deny_button") != null }
                assertTrue(permissionButton("permission_deny_button")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                ui.await("Denied permission dialog did not close") { permissionButton("permission_allow_button") == null }
                ui.awaitMain("Denied calendar access was not retained") { runtime.planning?.canRead() == false && runtime.planning?.loading == false }
                clickApp("Allow phone calendar reading")
                ui.await("Android permission allow button missing or disabled") { permissionButton("permission_allow_button")?.isEnabled == true }
                ui.screenshot("planning-permission-allow")
                ui.tap(permissionButton("permission_allow_button")!!)
                ui.await("Allowed permission dialog did not close") { permissionButton("permission_allow_button") == null }
                ui.awaitMain("Granted calendar read access was not recognized") { runtime.planning?.canRead() == true && runtime.planning?.loading == false }
                if (!runtime.planning!!.canWrite()) {
                    clickApp("Allow calendar write permission")
                    ui.await("Write grant did not complete") { runtime.planning!!.canWrite() || permissionButton("permission_allow_button") != null }
                    permissionButton("permission_allow_button")?.let { assertTrue(it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
                    ui.awaitMain("Granted calendar write access was not recognized") { runtime.planning?.canWrite() == true }
                }
                ui.main {
                    assertFalse(runtime.planning!!.policy.writes)
                    assertFalse(runtime.planning!!.policy.sharing)
                    assertTrue(runtime.planning!!.policy.selected.isEmpty())
                    assertNull(runtime.planning!!.policy.destination)
                    assertTrue(runtime.planning!!.calendar.events.isEmpty())
                }
                checkNotNull(app.contentResolver.query(android.provider.CalendarContract.Calendars.CONTENT_URI,
                    arrayOf("_id"), "account_type=? AND account_name LIKE ?",
                    arrayOf(android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL, "bellpull-device-%"), null)).use { calendars ->
                    assertEquals("A disposable calendar survived cleanup", 0, calendars.count)
                }
                // No calendar is selected and no event write is attempted by granting permission.
            }
        } finally { ui.main { runtime.disconnect() }; client.close() }
    }
}
