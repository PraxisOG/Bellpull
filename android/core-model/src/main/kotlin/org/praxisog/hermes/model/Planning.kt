package org.praxisog.hermes.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import java.time.*
import java.security.MessageDigest

@Serializable data class Reminder(val id: String, val title: String, @SerialName("due_ms") val dueMs: Long,
    val state: String, val revision: Long)
@Serializable data class Routine(val id: String, val name: String? = null, val prompt: String? = null,
    @SerialName("schedule_display") val schedule: String? = null, @SerialName("next_run_at") val next: String? = null,
    @SerialName("last_run_at") val last: String? = null, @SerialName("last_status") val lastStatus: String? = null,
    @SerialName("last_error") val lastError: String? = null, val enabled: Boolean = false,
    val state: String? = null, val deliver: String? = null, val fingerprint: String,
    @SerialName("schedule_input") val scheduleInput: String? = null,
    @SerialName("last_delivery_error") val lastDeliveryError: String? = null)
@Serializable data class CalendarProposal(val id: String, val device: String, val action: String, val state: String,
    val title: String, @SerialName("start_ms") val start: Long? = null, @SerialName("end_ms") val end: Long? = null,
    @SerialName("event_id") val eventId: String? = null, val fingerprint: String? = null, val result: String? = null)
@Serializable data class PlanningSnapshot(val version: Int, val reminders: List<Reminder>,
    val routines: List<Routine> = emptyList(), val proposals: List<CalendarProposal> = emptyList(),
    @SerialName("routines_available") val routinesAvailable: Boolean = false, @SerialName("observed_at") val observedAt: Double) {
    fun validated(): PlanningSnapshot {
        require(version == 1 && reminders.size <= 1000 && routines.size <= 500 && proposals.size <= 100)
        require(reminders.distinctBy { it.id }.size == reminders.size)
        reminders.forEach { require(it.id.matches(Regex("[a-f0-9]{32}")) && it.title.length in 1..400 && it.dueMs > 0 && it.revision > 0 && it.state in setOf("pending","completed","dismissed")) }
        proposals.forEach {
            require(it.id.matches(Regex("[a-f0-9]{32}")) && it.device.matches(Regex("[a-f0-9]{32}")) && it.title.length in 1..400 && it.state in setOf("pending","applied","rejected"))
            require(it.action in setOf("create_event","edit_event","delete_event"))
            if (it.action!="delete_event") require(it.start!=null && it.end!=null && it.start>0 && it.end>it.start)
            if (it.action!="create_event") require(!it.eventId.isNullOrBlank() && !it.fingerprint.isNullOrBlank())
        }
        return this
    }
}

@Serializable data class PhoneCalendarSource(val id: Long, val key: String, val name: String, val writable: Boolean)
@Serializable data class PhoneCalendarEvent(val id: Long, val calendarId: Long, val title: String,
    val start: Long, val end: Long, val allDay: Boolean, val timezone: String, val recurring: Boolean,
    val fingerprint: String, val calendarKey: String, val location: String = "") {
    override fun toString() = "PhoneCalendarEvent(details=redacted)"
}
@Serializable data class CalendarPolicy(val selected: Set<String> = emptySet(), val sharing: Boolean = false,
    val busyOnly: Boolean = true, val writes: Boolean = false, val destination: String? = null,
    val generation: Long = 0)
@Serializable data class PhoneCalendarSnapshot(val events: List<PhoneCalendarEvent> = emptyList(), val readAt: Long = 0,
    val truncated: Boolean = false)

object CalendarTimes {
    fun bounds(day: LocalDate, zone: ZoneId): LongRange =
        day.atStartOfDay(zone).toInstant().toEpochMilli() until day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    /** Calendar all-day instances encode dates at UTC midnight, not local midnight. */
    fun interval(event: PhoneCalendarEvent, zone: ZoneId): Pair<Long,Long> = if (event.allDay) {
        Instant.ofEpochMilli(event.start).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli() to
            Instant.ofEpochMilli(event.end).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    } else event.start to event.end
    fun fingerprint(vararg fields: Any?): String = MessageDigest.getInstance("SHA-256")
        .digest(fields.joinToString("\u0000") { it?.toString().orEmpty() }.toByteArray()).joinToString("") { "%02x".format(it) }
}

data class CalendarBlock(val id: String, val title: String, val start: Long, val end: Long, val reminder: Boolean = false,
    val allDay: Boolean = false)
data class PositionedBlock(val block: CalendarBlock, val topMinute: Int, val durationMinute: Int, val column: Int, val columns: Int)

/** Clip at day boundaries; assign separate columns to every overlapping cluster. */
fun calendarBlocks(day: LocalDate, zone: ZoneId, blocks: List<CalendarBlock>): List<PositionedBlock> {
    val bounds=CalendarTimes.bounds(day,zone)
    val clipped=blocks.filter { !it.allDay && it.end > bounds.first && it.start <= bounds.last }.map {
        val start=maxOf(it.start,bounds.first); val end=minOf(it.end,bounds.last+1)
        val a=Instant.ofEpochMilli(start).atZone(zone).toLocalTime().let { t -> t.hour*60+t.minute }
        val b=if (end==bounds.last+1) 1440 else Instant.ofEpochMilli(end).atZone(zone).toLocalTime().let { t -> t.hour*60+t.minute }
        Triple(it,a,maxOf(a+10,b))
    }.sortedBy { it.second }
    val result=mutableListOf<PositionedBlock>(); var cluster=mutableListOf<PositionedBlock>(); val ends=mutableListOf<Int>()
    fun finish() { result += cluster.map { it.copy(columns=ends.size) }; cluster=mutableListOf(); ends.clear() }
    clipped.forEach { (block,start,end) ->
        if (ends.isNotEmpty() && ends.all { it <= start }) finish()
        var col=ends.indexOfFirst { it <= start }
        if (col < 0) { col=ends.size; ends.add(end) } else ends[col]=end
        cluster.add(PositionedBlock(block,start,end-start,col,0))
    }
    finish(); return result
}
