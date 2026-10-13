package org.praxisog.hermes

import android.Manifest
import android.content.*
import android.os.Bundle
import android.provider.CalendarContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import java.net.HttpURLConnection
import java.net.URI
import java.time.*
import java.util.UUID

/** Only the fixture package, disposable loopback host and a plugin-owned LOCAL calendar. */
@RunWith(AndroidJUnit4::class)
class PlanningDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val ui = DeviceFixtureUi
    private val origin = "http://127.0.0.1:18649"
    private val key = "bellpull-planning-device-fixture"
    private fun id() = UUID.randomUUID().toString().replace("-", "")
    private fun control(action: String? = null, body: JsonObject? = null, device: String? = null): JsonObject {
        val request = URI(origin + "/fixture/planning" + (action?.let { "/$it" } ?: "") + (device?.let { "?device=$it" } ?: "")).toURL().openConnection() as HttpURLConnection
        request.connectTimeout = 5000; request.readTimeout = 5000
        request.setRequestProperty("Authorization", "Bearer $key")
        if (action != null) {
            request.requestMethod = "POST"; request.doOutput = true
            request.setRequestProperty("Content-Type", "application/json")
            request.outputStream.use { it.write((body?.toString() ?: "{}").toByteArray()) }
        }
        return try { assertEquals(200, request.responseCode); request.inputStream.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject } }
        finally { request.disconnect() }
    }
    private fun field(index: Int, value: String) {
        var number = 0
        val editor = ui.find { it.isEditable && number++ == index }
        assertNotNull("Missing editor $index", editor)
        assertTrue(editor!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
    }
    private fun profile(): Pair<ServerProfile,HermesClient> {
        ui.guard()
        ui.main { BellpullRuntime.get(app).disconnect() }
        val secure = SecureConnectionStore(app); secure.clear()
        val profile = secure.add("Planning device fixture", origin, key); secure.activate(profile.id)
        val client = HermesClient(origin, { key })
        val caps = (client.capabilities() as HermesResult.Ok).value
        assertTrue(caps.features["bellpull_planning"] == true)
        val cache = SessionCache(app, profile.id)
        try { cache.writeCapabilities(caps.raw.toString()); cache.rememberSession("phone_planning") }
        finally { cache.close() }
        return profile to client
    }
    @Test fun remindersNativeRoutinesAndTodayNavigationPersistWithoutInference() = ui.withWindows {
        val (profile, client) = profile()
        val runtime = BellpullRuntime.get(app)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scene ->
                ui.awaitMain("Planning chat did not load") { runtime.store?.history is UiState.Ready }
                ui.main { runtime.store!!.updateDraft(TextFieldValue("Keep planning navigation draft")) }
                ui.await("Workspace navigation did not render") { ui.text("Today") != null || ui.find { it.contentDescription?.toString() == "Back to sessions" } != null }
                if (ui.text("Today") == null) ui.tap(ui.find { it.contentDescription?.toString() == "Back to sessions" }!!)
                ui.tapText("Today")
                ui.awaitMain("Planning snapshot did not load") { runtime.planning?.snapshot != null && runtime.planning?.loading == false }
                ui.tapText("Add reminder")
                ui.await("Reminder editor missing") { ui.find { it.isEditable } != null }
                ui.tap(ui.find { it.isEditable }!!)
                field(0, "Phone fixture reminder")
                field(1, OffsetDateTime.now().minusMinutes(5).withNano(0).toString())
                ui.tapText("Save")
                ui.awaitMain("Reminder creation failed") { runtime.planning?.snapshot?.reminders?.any { it.title == "Phone fixture reminder" } == true }
                ui.await("Overdue group missing") { ui.text("Needs attention") != null }
                ui.tapText("Snooze 1h")
                ui.awaitMain("Snooze changed completion incorrectly") { runtime.planning?.snapshot?.reminders?.any { it.title == "Phone fixture reminder" && it.state == "pending" && it.dueMs > System.currentTimeMillis() } == true }
                ui.tapText("Complete")
                ui.awaitMain("Reminder completion failed") { runtime.planning?.snapshot?.reminders?.any { it.title == "Phone fixture reminder" && it.state == "completed" } == true }
                ui.tapText("Reopen")
                ui.awaitMain("Reminder reopening failed") { runtime.planning?.snapshot?.reminders?.any { it.title == "Phone fixture reminder" && it.state == "pending" && it.revision == 4L } == true }
                ui.screenshot("planning-reminders")
                ui.tapText("Routines")
                ui.awaitMain("Native scheduler capability missing") { runtime.planning?.snapshot?.routinesAvailable == true }
                ui.tapText("Add Hermes routine")
                ui.await("Routine editor missing") { ui.find { it.isEditable } != null }
                field(0, "Phone fixture routine"); field(1, "every 7d"); field(2, "Fixture only; never execute this job.")
                ui.tapText("Save")
                ui.awaitMain("Native routine creation failed") { runtime.planning?.snapshot?.routines?.any { it.name == "Phone fixture routine" } == true }
                assertEquals(1, control()["routines"]!!.jsonArray.size)
                ui.tapText("Pause")
                ui.awaitMain("Native routine pause failed") { runtime.planning?.snapshot?.routines?.singleOrNull()?.enabled == false }
                ui.tapText("Resume")
                ui.awaitMain("Native routine resume failed") { runtime.planning?.snapshot?.routines?.singleOrNull()?.enabled == true }
                ui.screenshot("planning-routines")
                ui.tapText("Cancel routine")
                ui.awaitMain("Native routine cancellation failed") { runtime.planning?.snapshot?.routines?.isEmpty() == true }
                assertEquals(0, control()["routines"]!!.jsonArray.size)
                control("offline")
                ui.main { runtime.planning!!.refresh() }
                ui.awaitMain("Cached planning did not show disconnection") { runtime.planning?.disconnected == true && runtime.planning?.loading == false }
                scene.recreate()
                ui.awaitMain("Today navigation lost the chat draft") { runtime.store?.draft?.text == "Keep planning navigation draft" }
                control("online")
                ui.main { runtime.planning!!.refresh() }
                ui.awaitMain("Planning did not recover") { runtime.planning?.disconnected == false && runtime.planning?.loading == false }
            }
            ui.main { runtime.disconnect() }
            val cache = SessionCache(app, profile.id)
            try {
                assertTrue(cache.readPlanning()!!.reminders.any { it.title == "Phone fixture reminder" && it.state == "pending" })
                assertEquals("Keep planning navigation draft", cache.readDrafts()["phone_planning"]!!.text)
            } finally { cache.close() }
            assertEquals(0, control()["admissions"]!!.jsonPrimitive.int)
        } finally { control("online"); ui.main { runtime.disconnect() }; client.close() }
    }

    @Test fun realCalendarProviderCrudRecurrencePermissionsAndProposalRecovery() = ui.withWindows {
        val (profile, client) = profile()
        val read = Manifest.permission.READ_CALENDAR; val write = Manifest.permission.WRITE_CALENDAR
        ui.automation.grantRuntimePermission(app.packageName, read)
        ui.automation.grantRuntimePermission(app.packageName, write)
        val resolver = app.contentResolver
        val account = "bellpull-device-" + id()
        val calendarUri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter("account_name", account).appendQueryParameter("account_type", CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        val inserted = checkNotNull(resolver.insert(calendarUri, ContentValues().apply {
            put("account_name", account); put("account_type", CalendarContract.ACCOUNT_TYPE_LOCAL)
            put("name", account); put("calendar_displayName", "Bellpull disposable test calendar")
            put("calendar_color", 0xff59091e.toInt()); put("calendar_access_level", CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put("ownerAccount", account); put("visible", 1); put("sync_events", 1); put("calendar_timezone", ZoneId.systemDefault().id)
        }))
        val calendarId = ContentUris.parseId(inserted)
        val cache = SessionCache(app, profile.id)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var workspace: PlanningWorkspace
        var workspaceCreated = false
        val phone = PhoneCalendar(app)
        try {
            val source = phone.sources().single { it.id == calendarId }
            val policy = CalendarPolicy(setOf(source.key), writes = true, destination = source.key)
            val day = LocalDate.now(); val zone = ZoneId.systemDefault()
            val start = day.atTime(9,0).atZone(zone).toInstant().toEpochMilli()
            val draft = CalendarDraft("Bellpull fixture overlap A", start, start + 3600000)
            val operation = id()
            val one = phone.create(operation, policy, source, draft)
            assertEquals(one.id, phone.create(operation, policy, source, draft).id)
            val two = phone.create(id(), policy, source, draft.copy(title = "Bellpull fixture overlap B", start = start + 1800000, end = start + 5400000))
            phone.change(policy, source, two, draft.copy(title = "Bellpull fixture edited", start = two.start, end = two.end))
            assertThrows(IllegalStateException::class.java) { phone.change(policy, source, two, null) }
            val allDayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            phone.create(id(), policy, source, CalendarDraft("Bellpull fixture all day", allDayStart, allDayStart + 86400000, allDay = true))
            val recurring = phone.create(id(), policy, source, draft.copy(title = "Bellpull fixture daily", start = start + 7200000, end = start + 9000000, recurrence = "FREQ=DAILY;COUNT=3", alertMinutes = 10))
            assertTrue(recurring.recurring)
            assertThrows(IllegalStateException::class.java) { phone.change(policy, source, recurring, null) }
            val instances = phone.read(policy, day.atStartOfDay(zone).toInstant().toEpochMilli(), day.plusDays(4).atStartOfDay(zone).toInstant().toEpochMilli())
            assertEquals(3, instances.events.count { it.id == recurring.id })
            assertEquals(1, instances.events.count { it.id == one.id })
            assertEquals(2, calendarBlocks(day, zone, instances.events.filter { !it.recurring }.map { CalendarBlock(it.id.toString(), it.title, it.start, it.end, allDay = it.allDay) }).maxOf { it.columns })
            ui.main { workspace = PlanningWorkspace(scope, client, cache, app, true); workspaceCreated = true; workspace.savePolicy(policy); workspace.open(true) }
            ui.awaitMain("Real calendar read failed") { workspace.calendar.events.size >= 6 && !workspace.loading }
            // Sharing stays off until explicitly enabled; only the selected LOCAL fixture is sent.
            assertEquals(JsonNull, control(device = workspace.device)["calendar"])
            ui.main { workspace.savePolicy(policy.copy(sharing = true)) }
            ui.awaitMain("Busy-only sharing did not finish") { !workspace.loading && workspace.calendar.readAt > 0 && workspace.calendarProblem == null }
            val busy = control(device = workspace.device)["calendar"]!!.jsonObject
            assertTrue(busy["busy_only"]!!.jsonPrimitive.boolean)
            assertTrue(busy["events"]!!.jsonArray.all { "title" !in it.jsonObject && "fingerprint" !in it.jsonObject })
            ActivityScenario.launch(ComponentActivity::class.java).use { scene ->
                scene.onActivity { it.setContent { HermesTheme { Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().safeDrawingPadding()) { PlanningScreen(workspace, true) {} } } } } }
                ui.tapText("Calendar")
                ui.tapText("Week")
                val tomorrowLabel = day.plusDays(1).format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d"))
                ui.await("Week view did not render") { ui.text(tomorrowLabel) != null }
                ui.scroll(true)
                ui.screenshot("planning-calendar-week")
                ui.scroll(false)
                ui.tapText("Day")
                ui.await("Day view did not render") { ui.text(tomorrowLabel) == null }
                ui.scroll(true)
                ui.screenshot("planning-calendar-day")
                ui.scroll(false)
                ui.tapText("Add calendar event")
                ui.await("Calendar editor did not open") { ui.find { it.isEditable } != null }
                ui.tap(ui.find { it.isEditable }!!)
                field(0, "Bellpull fixture UI creation")
                ui.tapText("Save on phone")
                ui.awaitMain("Calendar editor did not save") { workspace.calendar.events.any { it.title == "Bellpull fixture UI creation" } && !workspace.busy }
            }
            ui.main { workspace.savePolicy(policy.copy(sharing = true, busyOnly = false)) }
            ui.awaitMain("Detailed sharing did not finish") { !workspace.loading && workspace.calendar.readAt > 0 && workspace.calendarProblem == null }
            val proposal = control("propose", buildJsonObject {
                put("action", "create_event"); put("operation_id", id()); put("device", workspace.device)
                put("title", "Bellpull fixture proposal"); put("start_ms", start + 14400000); put("end_ms", start + 18000000)
            })
            ui.main { workspace.refresh() }
            ui.awaitMain("Proposal did not arrive") { workspace.snapshot?.proposals?.any { it.id == proposal["id"]!!.jsonPrimitive.content } == true && !workspace.loading }
            val pending = workspace.snapshot!!.proposals.single { it.id == proposal["id"]!!.jsonPrimitive.content }
            control("drop-report")
            ui.main { workspace.applyProposal(pending) }
            ui.awaitMain("Proposal phone write failed") { !workspace.busy && cache.calendarReceipt(pending.id)?.startsWith("Created event") == true && !workspace.loading }
            val firstIds = phone.read(policy, start, start + 86400000).events.filter { it.title == pending.title }.map { it.id }
            assertEquals(1, firstIds.size)
            ui.main { workspace.applyProposal(pending) }
            ui.awaitMain("Proposal report retry did not finish") { !workspace.busy && !workspace.loading }
            assertEquals(firstIds, phone.read(policy, start, start + 86400000).events.filter { it.title == pending.title }.map { it.id })
            assertEquals(1, control()["dropped_reports"]!!.jsonPrimitive.int)
            control("offline")
            ui.main { workspace.savePolicy(policy) }
            ui.awaitMain("Offline sharing revocation not retained") { workspace.revokePending && !workspace.loading && workspace.calendarProblem?.contains("pending reconnection") == true }
            control("online")
            ui.main { workspace.refresh() }
            ui.awaitMain("Sharing revocation did not recover") { !workspace.revokePending && !workspace.loading && workspace.calendarProblem == null }
            assertEquals(JsonNull, control(device = workspace.device)["calendar"])
            assertThrows(IllegalStateException::class.java) { phone.change(policy.copy(writes = false), source, phone.event(one.id, source)!!, null) }
            phone.change(policy, source, phone.event(one.id, source)!!, null)
            assertNull(phone.event(one.id, source))
            assertEquals(0, control()["admissions"]!!.jsonPrimitive.int)
        } finally {
            control("online")
            ui.main { if (workspaceCreated) workspace.close(); scope.cancel(); cache.close() }
            // Exact calendar ID/account URI only; never touch another calendar's rows.
            assertEquals("Disposable calendar cleanup failed", 1, resolver.delete(calendarUri, "_id=?", arrayOf(calendarId.toString())))
            checkNotNull(resolver.query(calendarUri, arrayOf("_id"), "_id=?", arrayOf(calendarId.toString()), null)).use {
                assertEquals("Disposable calendar still exists", 0, it.count)
            }
            client.close()
        }
    }
}
