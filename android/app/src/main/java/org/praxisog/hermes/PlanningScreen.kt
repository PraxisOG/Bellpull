package org.praxisog.hermes

import android.content.ContentUris
import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.serialization.json.*
import org.praxisog.hermes.model.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable internal fun PlanningScreen(workspace: PlanningWorkspace, foreground: Boolean, requestPermission: (Boolean) -> Unit) {
    DisposableEffect(workspace,foreground) { workspace.open(foreground); onDispose { workspace.open(false) } }
    var tab by remember { mutableStateOf("Reminders") }
    var editor by remember { mutableStateOf<String?>(null) }
    var editingReminder by remember { mutableStateOf<Reminder?>(null) }
    var editingRoutine by remember { mutableStateOf<Routine?>(null) }
    var selectedEvent by remember { mutableStateOf<PhoneCalendarEvent?>(null) }
    var eventEditor by remember { mutableStateOf(false) }
    var proposal by remember { mutableStateOf<CalendarProposal?>(null) }
    val context=LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("Today",style=MaterialTheme.typography.titleLarge)
            TextButton(onClick=workspace::refresh,enabled=!workspace.loading) { Text("Refresh") }
        }
        Text("Planning prototype · reminders need this view for now; background/offline alerts belong to Phase 10.",style=MaterialTheme.typography.bodySmall)
        if (!workspace.supported) Text("Reminders and routines need this branch’s Bellpull planning plugin. Phone calendar stays local until sharing is enabled.")
        if (workspace.disconnected) Text("Saved planning data · Hermes has not refreshed.",color=MaterialTheme.colorScheme.error)
        workspace.problem?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        workspace.result?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
        if (workspace.loading || workspace.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) { listOf("Reminders","Routines","Calendar").forEach { label ->
            FilterChip(tab==label,{ tab=label },label={ Text(label) })
        } }
        when(tab) {
            "Reminders" -> {
                Button(onClick={ editingReminder=null; editor="Reminder" },enabled=workspace.supported && !workspace.busy) { Text("Add reminder") }
                val values=workspace.snapshot?.reminders.orEmpty().sortedBy { it.dueMs }
                val now=System.currentTimeMillis()
                if (values.isEmpty()) Text("No reminders saved.")
                listOf("Needs attention","Upcoming","Finished").forEach { group ->
                    val reminders=values.filter { when(group) { "Needs attention" -> it.state=="pending" && it.dueMs<now; "Upcoming" -> it.state=="pending" && it.dueMs>=now; else -> it.state!="pending" } }
                    if (reminders.isNotEmpty()) Text(group,style=MaterialTheme.typography.titleMedium)
                    reminders.forEach { reminder -> PlanningCard {
                        Text(reminder.title,style=MaterialTheme.typography.titleSmall)
                        Text("${displayInstant(reminder.dueMs)} · ${reminder.state}",style=MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            if (reminder.state=="pending") {
                                TextButton(onClick={ workspace.reminder(reminder,"complete") },enabled=!workspace.busy) { Text("Complete") }
                                TextButton(onClick={ workspace.reminder(reminder,"snooze",now+3600000) },enabled=!workspace.busy) { Text("Snooze 1h") }
                                TextButton(onClick={ workspace.reminder(reminder,"dismiss") },enabled=!workspace.busy) { Text("Dismiss") }
                            } else TextButton(onClick={ workspace.reminder(reminder,"reopen") },enabled=!workspace.busy) { Text("Reopen") }
                            TextButton(onClick={ editingReminder=reminder; editor="Reminder" },enabled=!workspace.busy) { Text("Edit") }
                            TextButton(onClick={ workspace.reminder(reminder,"delete") },enabled=!workspace.busy) { Text("Delete") }
                        }
                    } }
                }
            }
            "Routines" -> {
                Text("Hermes runs these jobs on its own scheduler. A routine can use tools and inference; it is separate from a simple reminder.",style=MaterialTheme.typography.bodySmall)
                Button(onClick={ editingRoutine=null; editor="Routine" },enabled=workspace.snapshot?.routinesAvailable==true && !workspace.busy) { Text("Add Hermes routine") }
                if (workspace.snapshot?.routinesAvailable!=true) Text("Hermes scheduler controls are unavailable.")
                workspace.snapshot?.routines.orEmpty().forEach { routine -> PlanningCard {
                    Text(routine.name ?: routine.id,style=MaterialTheme.typography.titleSmall)
                    Text("${routine.state ?: if (routine.enabled) "Enabled" else "Paused"} · ${routine.schedule.orEmpty()}")
                    Text("Next: ${routine.next ?: "Not scheduled"}\nLast: ${routine.last ?: "Never"} · ${routine.lastStatus ?: "No result"}\nDelivery: ${routine.deliver ?: "local"}",style=MaterialTheme.typography.bodySmall)
                    routine.lastError?.let { Text(it.take(1000),color=MaterialTheme.colorScheme.error) }
                    routine.lastDeliveryError?.let { Text("Delivery: ${it.take(1000)}",color=MaterialTheme.colorScheme.error) }
                    FlowRow {
                        TextButton(onClick={ workspace.routine(routine,if (routine.enabled) "pause" else "resume") },enabled=!workspace.busy) { Text(if (routine.enabled) "Pause" else "Resume") }
                        TextButton(onClick={ editingRoutine=routine; editor="Routine" },enabled=!workspace.busy) { Text("Edit") }
                        TextButton(onClick={ workspace.routine(routine,"delete") },enabled=!workspace.busy) { Text("Cancel routine") }
                    }
                } }
            }
            else -> {
                CalendarControls(workspace,requestPermission)
                workspace.calendarProblem?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                if (workspace.calendar.readAt>0) Text("Phone read: ${displayInstant(workspace.calendar.readAt)} · account/cloud sync is managed by your calendar account.",style=MaterialTheme.typography.bodySmall)
                if (workspace.calendar.truncated) Text("500-event preview limit reached. Narrow the calendars or date range.")
                Button(onClick={ selectedEvent=null; eventEditor=true },enabled=workspace.policy.writes && workspace.canWrite() && !workspace.busy) { Text("Add calendar event") }
                CalendarBlocksView(workspace.day,workspace.calendar.events,workspace.snapshot?.reminders.orEmpty(),workspace::selectDay,
                    { selectedEvent=it }, { editingReminder=it; editor="Reminder" })
                val pending=workspace.snapshot?.proposals.orEmpty().filter { it.device==workspace.device && it.state=="pending" }
                if (pending.isNotEmpty()) Text("Pending Hermes proposals",style=MaterialTheme.typography.titleMedium)
                pending.forEach { value -> OutlinedButton(onClick={ proposal=value }) { Text("${value.action.replace('_',' ')} · ${value.title}") } }
            }
        }
    }
    editor?.let { kind -> PlanningEditor(kind,editingReminder,editingRoutine,workspace.busy,{ editor=null }) { title,whenText,prompt,operation ->
        workspace.action(if (kind=="Reminder") "reminders" else "routines",buildJsonObject {
            put("action",if (editingReminder!=null || editingRoutine!=null) "edit" else "create")
            if (kind=="Reminder") {
                put("title",title); put("due_ms",OffsetDateTime.parse(whenText).toInstant().toEpochMilli())
                editingReminder?.let { put("id",it.id); put("revision",it.revision) }
            } else {
                put("name",title); put("schedule",whenText); put("prompt",prompt)
                editingRoutine?.let { put("id",it.id); put("fingerprint",it.fingerprint) }
            }
        },operation); editor=null
    } }
    if (eventEditor) CalendarEventEditor(selectedEvent,workspace.busy,{ eventEditor=false }) { value,operation ->
        val existing=selectedEvent
        if (existing==null) workspace.createEvent(value,operation) else workspace.changeEvent(existing,value,operation)
        eventEditor=false; selectedEvent=null
    }
    if (selectedEvent!=null && !eventEditor) {
        val event=checkNotNull(selectedEvent)
        AlertDialog(onDismissRequest={ selectedEvent=null },title={ Text(event.title) },text={ Column {
            Text(if (event.allDay) "All day · ${Instant.ofEpochMilli(event.start).atZone(ZoneOffset.UTC).toLocalDate()}" else "${displayInstant(event.start)} – ${displayInstant(event.end)}")
            Text("${event.timezone}${if (event.recurring) " · recurring occurrence" else ""}")
            if (event.location.isNotBlank()) Text(event.location)
            Text("Calendar apps own appointment alerts.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={ runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,event.id)).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,event.start).putExtra(CalendarContract.EXTRA_EVENT_END_TIME,event.end)) } }) { Text("Open calendar app") }
            if (!event.recurring && workspace.policy.writes) {
                TextButton(onClick={ eventEditor=true }) { Text("Edit event") }
                TextButton(onClick={ workspace.changeEvent(event,null,newOperation()); selectedEvent=null },enabled=!workspace.busy) { Text("Delete event") }
            }
        } },confirmButton={ TextButton(onClick={ selectedEvent=null }) { Text("Close") } })
    }
    proposal?.let { value -> AlertDialog(onDismissRequest={ proposal=null },title={ Text("Calendar proposal") },text={ Column {
        Text("${value.action.replace('_',' ')}: ${value.title}")
        value.start?.let { Text("${displayInstant(it)} – ${displayInstant(checkNotNull(value.end))}") }
        Text("Applying validates current calendar access and event state on this phone. It does not establish cloud sync.",style=MaterialTheme.typography.bodySmall)
    } },confirmButton={ TextButton(onClick={ workspace.applyProposal(value); proposal=null },enabled=workspace.policy.writes && workspace.canWrite() && !workspace.busy) { Text("Apply on phone") } },dismissButton={ TextButton(onClick={ workspace.rejectProposal(value); proposal=null },enabled=!workspace.busy) { Text("Reject") } }) }
}

@Composable private fun PlanningCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp),content=content)
    }
}

@Composable private fun CalendarControls(w: PlanningWorkspace, request: (Boolean) -> Unit) {
    SettingsSection("Phone calendar access","Local calendar, sharing and writes are separate choices") {
        if (!w.canRead()) Button(onClick={ request(false) }) { Text("Allow phone calendar reading") }
        w.sources.forEach { source -> Row(Modifier.fillMaxWidth()) {
            Checkbox(source.key in w.policy.selected,{ checked -> w.savePolicy(w.policy.copy(selected=if (checked) w.policy.selected+source.key else w.policy.selected-source.key,destination=w.policy.destination.takeUnless { !checked && it==source.key })) },enabled=!w.busy)
            Text(source.name+if (source.writable) "" else " · Read only",Modifier.weight(1f).padding(top=12.dp))
        } }
        Row { Checkbox(w.policy.sharing,{ w.savePolicy(w.policy.copy(sharing=it)) },enabled=w.supported && w.policy.selected.isNotEmpty() && !w.busy); Text("Share selected calendar with this Hermes connection",Modifier.weight(1f)) }
        Row { Checkbox(w.policy.busyOnly,{ w.savePolicy(w.policy.copy(busyOnly=it)) },enabled=!w.busy); Text("Share busy times only",Modifier.weight(1f)) }
        if (w.revokePending) Text("Server cache removal is pending reconnect.",color=MaterialTheme.colorScheme.error)
        if (!w.canWrite()) OutlinedButton(onClick={ request(true) }) { Text("Allow calendar write permission") }
        Row { Checkbox(w.policy.writes,{ w.savePolicy(w.policy.copy(writes=it)) },enabled=w.canWrite() && !w.busy); Text("Enable writes from Bellpull",Modifier.weight(1f)) }
        Text("Default destination",style=MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { w.sources.filter { it.writable && it.key in w.policy.selected }.forEach { source ->
            FilterChip(w.policy.destination==source.key,{ w.savePolicy(w.policy.copy(destination=source.key)) },label={ Text(source.name) },enabled=!w.busy)
        } }
    }
}

@Composable internal fun CalendarBlocksView(day: LocalDate, events: List<PhoneCalendarEvent>, reminders: List<Reminder>,
    onDay: (LocalDate) -> Unit, onEvent: (PhoneCalendarEvent) -> Unit, onReminder: (Reminder) -> Unit) {
    var week by remember { mutableStateOf(false) }; val zone=ZoneId.systemDefault()
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        TextButton(onClick={ onDay(day.minusDays(if (week) 7 else 1)) }) { Text("Previous") }
        Text(day.toString(),Modifier.padding(top=12.dp),style=MaterialTheme.typography.labelLarge)
        TextButton(onClick={ onDay(day.plusDays(if (week) 7 else 1)) }) { Text("Next") }
    }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        FilterChip(!week,{ week=false },label={ Text("Day") }); FilterChip(week,{ week=true },label={ Text("Week") })
        TextButton(onClick={ onDay(LocalDate.now()) }) { Text("Today") }
    }
    Text("${zone.id} · Calendar events and unfinished reminders",style=MaterialTheme.typography.bodySmall)
    val blocks=events.map { val (a,b)=CalendarTimes.interval(it,zone); CalendarBlock("event:${it.id}:${it.start}",it.title,a,b,allDay=it.allDay) }+
        reminders.filter { it.state=="pending" }.map { CalendarBlock("reminder:${it.id}",it.title,it.dueMs,it.dueMs+900000,true) }
    val dayCount=if (week) 7 else 1
    val allDayLanes=(0 until dayCount).maxOf { offset ->
        val range=CalendarTimes.bounds(day.plusDays(offset.toLong()),zone)
        blocks.count { it.allDay && it.start<=range.last && it.end>range.first }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val dayWidth=maxWidth
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        (0 until dayCount).forEach { offset ->
            val date=day.plusDays(offset.toLong()); val bounds=CalendarTimes.bounds(date,zone)
            Column(Modifier.width(if (week) 164.dp else dayWidth)) {
                Text(date.format(DateTimeFormatter.ofPattern("EEE, MMM d")),style=MaterialTheme.typography.titleSmall)
                Column(Modifier.fillMaxWidth().height((allDayLanes*32).dp)) {
                blocks.filter { it.allDay && it.start<=bounds.last && it.end>bounds.first }.forEach { block ->
                    Surface(Modifier.fillMaxWidth().padding(vertical=2.dp).clickable { events.find { "event:${it.id}:${it.start}"==block.id }?.let(onEvent) },color=MaterialTheme.colorScheme.secondaryContainer,shape=RoundedCornerShape(4.dp)) { Text("All day · ${block.title}",Modifier.padding(6.dp),style=MaterialTheme.typography.labelSmall) }
                }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().height(1056.dp).testTag("calendar-blocks:$date")) {
                    repeat(24) { hour -> Column(Modifier.offset(y=(hour*44).dp).fillMaxWidth()) {
                        HorizontalDivider(); Text("%02d:00".format(hour),style=MaterialTheme.typography.labelSmall)
                    } }
                    calendarBlocks(date,zone,blocks).forEach { placed ->
                        val width=(maxWidth-36.dp)/placed.columns
                        Surface(Modifier.offset(x=36.dp+width*placed.column,y=(placed.topMinute*44f/60).dp)
                            .width(width-2.dp).heightIn(min=24.dp).height((placed.durationMinute*44f/60).dp)
                            .clickable { if (placed.block.reminder) reminders.find { "reminder:${it.id}"==placed.block.id }?.let(onReminder) else events.find { "event:${it.id}:${it.start}"==placed.block.id }?.let(onEvent) },
                            color=if (placed.block.reminder) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(5.dp)) {
                            Text(placed.block.title,Modifier.padding(4.dp),style=MaterialTheme.typography.labelSmall,maxLines=4,overflow=TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable private fun PlanningEditor(kind: String, reminder: Reminder?, routine: Routine?, busy: Boolean, dismiss: () -> Unit,
    save: (String,String,String,String) -> Unit) {
    var title by remember { mutableStateOf(reminder?.title ?: routine?.name.orEmpty()) }
    var timing by remember { mutableStateOf(reminder?.let { OffsetDateTime.ofInstant(Instant.ofEpochMilli(it.dueMs),ZoneId.systemDefault()).toString() } ?: if (kind=="Routine") routine?.scheduleInput ?: "every 1d" else OffsetDateTime.now().plusHours(1).withSecond(0).withNano(0).toString()) }
    var prompt by remember { mutableStateOf(routine?.prompt.orEmpty()) }; var error by remember { mutableStateOf<String?>(null) }
    val operation=remember(title,timing,prompt) { newOperation() }
    PlanningFormDialog(if (reminder!=null || routine!=null) "Edit $kind" else "New $kind",dismiss,form={
        OutlinedTextField(title,{ title=it },label={ Text("Name") },modifier=Modifier.fillMaxWidth(),singleLine=true)
        OutlinedTextField(timing,{ timing=it },label={ Text(if (kind=="Reminder") "Due time (+offset)" else "Hermes schedule") },modifier=Modifier.fillMaxWidth(),singleLine=true)
        if (kind=="Routine") { OutlinedTextField(prompt,{ prompt=it },label={ Text("What Hermes should do") },modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=6); Text("New routines deliver to Hermes local results. Configure advanced destinations through Hermes.",style=MaterialTheme.typography.bodySmall) }
        else Text("Example: 2026-10-12T18:30-07:00. Due does not mean completed.",style=MaterialTheme.typography.bodySmall)
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    },actions={ TextButton(onClick=dismiss) { Text("Cancel") }; TextButton(onClick={ runCatching { require(title.isNotBlank()); if (kind=="Reminder") OffsetDateTime.parse(timing); save(title,timing,prompt,operation) }.onFailure { error="Check the title and time, including its UTC offset." } },enabled=!busy) { Text("Save") } })
}

@Composable private fun CalendarEventEditor(event: PhoneCalendarEvent?, busy: Boolean, dismiss: () -> Unit, save: (CalendarDraft,String) -> Unit) {
    var title by remember { mutableStateOf(event?.title.orEmpty()) }; var allDay by remember { mutableStateOf(event?.allDay ?: false) }
    val zone=runCatching { ZoneId.of(event?.timezone ?: ZoneId.systemDefault().id) }.getOrDefault(ZoneId.systemDefault())
    fun initial(ms: Long?, plus: Long)=if (allDay) (ms?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: LocalDate.now().plusDays(plus)).toString() else (ms?.let { OffsetDateTime.ofInstant(Instant.ofEpochMilli(it),zone) } ?: OffsetDateTime.now().plusHours(plus+1)).withSecond(0).withNano(0).toString()
    var start by remember(allDay) { mutableStateOf(initial(event?.start,0)) }; var end by remember(allDay) { mutableStateOf(initial(event?.end,1)) }
    var repeat by remember { mutableStateOf("None") }; var alert by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }
    val operation=remember(title,start,end,allDay,repeat,alert) { newOperation() }
    PlanningFormDialog(if (event==null) "New calendar event" else "Edit calendar event",dismiss,form={
        OutlinedTextField(title,{ title=it },label={ Text("Title") },modifier=Modifier.fillMaxWidth(),singleLine=true)
        Row { Checkbox(allDay,{ allDay=it }); Text("All day",Modifier.padding(top=12.dp)) }
        OutlinedTextField(start,{ start=it },label={ Text(if (allDay) "Start date" else "Start (+offset)") },modifier=Modifier.fillMaxWidth(),singleLine=true)
        OutlinedTextField(end,{ end=it },label={ Text(if (allDay) "Exclusive end date" else "End (+offset)") },modifier=Modifier.fillMaxWidth(),singleLine=true)
        Text("Timezone: ${zone.id}",style=MaterialTheme.typography.bodySmall)
        if (event==null) {
            FlowRow { listOf("None","Daily","Weekly").forEach { choice -> FilterChip(repeat==choice,{ repeat=choice },label={ Text(choice) }) } }
            if (repeat!="None") Text("Repeats 10 times. Edit the series in your calendar app.",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(alert,{ alert=it },label={ Text("Alert minutes before") },modifier=Modifier.fillMaxWidth(),singleLine=true)
        }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    },actions={ TextButton(onClick=dismiss) { Text("Cancel") }; TextButton(onClick={ runCatching {
        fun parse(value: String)=if (allDay) LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else OffsetDateTime.parse(value).toInstant().toEpochMilli()
        val draft=CalendarDraft(title,parse(start),parse(end),allDay,zone.id,if (repeat=="None") null else "FREQ=${repeat.uppercase()};COUNT=10",alert.takeIf(String::isNotBlank)?.toInt()).validated()
        save(draft,operation)
    }.onFailure { error=it.message ?: "Check the event fields." } },enabled=!busy) { Text("Save on phone") } })
}

/** Reuse the project editor's bounded, keyboard-aware dialog layout on cover screens. */
@Composable private fun PlanningFormDialog(title: String, dismiss: () -> Unit,
    form: @Composable ColumnScope.() -> Unit, actions: @Composable RowScope.() -> Unit) {
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.imePadding().padding(20.dp).widthIn(max=560.dp).fillMaxWidth().fillMaxHeight(.9f),shape=MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(title,style=MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp),content=form)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End,content=actions)
            }
        }
    }
}

private fun newOperation()=UUID.randomUUID().toString().replace("-","")
private fun displayInstant(ms: Long)=Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d, HH:mm z"))
