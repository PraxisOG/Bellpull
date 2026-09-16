package org.praxisog.hermes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    apps: List<LaunchableApp> = emptyList(),
    onLaunchApp: (LaunchableApp) -> Unit = {},
    onAskHermes: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val searching = query.isNotBlank()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            // An empty Hermes with no way to start a conversation was the gap this closes:
            // createSession existed in the client and nothing in the UI called it.
            ExtendedFloatingActionButton(
                onClick = onNewSession,
                text = { Text(if (creating) "Starting…" else "New chat") },
                icon = { Text("+", style = MaterialTheme.typography.titleLarge) },
            )
        },
    ) { padding ->
      Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
       Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("Search apps and sessions") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
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
                    Text(
                        "No sessions on Hermes yet.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    createError?.let {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                it.toUserMessage(),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.value, key = { it.id }) { session ->
                            SessionRow(
                                session = session,
                                now = now,
                                selected = session.id == selectedId,
                                onClick = { onOpen(session.id) },
                            )
                            HorizontalDivider()
                        }
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
private fun SearchResults(
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
    val matchingApps = searchApps(apps, query)

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
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
            HorizontalDivider()
        }

        if (matchingApps.isNotEmpty()) {
            item { SectionHeader("Apps") }
            items(matchingApps, key = { it.packageName + it.activityName }) { app ->
                Surface(onClick = { onLaunchApp(app) }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        app.label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
                HorizontalDivider()
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
                HorizontalDivider()
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
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun SessionRow(session: Session, now: Double, selected: Boolean, onClick: () -> Unit) {
    val background =
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    Surface(color = background, onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                session.title?.takeIf { it.isNotBlank() } ?: "Untitled session",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
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
            // A single flowing line rather than three boxes: at 240dp the row broke
            // "3 tool calls" across two lines mid-label.
            Text(
                buildList {
                    add(formatLastActive(session.lastActive ?: session.startedAt, now))
                    add(pluralize(session.messageCount, "message"))
                    if (session.toolCallCount > 0) add(pluralize(session.toolCallCount, "tool call"))
                }.joinToString("  ·  "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
