package org.praxisog.hermes

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.provider.CalendarContract
import org.praxisog.hermes.model.*
import java.time.*

internal data class CalendarDraft(val title: String, val start: Long, val end: Long, val allDay: Boolean = false,
    val zone: String = ZoneId.systemDefault().id, val recurrence: String? = null, val alertMinutes: Int? = null) {
    fun validated(): CalendarDraft {
        require(title.isNotBlank() && title.length <= 300) { "Use a title of 1–300 characters." }
        require(start > 0 && end > start) { "End must follow start." }
        ZoneId.of(zone)
        require(recurrence == null || recurrence.matches(Regex("FREQ=(DAILY|WEEKLY);COUNT=([1-9]|[1-9][0-9]|100)"))) { "Use a daily or weekly repeat, at most 100 occurrences." }
        require(alertMinutes == null || alertMinutes in 0..10080)
        if (allDay) require(start % 86400000L == 0L && end % 86400000L == 0L) { "All-day dates use UTC midnights with an exclusive end date." }
        return this
    }
}

/** CalendarContract only. This neither starts account sync nor connects to a cloud calendar. */
internal class PhoneCalendar(private val context: Context) {
    private val resolver get() = context.contentResolver
    fun canRead() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    fun canWrite() = context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
    private val calendars = CalendarContract.Calendars.CONTENT_URI
    private val events = CalendarContract.Events.CONTENT_URI
    private val projection = arrayOf("_id","calendar_id","title","dtstart","dtend","allDay","eventTimezone","rrule","original_id","eventLocation","duration")
    fun sources(): List<PhoneCalendarSource> {
        check(canRead()) { "Calendar read permission is required." }
        return resolver.query(calendars,arrayOf("_id","calendar_displayName","calendar_access_level","account_name","account_type"),null,null,null)?.use { c ->
            buildList { while (c.moveToNext() && size < 100) add(PhoneCalendarSource(c.getLong(0),CalendarTimes.fingerprint(c.getLong(0),c.getString(3),c.getString(4)),c.getString(1).orEmpty(),c.getInt(2)>=CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)) }
        } ?: error("The phone calendar provider is unavailable.")
    }
    private fun checked(policy: CalendarPolicy, source: PhoneCalendarSource, write: Boolean = false) {
        check(canRead() && source.key in policy.selected) { "Calendar is no longer selected." }
        check(sources().any { it.id==source.id && it.key==source.key && (!write || it.writable) }) { "Calendar account changed or is read-only." }
        if (write) check(canWrite() && policy.writes && source.writable) { "Enable calendar writes and Android permission first." }
    }
    private fun row(c: android.database.Cursor, source: PhoneCalendarSource): PhoneCalendarEvent {
        val values=projection.map { c.getString(c.getColumnIndexOrThrow(it)).orEmpty() }
        val end=values[4].toLongOrNull() ?: (values[3].toLong()+runCatching { Duration.parse(values[10]).toMillis() }.getOrDefault(0))
        return PhoneCalendarEvent(values[0].toLong(),source.id,values[2].ifBlank { "Untitled event" },values[3].toLong(),end,values[5]=="1",values[6].ifBlank { "UTC" },values[7].isNotBlank() || values[8].isNotBlank(),CalendarTimes.fingerprint(source.key,*values.toTypedArray()),source.key,values[9])
    }
    fun event(id: Long, source: PhoneCalendarSource): PhoneCalendarEvent? = resolver.query(ContentUris.withAppendedId(events,id),projection,"deleted=0",null,null)?.use { c -> if (c.moveToFirst() && c.getLong(1)==source.id) row(c,source) else null }
    fun read(policy: CalendarPolicy, from: Long, until: Long): PhoneCalendarSnapshot {
        check(canRead()) { "Calendar read permission is required." }
        require(until > from && until-from <= 35L*86400000)
        val selected=sources().filter { it.key in policy.selected }.associateBy { it.id }
        if (selected.isEmpty()) return PhoneCalendarSnapshot(readAt=System.currentTimeMillis())
        val uri=CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it,from-86400000); ContentUris.appendId(it,until+86400000) }.build()
        val args=selected.keys.map(Long::toString).toTypedArray()
        val output=mutableListOf<PhoneCalendarEvent>(); var truncated=false
        val originals=mutableMapOf<Long,PhoneCalendarEvent?>()
        resolver.query(uri,arrayOf("event_id","calendar_id","begin","end"),"calendar_id IN (${args.joinToString(",") { "?" }})",args,"begin ASC")?.use { c ->
            while (c.moveToNext()) {
                if (output.size>=500) { truncated=true; break }
                val source=selected[c.getLong(1)] ?: continue; val id=c.getLong(0)
                val master=originals.getOrPut(id) { event(id,source) } ?: continue
                val value=master.copy(start=c.getLong(2),end=c.getLong(3))
                val interval=CalendarTimes.interval(value,ZoneId.systemDefault())
                if (interval.second > from && interval.first < until) output.add(value)
            }
        } ?: error("The phone calendar provider did not return events.")
        return PhoneCalendarSnapshot(output,System.currentTimeMillis(),truncated)
    }
    /** Stable create marker recovers a provider commit if the local receipt was interrupted. */
    @Synchronized fun create(operation: String, policy: CalendarPolicy, source: PhoneCalendarSource, draft: CalendarDraft, recoverOnly: Boolean=false): PhoneCalendarEvent {
        require(operation.matches(Regex("[a-f0-9]{32}"))); draft.validated(); checked(policy,source,true)
        val marker="bellpull://calendar/$operation"
        resolver.query(events,arrayOf("_id","calendar_id"),"customAppPackage=? AND customAppUri=? AND deleted=0",arrayOf(context.packageName,marker),null)?.use { c ->
            if (c.moveToFirst()) {
                check(c.getLong(1)==source.id) { "Operation already belongs to another calendar." }
                val saved=checkNotNull(event(c.getLong(0),source))
                check(saved.title==draft.title.trim() && saved.start==draft.start && saved.end==draft.end && saved.allDay==draft.allDay && saved.recurring==(draft.recurrence!=null)) { "Operation identity already belongs to different event content." }
                return saved
            }
        }
        check(!recoverOnly) { "Previous creation outcome is uncertain and its provider marker is absent. Check the calendar before creating another event." }
        val values=values(draft).apply { put("calendar_id",source.id); put("customAppPackage",context.packageName); put("customAppUri",marker) }
        val operations=arrayListOf(ContentProviderOperation.newInsert(events).withValues(values).build())
        if (draft.alertMinutes!=null) operations.add(ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
            .withValueBackReference("event_id",0).withValue("minutes",draft.alertMinutes).withValue("method",CalendarContract.Reminders.METHOD_ALERT).build())
        val result=resolver.applyBatch(CalendarContract.AUTHORITY,operations)
        val id=ContentUris.parseId(checkNotNull(result[0].uri))
        val saved=checkNotNull(event(id,source)) { "Provider saved no readable event. Refresh before retrying." }
        check(saved.title==draft.title.trim() && saved.start==draft.start && saved.end==draft.end && saved.allDay==draft.allDay) { "Saved event differs from the request. Refresh before another write." }
        return saved
    }
    @Synchronized fun change(policy: CalendarPolicy, source: PhoneCalendarSource, expected: PhoneCalendarEvent, draft: CalendarDraft?): String {
        checked(policy,source,true)
        val raw=ContentValues()
        val current=resolver.query(ContentUris.withAppendedId(events,expected.id),projection,"deleted=0",null,null)?.use { c ->
            if (!c.moveToFirst() || c.getLong(1)!=source.id) null else {
                android.database.DatabaseUtils.cursorRowToContentValues(c,raw)
                row(c,source)
            }
        } ?: error("Event no longer exists. Refresh first.")
        check(!current.recurring) { "Open recurring occurrences or series in your calendar app." }
        check(current.fingerprint==expected.fingerprint) { "Event changed. Refresh before editing." }
        draft?.validated()
        check(draft?.recurrence==null) { "Create recurrence as a new event." }
        val uri=ContentUris.withAppendedId(events,current.id)
        val assert=ContentProviderOperation.newAssertQuery(uri).withExpectedCount(1).withValues(raw).build()
        val mutation=if (draft==null) ContentProviderOperation.newDelete(uri).withExpectedCount(1).build()
            else ContentProviderOperation.newUpdate(uri).withValues(values(draft)).withExpectedCount(1).build()
        resolver.applyBatch(CalendarContract.AUTHORITY,arrayListOf(assert,mutation))
        val saved=event(current.id,source)
        if (draft==null) check(saved==null) { "Deletion could not be verified." }
        else check(saved!=null && saved.title==draft.title && saved.start==draft.start && saved.end==draft.end) { "Calendar result differs. Refresh before another edit." }
        return if (draft==null) "Deleted from phone calendar; account sync is separate." else "Updated phone calendar; account sync is separate."
    }
    private fun values(draft: CalendarDraft) = ContentValues().apply {
        put("title",draft.title.trim()); put("dtstart",draft.start); put("eventTimezone",if (draft.allDay) "UTC" else draft.zone)
        put("allDay",if (draft.allDay) 1 else 0)
        if (draft.recurrence==null) { put("dtend",draft.end); putNull("duration"); putNull("rrule") }
        else { putNull("dtend"); put("duration",if (draft.allDay) "P${(draft.end-draft.start)/86400000}D" else "PT${(draft.end-draft.start)/1000}S"); put("rrule",draft.recurrence) }
    }
}
