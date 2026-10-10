package org.praxisog.hermes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.praxisog.hermes.model.Session

/**
 * The session list. Takes data, not a client, so screenshot tests need no network.
 *
 * [selectedId] is highlighted rather than navigated to, because on an unfolded Fold the list
 * and the detail pane are visible at the same time.
 */
@Composable
internal fun SessionListScreen(
    state: UiState<List<Session>>,
    now: Double,
    selectedId: String?,
    onOpen: (String) -> Unit,
    onRetry: () -> Unit,
    onNewSession: () -> Unit,
    creating: Boolean = false,
    createError: org.praxisog.hermes.model.HermesError? = null,
    offline: OfflineState? = null,
    now2: Double = now,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    apps: List<LaunchableApp> = emptyList(),
    onLaunchApp: (LaunchableApp) -> Unit = {},
    onAskHermes: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    showNewChatButton: Boolean = true,
    onExpandedSearch: (() -> Unit)? = null,
    unreadIds: Set<String> = emptySet(),
    completionRevision: Long = 0,
    runLabels: Map<String, String> = emptyMap(),
) {
    val searching = query.isNotBlank()
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (onExpandedSearch == null) OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search apps & chats", style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = { SearchGlyph() },
                shape = MaterialTheme.shapes.medium,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).semantics {
                    contentDescription = "Search apps and sessions"
                },
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onExpandedSearch == null) Box(Modifier.weight(1f)) {
                    SectionHeader(if (searching) "Search results" else "Conversations")
                } else {
                    Text("Conversations", style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                        color = MaterialTheme.colorScheme.onSurface)
                    androidx.compose.material3.IconButton(onClick = onExpandedSearch,
                        modifier = Modifier.semantics { contentDescription = "Search conversations" }) { SearchGlyph() }
                }
                if (showNewChatButton) NewChatButton(onNewSession, creating)
            }
            offline?.let { state ->
                // Names the staleness AND which layer failed, because "offline" alone does not
                // tell the user whether to check their network or the server.
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            "Showing sessions cached " +
                                formatLastActive(state.cachedAtEpochSeconds, now2),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            state.error.toUserMessage(),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            // Keep creation feedback visible in search and on an empty account, too.
            createError?.let {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(it.toUserMessage(), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp))
                }
            }
            if (searching) {
                SearchResults(
                    query = query,
                    sessions = (state as? UiState.Ready)?.value.orEmpty(),
                    apps = apps,
                    now = now,
                    selectedId = selectedId,
                    onOpen = onOpen,
                    onLaunchApp = onLaunchApp,
                    onAskHermes = onAskHermes,
                )
                return@Column
            }
            when (state) {
                is UiState.Loading -> Centered { CircularProgressIndicator() }

                is UiState.Failed -> Centered {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Could not load sessions", style = MaterialTheme.typography.titleMedium)
                        Text(
                            state.error.toUserMessage(),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                        Button(onClick = onRetry) { Text("Try again") }
                    }
                }

                is UiState.Ready -> if (state.value.isEmpty()) {
                    Centered {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            BellpullMark(size = 48.dp)
                            Text("Room for a new idea", style = MaterialTheme.typography.titleMedium)
                            Text("No sessions on Hermes yet.", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    val listState = rememberLazyListState()
                    val ordered = remember(state.value, unreadIds) {
                        state.value.sortedWith(compareByDescending<Session> { it.id in unreadIds }
                            .thenByDescending { it.lastActive ?: it.startedAt ?: 0.0 })
                    }
                    val serverHead = state.value.firstOrNull()?.id
                    var previousHead by rememberSaveable { mutableStateOf<String?>(null) }
                    var previousSelection by rememberSaveable { mutableStateOf<String?>(null) }
                    var previousCompletion by rememberSaveable { mutableStateOf(0L) }
                    var previousUnread by rememberSaveable { mutableStateOf(emptyList<String>()) }
                    LaunchedEffect(selectedId, serverHead, completionRevision, unreadIds) {
                        // Stable item keys otherwise keep the old anchor below a promoted chat.
                        // A promotion wins over keeping the selected (possibly older) chat visible.
                        val promoted = (previousHead != null && previousHead != serverHead) ||
                            previousCompletion != completionRevision || (unreadIds - previousUnread.toSet()).isNotEmpty()
                        val selectedIndex = ordered.indexOfFirst { it.id == selectedId }
                        if (promoted) listState.scrollToItem(0)
                        else if (previousSelection != selectedId && selectedIndex >= 0 &&
                            listState.layoutInfo.visibleItemsInfo.none { it.key == selectedId }) {
                            listState.scrollToItem(selectedIndex)
                        }
                        previousHead = serverHead
                        previousSelection = selectedId
                        previousCompletion = completionRevision
                        previousUnread = unreadIds.toList()
                    }
                    Column(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().testTag("conversations-list"),
                            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(ordered, key = { it.id }) { session ->
                                SessionRow(
                                    session = session,
                                    now = now,
                                    selected = session.id == selectedId,
                                    unread = session.id in unreadIds,
                                    runLabel = runLabels[session.id],
                                    onClick = { onOpen(session.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One field, three kinds of result: sessions whose title or preview matches, installed apps,
 * and Hermes itself as the always-present fallback.
 *
 * The Ask Hermes row is shown for *every* non-empty query, not only when nothing else
 * matched. A query that happens to collide with an app name is still a perfectly good prompt,
 * and hiding the option in that case would make the feature feel arbitrary.
 */
@Composable
internal fun SearchResults(
    query: String,
    sessions: List<Session>,
    apps: List<LaunchableApp>,
    now: Double,
    selectedId: String?,
    onOpen: (String) -> Unit,
    onLaunchApp: (LaunchableApp) -> Unit,
    onAskHermes: (String) -> Unit,
) {
    val q = query.trim().lowercase()
    val matchingSessions = sessions.filter {
        it.title?.lowercase()?.contains(q) == true || it.preview?.lowercase()?.contains(q) == true
    }
    val matchingApps = if (query.isBlank()) emptyList() else searchApps(apps, query)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (query.isNotBlank()) item {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
                onClick = { onAskHermes(query.trim()) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("Ask Hermes", style = MaterialTheme.typography.titleMedium)
                    Text(
                        query.trim(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (matchingApps.isNotEmpty()) {
            item { SectionHeader("Apps") }
            items(matchingApps, key = { it.packageName + it.activityName }) { app ->
                Surface(onClick = { onLaunchApp(app) }, shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(
                        app.label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
            }
        }

        if (matchingSessions.isNotEmpty()) {
            item { SectionHeader("Sessions") }
            items(matchingSessions, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    now = now,
                    selected = session.id == selectedId,
                    onClick = { onOpen(session.id) },
                )
            }
        }

        if (matchingApps.isEmpty() && matchingSessions.isEmpty()) {
            item {
                Text(
                    "No apps or sessions match. Ask Hermes instead.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
    )
}

@Composable
private fun SessionRow(session: Session, now: Double, selected: Boolean, unread: Boolean = false, runLabel: String? = null, onClick: () -> Unit) {
    val background =
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest
    Surface(
        color = background, onClick = onClick, modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                session.title?.takeIf { it.isNotBlank() } ?: "Untitled session",
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            session.preview?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Reserve the dot first; the metadata fits one line even in the narrow drawer.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText(
                    buildList {
                        add(formatLastActive(session.lastActive ?: session.startedAt, now))
                        add(pluralize(session.messageCount, "message"))
                        if (session.toolCallCount > 0) add(pluralize(session.toolCallCount, "tool call"))
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 12.sp, stepSize = .5.sp),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (unread) {
                    val accent = MaterialTheme.colorScheme.primary
                    Canvas(Modifier.size(8.dp).testTag("unread-${session.id}")
                        .semantics { contentDescription = "Unread reply" }) { drawCircle(accent) }
                }
            }
            runLabel?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
internal fun SearchGlyph() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = Modifier.size(18.dp)) {
        val stroke = 1.7.dp.toPx()
        drawCircle(color, radius = size.width * 0.32f,
            center = Offset(size.width * 0.4f, size.height * 0.4f), style = Stroke(stroke))
        drawLine(color, Offset(size.width * 0.65f, size.height * 0.65f),
            Offset(size.width * 0.94f, size.height * 0.94f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
