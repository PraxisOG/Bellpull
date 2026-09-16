package org.praxisog.hermes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.praxisog.hermes.model.ToolResult

/**
 * Renders a tool message. Three states are deliberately visually distinct, because collapsing
 * them is how a user ends up misled:
 *
 * - **refused** — `status: "blocked"`. The server declined to run the command and nothing
 *   executed. Flagged commands that smart approval does not auto-approve fall to
 *   `approvals.unattended_mode`, which defaults to `deny`. This is a decision by Hermes, not
 *   a failure of the app or the network, and must not read like one.
 * - **flagged but allowed** — an `approval` audit note is present. A guard fired and the
 *   command ran anyway. A silently auto-approved destructive command is precisely what we do
 *   not want, so the note is always shown.
 * - **ordinary** — ran, with an exit code.
 */
@Composable
internal fun ToolResultView(
    toolName: String?,
    rawContent: String?,
    modifier: Modifier = Modifier,
) {
    val parsed = ToolResult.parse(rawContent)
    val scheme = MaterialTheme.colorScheme
    val container = when {
        parsed == null -> scheme.surfaceVariant
        parsed.isBlocked -> scheme.errorContainer
        parsed.wasFlaggedAndAllowed -> scheme.tertiaryContainer
        else -> scheme.surfaceVariant
    }

    // A refused command also gets an error-coloured outline and an error-coloured heading.
    // errorContainer and tertiaryContainer are near-identical pinks in the light theme, and
    // these are the two states a user must never confuse, so colour alone is not enough --
    // quite apart from it being the wrong thing to lean on for anyone who can't distinguish
    // the hues at all.
    val outline = if (parsed?.isBlocked == true) BorderStroke(2.dp, scheme.error) else null

    Surface(
        color = container,
        shape = MaterialTheme.shapes.medium,
        border = outline,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                toolName?.let { "tool · $it" } ?: "tool",
                style = MaterialTheme.typography.labelMedium,
            )

            if (parsed == null) {
                // Not a tool-result object. Some tools return prose; show it as text rather
                // than pretending the call failed.
                Text(rawContent.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                return@Column
            }

            if (parsed.isBlocked) {
                Text(
                    "Hermes refused this command",
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.error,
                )
                Text(
                    parsed.error ?: "The command was blocked and did not run.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Nothing was executed. This is a server-side policy decision, not a connection problem.",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }

            parsed.approval?.let {
                Text("Flagged and allowed", style = MaterialTheme.typography.titleSmall)
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }

            parsed.output?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }

            parsed.error?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.error)
            }

            parsed.exitCode?.let {
                Text(
                    if (it == 0) "exited 0" else "exited $it",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
