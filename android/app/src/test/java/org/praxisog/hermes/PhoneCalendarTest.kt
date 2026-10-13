package org.praxisog.hermes

import android.Manifest
import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.praxisog.hermes.model.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.time.*

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class PhoneCalendarTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private lateinit var provider: CalendarProviderFixture
    private lateinit var phone: PhoneCalendar
    @Before fun setup() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)
        provider=CalendarProviderFixture(); provider.attachInfo(app,android.content.pm.ProviderInfo().apply { authority=CalendarContract.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY,provider)
        phone=PhoneCalendar(app)
    }
    private fun policy(source: PhoneCalendarSource)=CalendarPolicy(setOf(source.key),writes=true,destination=source.key)
    @Test fun createRetryWithStableMarkerVerifiesOneEventAndOneAlert() {
        val source=phone.sources().first(); val policy=policy(source)
        val draft=CalendarDraft("Fixture meeting",2000000000000,2000003600000,alertMinutes=10)
        val one=phone.create("a".repeat(32),policy,source,draft)
        val retry=phone.create("a".repeat(32),policy,source,draft)
        assertEquals(one.id,retry.id); assertEquals(1,provider.rows.size); assertEquals(1,provider.alerts)
        assertThrows(IllegalStateException::class.java) { phone.create("a".repeat(32),policy,source,draft.copy(title="Different")) }
    }
    @Test fun providerCommitBeforeResponseCanBeRecoveredWithoutAnotherInsert() {
        val source=phone.sources().first(); val draft=CalendarDraft("Interrupted",2000000000000,2000003600000)
        provider.interruptNextBatch=true
        assertThrows(RuntimeException::class.java) { phone.create("b".repeat(32),policy(source),source,draft) }
        assertEquals(1,provider.rows.size)
        phone.create("b".repeat(32),policy(source),source,draft,recoverOnly=true)
        assertEquals(1,provider.rows.size)
        assertThrows(IllegalStateException::class.java) { phone.create("c".repeat(32),policy(source),source,draft,recoverOnly=true) }
        assertEquals(1,provider.rows.size)
    }
    @Test fun staleEventReadOnlyCalendarAndRevokedPermissionCannotWrite() {
        val source=phone.sources().first(); val policy=policy(source)
        assertThrows(IllegalStateException::class.java) { phone.create("c".repeat(32),policy,source.copy(writable=false),CalendarDraft("Read only",2000000000000,2000003600000)) }
        val old=phone.create("a".repeat(32),policy,source,CalendarDraft("Before",2000000000000,2000003600000))
        provider.rows[old.id]!!.put("title","Edited elsewhere")
        assertThrows(IllegalStateException::class.java) { phone.change(policy,source,old,null) }
        val current=phone.event(old.id,source)!!
        phone.change(policy,source,current,CalendarDraft("After",current.start,current.end))
        assertEquals("After",phone.event(old.id,source)!!.title)
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        assertThrows(IllegalStateException::class.java) { phone.change(policy,source,phone.event(old.id,source)!!,null) }
        assertEquals(1,provider.rows.size)
    }
    @Test fun allDayAndSimpleRecurrenceUseCorrectProviderEncodingAndRequireSeriesHandoff() {
        val source=phone.sources().first(); val start=LocalDate.of(2033,5,18).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val value=phone.create("a".repeat(32),policy(source),source,CalendarDraft("All day repeat",start,start+86400000,true,recurrence="FREQ=DAILY;COUNT=10"))
        assertEquals("UTC",provider.rows[value.id]!!.getAsString("eventTimezone"))
        assertEquals("P1D",provider.rows[value.id]!!.getAsString("duration"))
        assertTrue(value.recurring); assertEquals(start+86400000,value.end)
        assertThrows(IllegalStateException::class.java) { phone.change(policy(source),source,value,null) }
    }
    @Test fun localReadIsBoundedToSelectedCalendarAndDeleteIsVerified() {
        val source=phone.sources().first(); val policy=policy(source)
        val event=phone.create("a".repeat(32),policy,source,CalendarDraft("Selected",2000000000000,2000003600000))
        assertEquals(1,phone.read(policy,event.start,event.end).events.size)
        assertEquals(0,phone.read(CalendarPolicy(),event.start,event.end).events.size)
        phone.change(policy,source,event,null); assertNull(phone.event(event.id,source))
    }
}

/** Exercises our CalendarContract calls; not evidence about a real account/sync adapter. */
internal class CalendarProviderFixture: ContentProvider() {
    val rows=linkedMapOf<Long,ContentValues>(); var alerts=0; var interruptNextBatch=false
    override fun onCreate()=true
    override fun getType(uri: Uri)="vnd.android.cursor.item/event"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val columns=projection ?: arrayOf("_id")
        val cursor=MatrixCursor(columns)
        if (uri.pathSegments.first()=="calendars") {
            val values=mapOf<String,Any>("_id" to 1L,"calendar_displayName" to "Fixture calendar","calendar_access_level" to 700,"account_name" to "fixture","account_type" to "local")
            cursor.addRow(columns.map { values[it] }.toTypedArray()); return cursor
        }
        val id=uri.pathSegments.getOrNull(1)?.toLongOrNull().takeIf { uri.pathSegments.first()=="events" }
        rows.forEach { (key,values) ->
            if (id!=null && key!=id) return@forEach
            if (selection?.contains("customAppUri=?")==true && (values.getAsString("customAppPackage")!=selectionArgs!![0] || values.getAsString("customAppUri")!=selectionArgs[1])) return@forEach
            cursor.addRow(columns.map { column -> when(column) { "event_id" -> key; "begin" -> values.getAsLong("dtstart"); "end" -> values.getAsLong("dtend") ?: values.getAsLong("dtstart")+Duration.parse(values.getAsString("duration")).toMillis(); else -> values.get(column) } }.toTypedArray())
        }
        return cursor
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri {
        if (uri.pathSegments.first()=="reminders") { alerts++; return ContentUris.withAppendedId(uri,alerts.toLong()) }
        val id=(rows.keys.maxOrNull() ?: 0)+1
        rows[id]=ContentValues(values).apply { put("_id",id); put("deleted",0) }
        return ContentUris.withAppendedId(uri,id)
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        val row=rows[ContentUris.parseId(uri)] ?: return 0; row.putAll(values); return 1
    }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = if (rows.remove(ContentUris.parseId(uri))!=null) 1 else 0
    override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
        val results=super.applyBatch(operations)
        if (interruptNextBatch) { interruptNextBatch=false; throw RuntimeException("Fixture lost response after provider commit") }
        return results
    }
}
