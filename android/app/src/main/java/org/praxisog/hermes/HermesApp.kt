package org.praxisog.hermes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
    offline: OfflineState? = null,
    onDisconnect: (() -> Unit)? = null,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    apps: List<LaunchableApp> = emptyList(),
    onLaunchApp: (LaunchableApp) -> Unit = {},
    onAskHermes: (String) -> Unit = {},
    prefill: String = "",
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

                // Three panes are shown from 820dp, which is just under the Fold's ~840dp
                // unfolded width — the target device, where all three are wanted.
                //
                // An earlier version gated this at 1000dp, which meant the Fold unfolded to
                // two panes and a toggle. That was the wrong call: it came from three *fixed*
                // panes at 288dp and 248dp leaving the transcript ~260dp, and the fix for a
                // cramped transcript is to narrow its neighbours, not to hide one. At 840dp
                // the list and context now take 240dp and 200dp, leaving ~400dp — wider than
                // the cover display the transcript already renders fine on.
                val roomForThreePanes = maxWidth >= 820.dp

                // Both side panes collapse, so the transcript can take the whole width when
                // you want it to. Keyed on the width so a fold transition re-derives sensible
                // defaults instead of carrying a cover-width choice into the unfolded layout.
                var showList by remember(sideBySide) { mutableStateOf(true) }
                var showContext by remember(roomForThreePanes) { mutableStateOf(roomForThreePanes) }

                if (sideBySide) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        if (showList) {
                            SessionListScreen(
                                state = sessions,
                                now = now,
                                selectedId = openSessionId,
                                onOpen = onOpenSession,
                                onRetry = onRetrySessions,
                                onNewSession = onNewSession,
                                creating = creatingSession,
                                createError = createError,
                                offline = offline,
                                query = query,
                                onQueryChange = onQueryChange,
                                apps = apps,
                                onLaunchApp = onLaunchApp,
                                onAskHermes = onAskHermes,
                                modifier = Modifier.width(240.dp).fillMaxHeight(),
                            )
                            VerticalDivider()
                        }
                        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            // Always present, even with no session open, or hiding the list
                            // would leave no way to bring it back.
                            PaneToggleBar(
                                showList = showList,
                                onToggleList = { showList = !showList },
                                showContext = showContext,
                                onToggleContext = { showContext = !showContext },
                                contextEnabled = openSessionId != null,
                            )
                            if (openSessionId == null) {
                                EmptyDetail()
                            } else {
                                SessionDetailScreen(
                                    history = history,
                                    turn = turn,
                                    onSend = onSend,
                                    onStop = onStop,
                                    onRetry = onRetryHistory,
                                    prefill = prefill,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        if (showContext && openSessionId != null) {
                            VerticalDivider()
                            ContextPanel(
                                session = openSession,
                                now = now,
                                onDisconnect = onDisconnect,
                                modifier = Modifier.width(200.dp).fillMaxHeight(),
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
                            offline = offline,
                            query = query,
                            onQueryChange = onQueryChange,
                            apps = apps,
                            onLaunchApp = onLaunchApp,
                            onAskHermes = onAskHermes,
                        )
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                TextButton(onClick = onCloseSession) { Text("← Sessions") }
                                IconToggle(
                                    onClick = { showContext = !showContext },
                                    active = showContext,
                                    description = if (showContext) "Hide stats" else "Show stats",
                                ) { InfoGlyph(it) }
                            }
                            if (showContext) {
                                // Folded has no room for two panes, so context replaces the
                                // transcript rather than squeezing it.
                                ContextPanel(
                                    session = openSession,
                                    now = now,
                                    onDisconnect = onDisconnect,
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                SessionDetailScreen(
                                    history = history,
                                    turn = turn,
                                    onSend = onSend,
                                    onStop = onStop,
                                    onRetry = onRetryHistory,
                                    prefill = prefill,
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

/**
 * Toggles for the two side panes: sessions on the left, stats on the right.
 *
 * The glyphs are drawn rather than pulled from material-icons, which is not a declared
 * dependency here — three lines and a circled "i" are cheap to draw and render identically in
 * screenshot tests. Each carries a content description, since a Canvas has no text for
 * accessibility services or UI tests to read.
 */
@Composable
private fun PaneToggleBar(
    showList: Boolean,
    onToggleList: () -> Unit,
    showContext: Boolean,
    onToggleContext: () -> Unit,
    contextEnabled: Boolean,
) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            IconToggle(
                onClick = onToggleList,
                active = showList,
                description = if (showList) "Hide sessions" else "Show sessions",
            ) { MenuGlyph(it) }
            IconToggle(
                onClick = onToggleContext,
                active = showContext,
                enabled = contextEnabled,
                description = if (showContext) "Hide stats" else "Show stats",
            ) { InfoGlyph(it) }
        }
    }
}

@Composable
private fun IconToggle(
    onClick: () -> Unit,
    active: Boolean,
    description: String,
    enabled: Boolean = true,
    glyph: @Composable (Color) -> Unit,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = description },
    ) { glyph(tint) }
}

/** Three lines. */
@Composable
private fun MenuGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(width = 20.dp, height = 14.dp)) {
        val stroke = 2.dp.toPx()
        listOf(0f, size.height / 2f, size.height).forEach { y ->
            drawLine(
                color = tint,
                start = Offset(0f, y.coerceIn(stroke / 2f, size.height - stroke / 2f)),
                end = Offset(size.width, y.coerceIn(stroke / 2f, size.height - stroke / 2f)),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** A circled "i". */
@Composable
private fun InfoGlyph(tint: Color) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .border(width = 2.dp, color = tint, shape = CircleShape),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Text(
            "i",
            color = tint,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
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
