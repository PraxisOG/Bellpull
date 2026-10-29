package org.praxisog.hermes

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.model.*

class PhoneContextTest {
    private val now = 1_800_000_000_000L
    private val profile = "primary"
    private val policy = CalendarPolicy(selected = setOf("chosen"), sharing = true)
    private val config = PhoneContextConfiguration(calendarOnline = true)
    private fun note(id: String = "one", text: String = "Meet at the west gate") =
        PhoneNotificationRecord(id, id, "source.app", "Messages", "Rowan", text, now-100, now-100, now-100)
    private fun snapshot(vararg rows: PhoneNotificationRecord) = PhoneNotificationSnapshot(ready = true, access = true, connected = true,
        policy = PhoneNotificationPolicy(capture = true, serverSharingProfiles = setOf(profile)), records = rows.toList())
    private fun event(id: String = "one", key: String = "chosen") = PhoneCalendarEvent(id.hashCode().toLong(), 1, "Private appointment", now+60_000,
        now+3_600_000, false, "UTC", false, "fingerprint", key)
    private fun body(notes: PhoneNotificationSnapshot = snapshot(note()), calPolicy: CalendarPolicy = policy,
        calendar: PhoneCalendarSnapshot = PhoneCalendarSnapshot(listOf(event()), now), settings: PhoneContextConfiguration = config,
        read: Boolean = true, local: Boolean = false) = phoneContextBody(notes, calPolicy, calendar, settings, profile, read, now, local)

    @Test fun existingExcerptPermissionNeverUpgradesToAutomaticContext() {
        val old = snapshot(note()).let { it.copy(policy = it.policy.copy(serverSharingProfiles = emptySet(), sharingProfiles = setOf(profile))) }
        assertFalse(body(notes = old)["notifications_enabled"]!!.jsonPrimitive.boolean)
        assertTrue(body(notes = old)["notifications"]!!.jsonArray.isEmpty())
        assertFalse(body(settings = PhoneContextConfiguration())["calendar_enabled"]!!.jsonPrimitive.boolean)
        assertTrue(body(notes = snapshot(note()).copy(access = false))["notifications"]!!.jsonArray.isEmpty())
        assertTrue(body(read = false)["calendar"]!!.jsonArray.isEmpty())
    }
    @Test fun calendarRequiresBothGatesAndOnlySelectedUpcomingEventsLeavePhone() {
        val events = listOf(event(), event("unselected", "other"), event("past").copy(start = now-10000, end = now-1000),
            event("distant").copy(start = now+8*NOTIFICATION_DAY_MS, end = now+9*NOTIFICATION_DAY_MS))
        val data = body(calendar = PhoneCalendarSnapshot(events, now))
        assertEquals(1, data["calendar"]!!.jsonArray.size)
        assertEquals("Busy", data["calendar"]!!.jsonArray.single().jsonObject["title"]!!.jsonPrimitive.content)
        assertFalse(data.toString().contains("Private appointment"))
        assertTrue(body(calPolicy = policy.copy(sharing = false))["calendar"]!!.jsonArray.isEmpty())
        assertEquals("Private appointment", body(calPolicy = policy.copy(busyOnly = false))["calendar"]!!.jsonArray.single().jsonObject["title"]!!.jsonPrimitive.content)
    }
    @Test fun notificationBoundsExclusionsGroupingRemovalAndTruncationApplyBeforeUpload() {
        val child = note().copy(groupKey = "group", removedAt = now)
        val rows = listOf(child, child.copy(id = "duplicate"), note("summary").copy(groupKey = "group", groupSummary = true),
            note("excluded").copy(packageName = "blocked.app"), note("ongoing").copy(ongoing = true),
            note("old").copy(updatedAt = now-2*NOTIFICATION_DAY_MS), note("huge", "x".repeat(3000)))
        val state = snapshot(*rows.toTypedArray()).let { it.copy(policy = it.policy.copy(excludedPackages = setOf("blocked.app"))) }
        val data = body(notes = state)
        val uploaded = data["notifications"]!!.jsonArray
        assertEquals(setOf("one", "huge"), uploaded.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet())
        assertTrue(data["truncated"]!!.jsonPrimitive.boolean)
        assertEquals(now, uploaded.first { it.jsonObject["id"]!!.jsonPrimitive.content == "one" }.jsonObject["removed_at_ms"]!!.jsonPrimitive.long)
        assertTrue(uploaded.all { it.jsonObject["text"]!!.jsonPrimitive.content.length <= 2000 })
        assertNotEquals(data["policy"], body(notes = state.copy(policy = state.policy.copy(clearedBefore = now)))["policy"])
        val many = body(notes = snapshot(*(1..110).map { note("$it", "Message $it") }.toTypedArray()))
        assertEquals(100, many["notifications"]!!.jsonArray.size); assertTrue(many["truncated"]!!.jsonPrimitive.boolean)
    }
    @Test fun localGrantsAreIndependentAndNeverEnableServerSharing() {
        val local = PhoneContextConfiguration(notificationsLocal = true, calendarLocal = true)
        val notes = snapshot(note()).let { it.copy(policy = it.policy.copy(serverSharingProfiles = emptySet())) }
        val cal = policy.copy(sharing = false)
        val online = body(notes = notes, calPolicy = cal, settings = local)
        assertTrue(online["notifications"]!!.jsonArray.isEmpty()); assertTrue(online["calendar"]!!.jsonArray.isEmpty())
        val offline = body(notes = notes, calPolicy = cal, settings = local, local = true)
        assertEquals(1, offline["notifications"]!!.jsonArray.size); assertEquals(1, offline["calendar"]!!.jsonArray.size)
        assertTrue(body(local = true)["notifications"]!!.jsonArray.isEmpty())
    }
}
