package org.praxisog.hermes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.praxisog.hermes.model.Artifact

/** Shares the compact outline, type scale and surface of a tool result. */
@Composable
internal fun ArtifactRow(item: Artifact, onDownload: (Artifact) -> Unit, onView: (Artifact) -> Unit) {
    val embedded = LocalInResponseBubble.current
    Surface(Modifier.fillMaxWidth().testTag("artifact-${item.id}").semantics(mergeDescendants = true) {}.responseRowEdges(),
        shape = if (embedded) androidx.compose.ui.graphics.RectangleShape else MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (embedded) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Row(Modifier.heightIn(min = 36.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ArtifactIcon(item.mime.startsWith("image/"))
                // Middle ellipsis preserves the extension, even in a narrow chat pane.
                Text(item.name, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                if (!item.available) Text("Missing", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { onDownload(item) }, enabled = item.available,
                    modifier = Modifier.heightIn(min = 36.dp).semantics { contentDescription = "Download ${item.name}" },
                    contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Download", style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = { onView(item) },
                    modifier = Modifier.heightIn(min = 36.dp).semantics { contentDescription = "View ${item.name}" },
                    contentPadding = PaddingValues(horizontal = 4.dp)) { Text("View", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
private fun ArtifactIcon(picture: Boolean) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(18.dp).semantics { contentDescription = if (picture) "Image" else "File folder" }) {
        val stroke = Stroke(1.3.dp.toPx())
        if (picture) {
            drawRoundRect(color, Offset(size.width * .08f, size.height * .14f),
                Size(size.width * .84f, size.height * .72f), style = stroke)
            drawCircle(color, size.width * .07f, Offset(size.width * .68f, size.height * .35f))
            drawPath(Path().apply {
                moveTo(size.width * .12f, size.height * .72f); lineTo(size.width * .38f, size.height * .44f)
                lineTo(size.width * .60f, size.height * .67f); lineTo(size.width * .73f, size.height * .54f)
                lineTo(size.width * .88f, size.height * .72f)
            }, color, style = stroke)
        } else drawPath(Path().apply {
            moveTo(size.width * .08f, size.height * .82f); lineTo(size.width * .08f, size.height * .20f)
            lineTo(size.width * .40f, size.height * .20f); lineTo(size.width * .51f, size.height * .33f)
            lineTo(size.width * .92f, size.height * .33f); lineTo(size.width * .92f, size.height * .82f); close()
        }, color, style = stroke)
    }
}
