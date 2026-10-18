package org.praxisog.hermes

import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.model.*
import java.time.*

class TodayDataTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val now = Instant.parse("2026-11-01T20:00:00Z").toEpochMilli()
    private val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    private val bounds = CalendarTimes.bounds(day, zone)
    private fun reminder(id: Char, due: Long, state: String = "pending") = Reminder(id.toString().repeat(32), "Reminder $id", due, state, 1)
    private fun event(id: Long, start: Long, end: Long, allDay: Boolean = false, key: String = "chosen") =
        PhoneCalendarEvent(id, 1, "Event $id", start, end, allDay, "UTC", false, "$id", key)
    private fun projection(planning: PlanningSnapshot? = null, delivery: DeliveryState? = null,
        events: List<PhoneCalendarEvent> = emptyList(), readable: Boolean = true,
        runs: Map<String,RunPresentation> = emptyMap(), receipts: Map<String,ConversationReceipt> = emptyMap(),
        legacyId: String? = null, legacy: TurnState = TurnState.Idle) = todayData(now,zone,planning,delivery,
        PhoneCalendarSnapshot(events,now),readable,setOf("chosen"),"phone",listOf(Session("chat",title="My chat")),runs,receipts,legacyId,legacy)
    private fun run(id: String, status: String = "running", synced: Boolean = false, review: Boolean = false, dismissed: Boolean = false) =
        RunPresentation(RunSubmission("request-$id",id,"Prompt",1.0,1,emptySet(),runId="run-$id",status=status,
            historySynced=synced,historyNeedsReview=review,trackingDismissed=dismissed))

    @Test fun calendarUsesLocalDayOverlapAndUtcAllDayDatesAcrossDst() {
        assertEquals(25 * 3_600_000L, bounds.last + 1 - bounds.first)
        val utc = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val events = listOf(event(1,bounds.first-1000,bounds.first+1000),event(2,bounds.last,bounds.last+5000),
            event(3,bounds.first-1000,bounds.first),event(4,bounds.last+1,bounds.last+2000),
            event(5,utc,utc+86_400_000,true),event(6,utc-86_400_000,utc,true),
            event(7,bounds.first,bounds.last,key="unselected"))
        assertEquals(listOf(5L,1L,2L),projection(events=events+events[0]).events.map { it.id })
        assertTrue(projection(events=events,readable=false).events.isEmpty())
    }

    @Test fun remindersIncludeOverdueAndTodayAndHonorOfflineActions() {
        val overdue=reminder('a',bounds.first-86_400_000)
        val today=reminder('b',bounds.last)
        val snoozed=reminder('c',now-1000)
        val blocked=reminder('d',now-1000)
        val planning=PlanningSnapshot(1,listOf(overdue,today,snoozed,blocked,reminder('e',bounds.last+1),reminder('f',now,"completed")),observedAt=1.0)
        val delivery=DeliveryState(planning,operations=listOf(
            ReminderOperation("1".repeat(32),overdue.id,1,"complete"),
            ReminderOperation("2".repeat(32),snoozed.id,1,"snooze",bounds.last+1),
            ReminderOperation("3".repeat(32),blocked.id,1,"complete",status="blocked")))
        val result=projection(planning,delivery)
        assertEquals(listOf(blocked.id,today.id),result.reminders.map { it.id })
        assertEquals(setOf(overdue.id,snoozed.id,blocked.id),result.pendingReminderIds)
        assertEquals(6,planning.reminders.size)
    }

    @Test fun onlyCurrentDecisionsAndTrackedWorkAreShown() {
        val approval=RunApproval("approval",description="Review the command",choices=listOf("once","deny"))
        val runs=mapOf("approve" to run("approve").copy(approval=approval), "review" to run("review",status="review"),
            "chat" to run("chat"), "sync" to run("sync",status="completed"),
            "done" to run("done",status="completed",synced=true).copy(approval=approval),
            "dismissed" to run("dismissed",status="review",dismissed=true),
            "history" to run("history",status="completed",review=true))
        val result=projection(runs=runs,delivery=DeliveryState(entries=listOf(InboxEntry("old-approval","attention","Old decision","",1,"old"))))
        assertEquals(setOf("approve","review","history"),result.decisions.map { it.sessionId }.toSet())
        assertEquals(setOf("chat","sync"),result.running.map { it.sessionId }.toSet())
        assertEquals("Finished work awaiting sync",result.running.single { it.sessionId=="sync" }.status)
        assertEquals("My chat",result.running.single { it.sessionId=="chat" }.title)
    }

    @Test fun unreadCompletionsUseReadReceiptsAndKeepOneRowPerConversation() {
        val delivery=DeliveryState(entries=listOf(InboxEntry("reply:baseline:99","reply","Imported","Not unread",1,"baseline"),
            InboxEntry("reply:chat:7","reply","Reply","Saved latest reply",2,"chat",openedAt=3,dismissedAt=4),
            InboxEntry("reply:chat:6","reply","Reply","Older reply",1,"chat")))
        val receipts=mapOf("chat" to ConversationReceipt(5,7),"read" to ConversationReceipt(8,8))
        val result=projection(delivery=delivery,receipts=receipts)
        assertEquals(listOf("chat"),result.unread.map { it.sessionId })
        assertEquals("Saved latest reply",result.unread.single().preview)
        assertEquals(ConversationReceipt(5,7),receipts["chat"])
        assertTrue(projection(delivery=delivery,receipts=mapOf("chat" to ConversationReceipt(7,7))).unread.isEmpty())
    }

    @Test fun proposalsStayOnTheirPhoneAndLegacyRunsAreNotDuplicated() {
        val pending=CalendarProposal("one","phone","create_event","pending","Proposal",now,now+1000)
        val planning=PlanningSnapshot(1,emptyList(),proposals=listOf(pending,pending.copy(id="other",device="other"),pending.copy(id="done",state="applied")),observedAt=1.0)
        assertEquals(listOf(pending),projection(planning=planning).proposals)
        assertEquals(1,projection(legacyId="chat",legacy=TurnState.Streaming("Text")).running.size)
        assertEquals(1,projection(runs=mapOf("chat" to run("chat")),legacyId="chat",legacy=TurnState.Streaming("Text")).running.size)
    }

    @Test fun missingAndStaleCalendarAreDistinctFromAnEmptyCurrentCalendar() {
        assertFalse(todayCalendarSource(false,setOf("chosen"),PhoneCalendarSnapshot(),null,false,now).available)
        assertFalse(todayCalendarSource(true,emptySet(),PhoneCalendarSnapshot(),null,false,now).available)
        assertFalse(todayCalendarSource(true,setOf("chosen"),PhoneCalendarSnapshot(),null,true,now).available)
        val current=todayCalendarSource(true,setOf("chosen"),PhoneCalendarSnapshot(readAt=now),null,false,now)
        assertTrue(current.available); assertFalse(current.saved)
        val stale=todayCalendarSource(true,setOf("chosen"),PhoneCalendarSnapshot(readAt=now-600_000),"Read failed",false,now)
        assertTrue(stale.available); assertTrue(stale.saved); assertTrue(stale.message.contains("Read failed"))
        val limited=todayCalendarSource(true,setOf("chosen"),PhoneCalendarSnapshot(readAt=now,truncated=true),null,false,now)
        assertFalse(limited.available); assertTrue(limited.message.contains("incomplete"))
    }
}
