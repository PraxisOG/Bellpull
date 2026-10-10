package org.praxisog.hermes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.praxisog.hermes.model.Session

/**
 * Read-only context for the open session, per `ROADMAP.md`'s own guidance that this is the
 * least load-bearing third of Slice 3 and should be a dump rather than a feature.
 *
 * **Everything here comes from the session object the list endpoint already returns** — model,
 * token and cache accounting, cost, tool-call count, lineage. Nothing new is fetched.
 *
 * What it deliberately does *not* show is Hermes' long-term memory contents. Memory is scoped
 * by the `X-Hermes-Session-Key` header, but no endpoint in `docs/hermes-api.md` reads it back,
 * and inventing one is worse than a blocker. If that panel is wanted, the endpoint has to be
 * verified first.
 */
@Composable
internal fun ContextPanel(
    session: Session?,
    now: Double,
    onDisconnect: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    modelControl: (@Composable () -> Unit)? = null,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxSize()) {
            val closeButton: @Composable () -> Unit = {
                onClose?.let { close ->
                    val tint = MaterialTheme.colorScheme.onSurfaceVariant
                    IconButton(onClick = close, modifier = Modifier.semantics { contentDescription = "Close context" }) {
                        Canvas(Modifier.size(18.dp)) {
                            drawLine(tint, Offset.Zero, Offset(size.width, size.height), 2.dp.toPx(), StrokeCap.Round)
                            drawLine(tint, Offset(size.width, 0f), Offset(0f, size.height), 2.dp.toPx(), StrokeCap.Round)
                        }
                    }
                }
            }
            if (LocalImmersiveHeaders.current) {
                CutoutHeader(title = {
                    Text("Context", style = MaterialTheme.typography.titleLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }, trailing = closeButton)
            } else Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Context", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                closeButton()
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Behind the conversation", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                if (session == null) {
                    Text(
                        "Open a session to see its context.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    onDisconnect?.let { DisconnectSection(it) }
                    return@Column
                }

                // Shortened and truncated from the left: the distinctive tail of a model id is
                // what identifies it, so losing the front costs nothing.
                if (modelControl != null) modelControl() else session.model?.let {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Model", style = MaterialTheme.typography.labelMedium)
                            Text(compactModelName(it), style = MaterialTheme.typography.titleSmall,
                                maxLines = 1, overflow = TextOverflow.StartEllipsis)
                        }
                    }
                }
                Field("Session", session.id)
                session.source?.let { Field("Source", it) }
                Field("Last active", formatLastActive(session.lastActive ?: session.startedAt, now))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metric("Messages", groupDigits(session.messageCount.toLong()), Modifier.weight(1f))
                    Metric("Tool calls", session.toolCallCount.toString(), Modifier.weight(1f))
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("Token usage", style = MaterialTheme.typography.titleSmall)
                Field("In", groupDigits(session.inputTokens))
                Field("Out", groupDigits(session.outputTokens))
                Field("Cache read", groupDigits(session.cacheReadTokens))
                Field("Cache write", groupDigits(session.cacheWriteTokens))
                if (session.reasoningTokens > 0) Field("Reasoning", groupDigits(session.reasoningTokens))
                Field("API calls", session.apiCallCount.toString())

                // Hermes sends actual_cost_usd as null until it knows; estimated is the fallback.
                val cost = session.actualCostUsd ?: session.estimatedCostUsd
                if (cost != null) {
                    // "Cost (estimated)" left no room for the figure and ellipsised it to
                    // "$0.04…". A truncated cost is worse than a terse label.
                    val label = if (session.actualCostUsd != null) "Cost" else "Cost (est.)"
                    Field(label, "$" + String.format("%.4f", cost))
                }

                if (session.hasSystemPrompt || session.hasModelConfig || session.parentSessionId != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    if (session.hasSystemPrompt) Field("System prompt", "set")
                    if (session.hasModelConfig) Field("Model config", "set")
                    session.parentSessionId?.let { Field("Forked from", it) }
                }

                onDisconnect?.let { DisconnectSection(it) }
            }
        }
    }
}

/**
 * Account actions live in Settings, not in a conversation's context panel.
 */
@Composable
private fun DisconnectSection(onDisconnect: () -> Unit) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    Text("Connection", style = MaterialTheme.typography.labelLarge)
    Text(
        "Switch servers, sign out, or remove saved credentials in Settings.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
        Text("Server profiles")
    }
}

@Composable
private fun Field(label: String, value: String, ellipsizeStart: Boolean = false) {
    // This pane is 200dp at the Fold's unfolded width, so a long value such as a model name
    // has to give way. Ellipsised on one line beats breaking a word across two.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = if (ellipsizeStart) TextOverflow.StartEllipsis else TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.small) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
