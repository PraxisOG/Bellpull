package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.praxisog.hermes.model.*
import java.time.*
import java.time.format.DateTimeFormatter

internal data class TodayWork(val sessionId: String, val title: String, val status: String, val preview: String = "")
internal data class TodaySource(val message: String, val available: Boolean = true, val saved: Boolean = false)
internal data class TodayData(
    val day: LocalDate,
    val events: List<PhoneCalendarEvent>,
    val reminders: List<Reminder>,
    val proposals: List<CalendarProposal>,
    val decisions: List<TodayWork>,
    val running: List<TodayWork>,
    val unread: List<TodayWork>,
    val pendingReminderIds: Set<String>,
)

/** A projection of the profile's existing records. No storage, networking or inference. */
internal fun todayData(
    now: Long, zone: ZoneId, planning: PlanningSnapshot?, delivery: DeliveryState?,
    calendar: PhoneCalendarSnapshot, calendarReadable: Boolean, selectedCalendars: Set<String>, device: String,
    sessions: List<Session>, runs: Map<String, RunPresentation>, receipts: Map<String, ConversationReceipt>,
    legacySessionId: String? = null, legacyTurn: TurnState = TurnState.Idle,
): TodayData {
    val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val bounds = CalendarTimes.bounds(day, zone)
    val effective = delivery?.copy(planning = delivery.planning ?: planning)?.reminders() ?: planning?.reminders.orEmpty()
    val names = sessions.associate { it.id to (it.title?.takeIf(String::isNotBlank) ?: "Conversation ${it.id.take(8)}") }
    fun work(id: String, status: String, preview: String = "") = TodayWork(id, names[id] ?: "Conversation ${id.take(8)}", status, preview)
    val tracked = runs.filterValues { !it.submission.trackingDismissed }
    val decisions = tracked.filterValues {
        (!it.submission.terminal && it.approval != null) || it.submission.status == "review" || it.submission.historyNeedsReview
    }
    val running = tracked.filter { (id, value) -> id !in decisions && value.busy }
        .map { (id, value) -> work(id, if (value.submission.terminal) "Finished work awaiting sync" else value.label ?: "Running") }.toMutableList()
    if (legacySessionId != null && legacySessionId !in runs && legacyTurn is TurnState.Streaming) {
        running += work(legacySessionId, if (legacyTurn.stopRequested) "Stopping" else "Running")
    }
    val replies = delivery?.entries.orEmpty().filter { it.kind == "reply" }.associateBy { it.id }
    val unread = receipts.filterValues { it.unreadReply > it.readThrough }.entries
        .sortedByDescending { (_, value) -> value.unreadReply }
        .map { (id, receipt) -> work(id, "Unread reply", replies["reply:$id:${receipt.unreadReply}"]?.body.orEmpty().take(180)) }
    return TodayData(day,
        calendar.events.filter { event ->
            val (start, end) = CalendarTimes.interval(event, zone)
            calendarReadable && event.calendarKey in selectedCalendars && end > bounds.first && start <= bounds.last
        }.distinctBy { "${it.calendarId}:${it.id}:${it.start}" }.sortedWith(compareBy<PhoneCalendarEvent> { !it.allDay }.thenBy { it.start }),
        effective.filter { it.state == "pending" && it.dueMs <= bounds.last }.sortedWith(compareBy<Reminder> { it.dueMs }.thenBy { it.id }),
        (delivery?.planning ?: planning)?.proposals.orEmpty().filter { it.device == device && it.state == "pending" },
        decisions.map { (id, value) -> work(id, if (!value.submission.terminal && value.approval != null) "Needs approval" else "Needs review") },
        running, unread, delivery?.operations.orEmpty().map { it.reminderId }.toSet())
}

internal fun todayCalendarSource(readable: Boolean, selected: Set<String>, calendar: PhoneCalendarSnapshot,
    problem: String?, loading: Boolean, now: Long): TodaySource = when {
    !readable -> TodaySource("Calendar access is off. Choose Calendar to enable it.", available = false)
    selected.isEmpty() -> TodaySource("Choose calendars to show in the Calendar tab.", available = false)
    calendar.readAt == 0L -> TodaySource(problem ?: if (loading) "Reading phone calendar…" else "Calendar has not been read yet.", available = false)
    else -> TodaySource(buildString {
        if (problem != null || now - calendar.readAt > 5 * 60_000L) append("Saved calendar · ")
        if (problem != null) append("$problem ")
        append("Phone calendar read ${displayInstant(calendar.readAt)}.")
        if (calendar.truncated) append(" Event limit reached; this view may be incomplete.")
    }, available = !calendar.truncated, saved = problem != null || now - calendar.readAt > 5 * 60_000L)
}

@Composable internal fun TodayDashboard(
    data: TodayData, now: Long, calendarSource: TodaySource, reminderSource: TodaySource, workSource: TodaySource,
    onReminder: (Reminder, String) -> Unit, onEvent: (PhoneCalendarEvent) -> Unit,
    onProposal: (CalendarProposal) -> Unit, onOpenSession: (String) -> Unit,
    onCalendar: () -> Unit, onReminders: () -> Unit, onInbox: () -> Unit,
    modifier: Modifier = Modifier, busy: Boolean = false,
) {
    var allEvents by rememberSaveable { mutableStateOf(false) }
    var allReminders by rememberSaveable { mutableStateOf(false) }
    var allWork by rememberSaveable { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val time = DateTimeFormatter.ofPattern("HH:mm")
    LazyColumn(modifier.fillMaxSize().testTag("today-dashboard"), verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 24.dp)) {
        item("date") {
            Text(data.day.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), style = MaterialTheme.typography.titleMedium)
            Text("Calendar, reminders and your agent’s work", style = MaterialTheme.typography.bodySmall)
        }
        item("calendar-heading") { TodayHeading("Today’s calendar", "Calendar", onCalendar); TodaySourceLabel(calendarSource) }
        if (data.events.isEmpty()) item("calendar-empty") {
            if (calendarSource.available) Text(if (calendarSource.saved) "No events in the saved view for today." else "No events today.")
        }
        items(if (allEvents) data.events else data.events.take(3), key = { "event:${it.calendarId}:${it.id}:${it.start}" }) { event ->
            TodayCard {
                Text(event.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (event.allDay) "All day" else {
                    val start = Instant.ofEpochMilli(event.start).atZone(zone)
                    val end = Instant.ofEpochMilli(event.end).atZone(zone)
                    "${if (start.toLocalDate() < data.day) "Started earlier" else start.format(time)} – ${if (end.toLocalDate() > data.day) "continues tomorrow" else end.format(time)}"
                }, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onEvent(event) }, modifier = Modifier.testTag("today-event-${event.id}-${event.start}")) { Text("Open event") }
            }
        }
        if (data.events.size > 3) item("calendar-more") { TextButton(onClick = { allEvents = !allEvents }) { Text(if (allEvents) "Show fewer events" else "Show all ${data.events.size} events") } }
        item("reminders-heading") { TodayHeading("Due reminders", "Reminders", onReminders); TodaySourceLabel(reminderSource) }
        if (data.reminders.isEmpty()) item("reminders-empty") {
            if (reminderSource.available) Text(if (reminderSource.saved) "No due reminders in the saved view." else "No due or overdue reminders.")
        }
        items(if (allReminders) data.reminders else data.reminders.take(3), key = { "reminder:${it.id}" }) { reminder ->
            TodayCard {
                Text(reminder.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${if (reminder.dueMs <= now) "Overdue" else "Due today"} · ${displayInstant(reminder.dueMs)}", style = MaterialTheme.typography.bodySmall)
                val pending = reminder.id in data.pendingReminderIds
                if (pending) Text("Action awaiting sync or review · see Reminders", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onReminder(reminder, "complete") }, enabled = !busy && !pending,
                        modifier = Modifier.testTag("today-complete-${reminder.id}")) { Text("Complete") }
                    TextButton(onClick = { onReminder(reminder, "snooze") }, enabled = !busy && !pending,
                        modifier = Modifier.testTag("today-snooze-${reminder.id}")) { Text("Snooze 1h") }
                }
            }
        }
        if (data.reminders.size > 3) item("reminders-more") { TextButton(onClick = { allReminders = !allReminders }) { Text(if (allReminders) "Show fewer reminders" else "Show all ${data.reminders.size} reminders") } }
        item("work-heading") { TodayHeading("Agent attention & work", "Inbox", onInbox); TodaySourceLabel(workSource) }
        if (data.proposals.isEmpty() && data.decisions.isEmpty()) item("decisions-empty") {
            if (workSource.available && reminderSource.available) Text("No pending decisions in the available records.", style = MaterialTheme.typography.bodySmall)
        }
        items(data.proposals, key = { "proposal:${it.id}" }) { proposal -> TodayCard {
            Text(proposal.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Calendar proposal · ${proposal.action.replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onProposal(proposal) }, modifier = Modifier.testTag("today-proposal-${proposal.id}")) { Text("Review proposal") }
        } }
        items(data.decisions, key = { "decision:${it.sessionId}" }) { TodayWorkCard(it, onOpenSession, "Review in chat") }
        item("running-heading") { Text("Running work", style = MaterialTheme.typography.titleSmall) }
        if (data.running.isEmpty()) item("running-empty") {
            if (workSource.available) Text("No active work in this phone’s records.", style = MaterialTheme.typography.bodySmall)
        }
        items(if (allWork) data.running else data.running.take(3), key = { "running:${it.sessionId}" }) { TodayWorkCard(it, onOpenSession) }
        item("unread-heading") { Text("Unread completions", style = MaterialTheme.typography.titleSmall) }
        if (data.unread.isEmpty()) item("unread-empty") {
            if (workSource.available) Text("No unread replies in this phone’s records.", style = MaterialTheme.typography.bodySmall)
        }
        items(if (allWork) data.unread else data.unread.take(3), key = { "unread:${it.sessionId}" }) { TodayWorkCard(it, onOpenSession) }
        if (data.running.size > 3 || data.unread.size > 3) item("work-more") { TextButton(onClick = { allWork = !allWork }) { Text(if (allWork) "Show less work" else "Show all work and replies") } }
    }
}

@Composable private fun TodayHeading(title: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onAction) { Text(action) }
    }
}
@Composable private fun TodaySourceLabel(source: TodaySource) {
    Text(source.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable private fun TodayCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}
@Composable private fun TodayWorkCard(work: TodayWork, onOpen: (String) -> Unit, action: String = "Open chat") {
    TodayCard {
        Text(work.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(work.status, style = MaterialTheme.typography.labelMedium)
        if (work.preview.isNotBlank()) Text(work.preview, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { onOpen(work.sessionId) }, modifier = Modifier.testTag("today-open-${work.sessionId}-${work.status}")
            .semantics { contentDescription = "$action: ${work.title}" }) { Text(action) }
    }
}
