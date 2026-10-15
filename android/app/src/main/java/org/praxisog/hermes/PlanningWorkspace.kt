package org.praxisog.hermes

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.*
import java.time.*
import java.util.UUID

internal interface PlanningCache {
    fun readPlanning(): PlanningSnapshot?
    fun writePlanning(value: PlanningSnapshot)
    fun readCalendar(): PhoneCalendarSnapshot?
    fun writeCalendar(value: PhoneCalendarSnapshot?)
    fun readCalendarPolicy(): CalendarPolicy
    fun writeCalendarPolicy(value: CalendarPolicy)
    fun calendarDevice(): String
    fun calendarRevokePending(): Boolean
    fun calendarRevokePending(value: Boolean)
    fun calendarReceipt(id: String): String?
    fun calendarReceipt(id: String, result: String)
}

/** Deterministic UI operations. No run admission, auto-apply, cloud sync or inference. */
internal class PlanningWorkspace(private val scope: CoroutineScope, private val client: HermesClient,
    private val cache: PlanningCache, context: Context, var supported: Boolean) {
    private val phone=PhoneCalendar(context.applicationContext)
    val device=cache.calendarDevice()
    var snapshot by mutableStateOf(cache.readPlanning()); private set
    var calendar by mutableStateOf(if (phone.canRead()) cache.readCalendar() ?: PhoneCalendarSnapshot() else PhoneCalendarSnapshot()); private set
    var policy by mutableStateOf(cache.readCalendarPolicy()); private set
    var sources by mutableStateOf<List<PhoneCalendarSource>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var disconnected by mutableStateOf(snapshot!=null); private set
    var problem by mutableStateOf<String?>(null); private set
    var calendarProblem by mutableStateOf<String?>(null); private set
    var result by mutableStateOf<String?>(null); private set
    var revokePending by mutableStateOf(cache.calendarRevokePending()); private set
    var day by mutableStateOf(LocalDate.now()); private set
    private var closed=false
    private var refreshJob: Job?=null
    private var calendarTransport: HermesClient.CalendarTransport?=null
    private val obsoleteRefreshes=mutableListOf<Job>()
    private var mutationJob: Job?=null
    private var active=false
    private var observed=false
    private val resolver=context.contentResolver
    private val observer=object: ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { if (active) refresh() }
    }
    fun permissionChanged() { refresh() }
    fun canRead()=phone.canRead()
    fun canWrite()=phone.canWrite()
    fun open(value: Boolean) {
        active=value
        if (value && !closed) {
            if (!observed && phone.canRead()) { resolver.registerContentObserver(CalendarContract.CONTENT_URI,true,observer); observed=true }
            refresh()
        } else if (observed) { resolver.unregisterContentObserver(observer); observed=false }
    }
    fun selectDay(value: LocalDate) { day=value; refresh() }
    fun refresh() {
        if (closed || refreshJob?.isActive==true) return
        refreshJob=scope.launch {
            delay(200); loading=true
            try {
                if (supported) when (val response=withContext(Dispatchers.IO) { client.planning() }) {
                    is HermesResult.Ok -> { if (closed) return@launch; snapshot=response.value; cache.writePlanning(response.value); disconnected=false; problem=null }
                    is HermesResult.Err -> { disconnected=true; problem=response.error.toUserMessage() }
                }
                readPhone()
                syncCalendar()
            } finally { loading=false }
        }
    }
    private suspend fun readPhone() {
        if (!phone.canRead()) {
            sources=emptyList(); calendar=PhoneCalendarSnapshot(); cache.writeCalendar(null)
            calendarProblem="Phone calendar read access is off."
            if (policy.sharing && !revokePending) { revokePending=true; cache.calendarRevokePending(true) }
            return
        }
        val selected=policy; val date=day
        runCatching { withContext(Dispatchers.IO) {
            phone.sources() to phone.read(selected,date.minusDays(7).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),date.plusDays(15).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
        } }.onSuccess { (available,read) ->
            if (!closed && policy==selected) { sources=available; calendar=read; cache.writeCalendar(read); calendarProblem=null }
        }.onFailure { if (it is CancellationException) throw it; calendarProblem="Calendar read failed. Saved events may be stale: ${it.message}" }
    }
    fun savePolicy(value: CalendarPolicy) {
        if (closed || busy) return
        val previous=policy
        if (previous.copy(generation=0)!=value.copy(generation=0)) {
            revokePending=true; cache.calendarRevokePending(true)
        }
        policy=value.copy(generation=previous.generation); cache.writeCalendarPolicy(policy)
        calendar=PhoneCalendarSnapshot(); cache.writeCalendar(null)
        calendarTransport?.cancel()
        refreshJob?.let { it.cancel(); obsoleteRefreshes.add(it) }
        refreshJob=null; refresh()
    }
    private suspend fun shareCalendar(body: JsonObject): HermesResult<JsonObject> {
        val transport=HermesClient.CalendarTransport()
        calendarTransport=transport
        return try { withContext(Dispatchers.IO) { client.shareCalendar(device,body,transport) } }
        finally { if (calendarTransport===transport) calendarTransport=null }
    }
    private fun acknowledge(response: HermesResult.Ok<JsonObject>): Boolean {
        val generation=response.value["generation"]?.jsonPrimitive?.longOrNull
        if (generation==null || generation<policy.generation) {
            calendarProblem="Hermes did not acknowledge the calendar policy generation. Update the server plugin and retry."
            return false
        }
        policy=policy.copy(generation=generation); cache.writeCalendarPolicy(policy)
        return true
    }
    private suspend fun syncCalendar() {
        if (!supported) return
        if (revokePending) {
            val response=shareCalendar(buildJsonObject { put("revoke",true); put("generation",policy.generation) })
            if (response is HermesResult.Err) { calendarProblem="Sharing is off locally; clearing the server cache is pending reconnection."; return }
            if (response !is HermesResult.Ok || !acknowledge(response)) return
            revokePending=false; cache.calendarRevokePending(false)
        }
        // Revoke first: cancellation cannot prevent an already-delivered request
        // from committing. Join obsolete calls before admitting a fresh upload.
        val obsolete=obsoleteRefreshes.toList()
        obsolete.forEach { it.cancelAndJoin() }; obsoleteRefreshes.removeAll(obsolete.toSet())
        if (!policy.sharing || !phone.canRead() || calendarProblem!=null || calendar.readAt==0L) return
        val value=calendar; val selection=policy
        val response=shareCalendar(buildJsonObject {
            put("generation",selection.generation)
            put("read_at_ms",value.readAt); put("busy_only",selection.busyOnly)
            put("events",buildJsonArray { value.events.forEach { event -> add(buildJsonObject {
                put("id",event.id.toString())
                if (!selection.busyOnly) { put("title",event.title); put("fingerprint",event.fingerprint) }
                put("start_ms",event.start); put("end_ms",event.end); put("all_day",event.allDay)
            }) } })
        })
        if (closed || policy!=selection) return
        when (response) {
            is HermesResult.Ok -> acknowledge(response)
            is HermesResult.Err -> {
                // (Uploads superseded by a local policy change already returned above.)
                val stale=when (val error=response.error) {
                    is HermesError.ServerError -> error.status==409 && error.message=="Stale calendar policy generation."
                    else -> false
                }
                if (stale) {
                    // A stale 409 on the CURRENT policy means the server revoked out from
                    // under our acknowledged generation (e.g. the acknowledge was lost).
                    // Silence would strand sharing forever: re-arm the durable revoke
                    // barrier so the next sync re-establishes a server-acknowledged
                    // generation, and state what the user can do.
                    revokePending=true; cache.calendarRevokePending(true)
                    calendarProblem="Hermes no longer recognizes this phone's calendar generation. Sharing re-arms through a fresh revoke on the next connection; events re-upload after that."
                } else calendarProblem="Phone read is current; sharing to Hermes failed. ${response.error.toUserMessage()}"
            }
        }
    }
    fun action(kind: String, fields: JsonObject, operation: String=UUID.randomUUID().toString().replace("-","")) {
        if (!supported || busy || closed) return
        busy=true; problem=null
        mutationJob=scope.launch {
            try {
                when (val response=withContext(Dispatchers.IO) { client.planningAction(kind,JsonObject(fields+mapOf("operation_id" to JsonPrimitive(operation)))) }) {
                    is HermesResult.Ok -> { result="Saved in Hermes."; refreshJob?.cancel(); refreshJob=null; refresh() }
                    is HermesResult.Err -> problem="${response.error.toUserMessage()} Refresh before repeating an uncertain routine change."
                }
            } finally { busy=false }
        }
    }
    fun reminder(value: Reminder, action: String, due: Long?=null) = this.action("reminders",buildJsonObject {
        put("action",action); put("id",value.id); put("revision",value.revision); due?.let { put("due_ms",it) }
    })
    fun routine(value: Routine, action: String) = this.action("routines",buildJsonObject {
        put("action",action); put("id",value.id); put("fingerprint",value.fingerprint)
    })
    private fun source(event: PhoneCalendarEvent?=null): PhoneCalendarSource = checkNotNull(sources.find {
        if (event==null) it.key==policy.destination else it.id==event.calendarId && it.key==event.calendarKey
    }) { "Select a writable destination calendar first." }
    fun createEvent(draft: CalendarDraft, operation: String, proposal: CalendarProposal?=null) = localWrite(operation,proposal) { recovering ->
        val saved=phone.create(operation,policy,source(),draft,recoverOnly=recovering)
        "Created event ${saved.id} in phone calendar; account sync is separate."
    }
    fun changeEvent(event: PhoneCalendarEvent, draft: CalendarDraft?, operation: String, proposal: CalendarProposal?=null) = localWrite(operation,proposal) { _ ->
        phone.change(policy,source(event),event,draft)
    }
    private fun localWrite(operation: String, proposal: CalendarProposal?, write: (Boolean) -> String) {
        if (closed || busy) return
        busy=true; calendarProblem=null
        mutationJob=scope.launch {
            try {
                val previous=cache.calendarReceipt(operation)
                val verified=if (previous!=null && previous!="pending") previous else withContext(Dispatchers.IO) {
                    check(previous!="pending" || proposal?.action=="create_event") { "Previous calendar write outcome is uncertain. Refresh and check your calendar before editing again." }
                    cache.calendarReceipt(operation,"pending")
                    write(previous=="pending").also { cache.calendarReceipt(operation,it) }
                }
                result=verified
                if (proposal!=null) reportProposal(proposal,"applied",verified)
                refreshJob?.cancel(); refreshJob=null; refresh()
            } catch (e: Exception) { if (e is CancellationException) throw e; calendarProblem=e.message ?: "Calendar write failed. Refresh before retrying." }
            finally { busy=false }
        }
    }
    private suspend fun reportProposal(value: CalendarProposal, state: String, result: String) {
        when (val response=withContext(Dispatchers.IO) { client.planningAction("proposals",buildJsonObject { put("id",value.id); put("device",device); put("state",state); put("result",result) }) }) {
            is HermesResult.Err -> calendarProblem="Phone result saved; reporting it to Hermes is pending. Reopen this proposal to retry without another write."
            else -> Unit
        }
    }
    fun applyProposal(value: CalendarProposal) {
        if (value.device!=device || value.state!="pending") return
        if (cache.calendarReceipt(value.id)?.let { it!="pending" }==true) {
            localWrite(value.id,value) { _ -> error("A verified receipt never repeats a write.") }; return
        }
        val event=calendar.events.find { it.id.toString()==value.eventId && it.fingerprint==value.fingerprint }
        val draft=if (value.action!="delete_event") CalendarDraft(value.title,checkNotNull(value.start),checkNotNull(value.end)) else null
        when {
            value.action=="create_event" -> createEvent(checkNotNull(draft),value.id,value)
            event!=null -> changeEvent(event,draft,value.id,value)
            else -> calendarProblem="The event reference is stale or outside the current phone snapshot. Refresh before applying."
        }
    }
    fun rejectProposal(value: CalendarProposal) { if (!busy && value.device==device) {
        busy=true; mutationJob=scope.launch { try { reportProposal(value,"rejected","Rejected on phone."); refresh() } finally { busy=false } }
    } }
    fun close() { closed=true; open(false); calendarTransport?.cancel(); refreshJob?.cancel(); obsoleteRefreshes.forEach { it.cancel() }; mutationJob?.cancel() }
}
