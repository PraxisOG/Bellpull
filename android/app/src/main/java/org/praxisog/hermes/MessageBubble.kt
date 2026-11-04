package org.praxisog.hermes

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.praxisog.hermes.model.Message

internal val ResponseBubbleShape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
internal val LocalInResponseBubble = staticCompositionLocalOf { false }

/** Adjacent rows draw on the same boundary, so their separators share one stroke.
 * The parent bubble clips drawing of these square rows to its outer corners. */
@Composable
internal fun Modifier.responseRowEdges(): Modifier {
    if (!LocalInResponseBubble.current) return this
    val color = MaterialTheme.colorScheme.outlineVariant
    return drawWithContent {
        drawContent()
        val stroke = 1.dp.toPx()
        drawLine(color, Offset.Zero, Offset(size.width, 0f), stroke)
        drawLine(color, Offset(0f, size.height), Offset(size.width, size.height), stroke)
    }
}

/** Filled user messages and outline-only replies remain distinguishable without names. */
@Composable
internal fun MessageBubble(message: Message, dense: Boolean, onSelectText: (Message) -> Unit, modifier: Modifier = Modifier,
    reading: Boolean = false, onRead: (Message) -> Unit = {}, onBranch: (Message) -> Unit = {},
    canBranch: Boolean = false, onFollowUp: ((Message) -> Unit)? = null,
    canEdit: Boolean = false, onEdit: (Message) -> Unit = {},
    onInfo: ((Message) -> Unit)? = null,
    content: (@Composable () -> Unit)? = null) {
    val user = message.role == "user"
    // Freeze the selected text while streaming continues behind the menu.
    var selected by remember(message.id) { mutableStateOf<Message?>(null) }
    Box(modifier.testTag("bubble-${message.id}")) {
        if (!user) {
            // A whole response can exceed the graphics-layer outline's hit-test bounds.
            // Clip drawing only; keep disclosure hit testing in ordinary layout coordinates.
            Column(Modifier.fillMaxWidth().messageLongPress { selected = message }
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, ResponseBubbleShape)
                .drawWithCache {
                    val path = Path().apply { addOutline(ResponseBubbleShape.createOutline(size, layoutDirection, this@drawWithCache)) }
                    onDrawWithContent { clipPath(path) { this@onDrawWithContent.drawContent() } }
                }.semantics { contentDescription = "Hermes response" }) {
                CompositionLocalProvider(LocalInResponseBubble provides true,
                    LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    if (content != null) content()
                    else Text(transcriptText(message.content.orEmpty()), style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = if (dense) 8.dp else 12.dp))
                }
            }
        } else {
            Surface(color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer, shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().messageLongPress { selected = message }
                    .semantics { contentDescription = "Your message" }) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = if (dense) 8.dp else 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!dense) Text("You", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    if (content != null) content() else UserMessageText(message.content.orEmpty())
                    if (!dense && onFollowUp != null) Row {
                        TextButton(onClick = { onFollowUp(message) }) { Text("Follow up") }
                        if (canEdit) TextButton(onClick = { onEdit(message) }) { Text("Fork & edit") }
                    }
                }
            }
        }
        DropdownMenu(expanded = selected != null, onDismissRequest = { selected = null },
            modifier = Modifier.testTag("message-action-popup")) {
            selected?.let { target ->
                if (user) UserMessageActions(target, onSelectText, onFollowUp, onEdit, canEdit, { selected = null })
                else MessageActions(target, reading, onRead, onBranch, canBranch, onFollowUp,
                    onSelectText, onInfo = onInfo?.let { info -> { info(target) } }, onAction = { selected = null })
            }
        }
    }
}
