package org.praxisog.hermes.model

import org.junit.Test
import org.junit.Assert.*
import java.time.*

class PlanningTest {
    private val zone=ZoneId.of("America/Los_Angeles")
    @Test fun dayBoundsFollowDstAndAllDayDatesRemainOnTheirAuthoredDate() {
        val spring=CalendarTimes.bounds(LocalDate.of(2026,3,8),zone)
        assertEquals(23*3600000L,spring.last+1-spring.first)
        val fall=CalendarTimes.bounds(LocalDate.of(2026,11,1),zone)
        assertEquals(25*3600000L,fall.last+1-fall.first)
        val date=LocalDate.of(2026,10,12)
        val utc=date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val event=PhoneCalendarEvent(1,1,"All day",utc,utc+86400000,true,"UTC",false,"hash","key")
        assertEquals(date.atStartOfDay(zone).toInstant().toEpochMilli(),CalendarTimes.interval(event,zone).first)
    }
    @Test fun overlapColumnsAndMidnightClippingKeepBlocksDistinct() {
        val day=LocalDate.of(2026,10,12); val zero=day.atStartOfDay(zone).toInstant().toEpochMilli()
        val blocks=listOf(CalendarBlock("a","First",zero+9*3600000,zero+11*3600000),CalendarBlock("b","Second",zero+10*3600000,zero+12*3600000),CalendarBlock("c","Overnight",zero-3600000,zero+3600000))
        val positions=calendarBlocks(day,zone,blocks)
        assertEquals(3,positions.size)
        assertEquals(0,positions.first().topMinute)
        assertEquals(60,positions.first().durationMinute)
        assertEquals(setOf(0,1),positions.drop(1).map { it.column }.toSet())
        assertTrue(positions.drop(1).all { it.columns==2 })
    }
    @Test fun planningSnapshotRejectsDuplicateAndInvalidReminderState() {
        val item=Reminder("a".repeat(32),"A reminder",1,"pending",1)
        val snapshot=PlanningSnapshot(1,listOf(item),observedAt=1.0)
        assertEquals(snapshot,snapshot.validated())
        assertThrows(IllegalArgumentException::class.java) { snapshot.copy(reminders=listOf(item,item)).validated() }
        assertThrows(IllegalArgumentException::class.java) { snapshot.copy(reminders=listOf(item.copy(state="alerted-is-done"))).validated() }
    }
}
