package org.praxisog.hermes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.praxisog.hermes.model.Message
import org.praxisog.hermes.model.Session

/**
 * The chat shell: session list, session detail, and the read-only context panel.
 *
 * Adaptive rather than optional — targetSdk 37 removed the large-screen orientation opt-out,
 * so the Fold's cover and unfolded widths are both first-class. Unfolded shows list and detail
 * side by side; folded shows one at a time with a back affordance. There is no navigation
 * library: three destinations driven by whether a session is open do not need a backstack.
 */
@Composable
internal fun HermesApp(
    sessions: UiState<List<Session>>,
    history: UiState<List<Message>>,
    turn: TurnState,
    openSessionId: String?,
    openSession: Session?,
    now: Double,
    onOpenSession: (String) -> Unit,
    onCloseSession: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onRetrySessions: () -> Unit,
    onRetryHistory: () -> Unit,
    onNewSession: () -> Unit,
    creatingSession: Boolean = false,
    createError: org.praxisog.hermes.model.HermesError? = null,
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                // The Fold's cover display is ~393dp and unfolded ~840dp. 720dp sits well
                // clear of both, so a fold transition cannot land on the boundary.
                val sideBySide = maxWidth >= 720.dp

                // Three fixed panes at 840dp left the transcript about 260dp — a cramped
                // composer and wrapped labels. Context is therefore collapsed by default
                // until there is room for all three, and always one tap away.
                val roomForThreePanes = maxWidth >= 1000.dp
                var showContext by remember(roomForThreePanes) { mutableStateOf(roomForThreePanes) }

                if (sideBySide) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        SessionListScreen(
                            state = sessions,
                            now = now,
                            selectedId = openSessionId,
                            onOpen = onOpenSession,
                            onRetry = onRetrySessions,
                            onNewSession = onNewSession,
                            creating = creatingSession,
                            createError = createError,
                            modifier = Modifier.width(288.dp).fillMaxHeight(),
                        )
                        VerticalDivider()
                        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            if (openSessionId == null) {
                                EmptyDetail()
                            } else {
                                ContextToggleRow(
                                    showContext = showContext,
                                    onToggle = { showContext = !showContext },
                                )
                                SessionDetailScreen(
                                    history = history,
                                    turn = turn,
                                    onSend = onSend,
                                    onStop = onStop,
                                    onRetry = onRetryHistory,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        if (showContext && openSessionId != null) {
                            VerticalDivider()
                            ContextPanel(
                                session = openSession,
                                now = now,
                                modifier = Modifier.width(248.dp).fillMaxHeight(),
                            )
                        }
                    }
                } else {
                    if (openSessionId == null) {
                        SessionListScreen(
                            state = sessions,
                            now = now,
                            selectedId = null,
                            onOpen = onOpenSession,
                            onRetry = onRetrySessions,
                            onNewSession = onNewSession,
                            creating = creatingSession,
                            createError = createError,
                        )
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                TextButton(onClick = onCloseSession) { Text("← Sessions") }
                                TextButton(onClick = { showContext = !showContext }) {
                                    Text(if (showContext) "Hide context" else "Context")
                                }
                            }
                            if (showContext) {
                                // Folded has no room for two panes, so context replaces the
                                // transcript rather than squeezing it.
                                ContextPanel(
                                    session = openSession,
                                    now = now,
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                SessionDetailScreen(
                                    history = history,
                                    turn = turn,
                                    onSend = onSend,
                                    onStop = onStop,
                                    onRetry = onRetryHistory,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextToggleRow(showContext: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onToggle) {
            Text(if (showContext) "Hide context" else "Context")
        }
    }
}

@Composable
private fun EmptyDetail() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Text("Pick a session", style = MaterialTheme.typography.titleMedium)
    }
}
