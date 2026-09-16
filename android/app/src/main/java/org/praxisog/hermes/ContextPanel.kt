package org.praxisog.hermes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
) {
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Context", style = MaterialTheme.typography.titleMedium)

            if (session == null) {
                Text(
                    "Open a session to see its context.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                onDisconnect?.let { DisconnectSection(it) }
                return@Column
            }

            Field("Session", session.id)
            session.model?.let { Field("Model", it) }
            session.source?.let { Field("Source", it) }
            Field("Last active", formatLastActive(session.lastActive ?: session.startedAt, now))
            Field("Messages", groupDigits(session.messageCount.toLong()))
            Field("Tool calls", session.toolCallCount.toString())

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text("Tokens", style = MaterialTheme.typography.labelLarge)
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

/**
 * The rotation path for a lost phone, kept behind the info panel rather than beside Send:
 * it is rare, and it is the one control that discards credentials.
 *
 * Rotating is a two-part job and the wording says so — changing `API_SERVER_KEY` on the host
 * is what actually revokes the lost phone's access. Forgetting the key here only stops *this*
 * device using it.
 */
@Composable
private fun DisconnectSection(onDisconnect: () -> Unit) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    Text("Connection", style = MaterialTheme.typography.labelLarge)
    Text(
        "Forgets the stored key and cached sessions on this device. To revoke a lost phone, " +
            "rotate API_SERVER_KEY on the Hermes host as well.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
        Text("Disconnect")
    }
}

@Composable
private fun Field(label: String, value: String) {
    // This pane is 200dp at the Fold's unfolded width, so a long value such as a model name
    // has to give way. Ellipsised on one line beats breaking a word across two.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}
