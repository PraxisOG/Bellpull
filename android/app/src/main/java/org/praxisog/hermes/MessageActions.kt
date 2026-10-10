package org.praxisog.hermes

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import org.praxisog.hermes.model.Message

@Composable
internal fun rememberMessageCopy(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    return { text ->
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, "Message copied", Toast.LENGTH_SHORT).show()
    }
}

/** One compact bar; accessible labels remain available for every icon. */
@Composable
internal fun MessageActions(
    message: Message,
    reading: Boolean,
    onRead: (Message) -> Unit,
    onBranch: (Message) -> Unit,
    canBranch: Boolean,
    onFollowUp: ((Message) -> Unit)? = null,
) {
    val copy = rememberMessageCopy()
    val context = LocalContext.current
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row {
                MessageAction("Copy message", "copy", onClick = { copy(message.content.orEmpty()) })
                MessageAction(if (reading) "Stop reading" else "Read message", if (reading) "stop" else "read",
                    onClick = { onRead(message) })
                MessageAction("Branch into new chat", "branch", enabled = canBranch, onClick = { onBranch(message) })
                if (onFollowUp != null) MessageAction("Follow up", "follow", onClick = { onFollowUp(message) })
                MessageAction("Share message", "share", onClick = { ArtifactSharing.text(context, message.content.orEmpty()) })
            }
        }
    }
}

@Composable
private fun MessageAction(label: String, glyph: String, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .38f)
    IconButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.size(width = 40.dp, height = 36.dp).semantics { contentDescription = label }) {
        Canvas(Modifier.size(16.dp)) {
            val w = size.width
            val h = size.height
            val stroke = 1.7.dp.toPx()
            when (glyph) {
                "share" -> {
                    val a = Offset(w * .18f, h * .5f)
                    val b = Offset(w * .8f, h * .16f)
                    val c = Offset(w * .8f, h * .84f)
                    drawLine(tint, a, b, stroke); drawLine(tint, a, c, stroke)
                    listOf(a, b, c).forEach { drawCircle(tint, w * .14f, it) }
                }
                "copy" -> {
                    drawRect(tint, Offset(w * .3f, h * .3f), Size(w * .6f, h * .6f), style = Stroke(stroke))
                    drawPath(Path().apply { moveTo(w * .7f, h * .1f); lineTo(w * .1f, h * .1f)
                        lineTo(w * .1f, h * .7f) }, tint, style = Stroke(stroke))
                }
                "stop" -> drawRect(tint, Offset(w * .15f, h * .15f), Size(w * .7f, h * .7f))
                "read" -> {
                    drawPath(Path().apply { moveTo(0f, h * .35f); lineTo(w * .25f, h * .35f)
                        lineTo(w * .5f, h * .1f); lineTo(w * .5f, h * .9f); lineTo(w * .25f, h * .65f)
                        lineTo(0f, h * .65f); close() }, tint, style = Stroke(stroke))
                    drawArc(tint, -60f, 120f, false, Offset(w * .4f, h * .1f), Size(w * .55f, h * .8f),
                        style = Stroke(stroke, cap = StrokeCap.Round))
                }
                "branch" -> {
                    drawPath(Path().apply { moveTo(w * .25f, h * .9f); lineTo(w * .25f, h * .15f)
                        moveTo(w * .25f, h * .65f); cubicTo(w * .8f, h * .65f, w * .8f, h * .5f, w * .8f, h * .15f)
                        moveTo(w * .6f, h * .35f); lineTo(w * .8f, h * .15f); lineTo(w, h * .35f) },
                        tint, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                "follow" -> {
                    drawPath(Path().apply {
                        moveTo(w * .1f, h * .15f); lineTo(w * .9f, h * .15f)
                        lineTo(w * .9f, h * .7f); lineTo(w * .4f, h * .7f)
                        lineTo(w * .15f, h * .95f); lineTo(w * .15f, h * .7f)
                        lineTo(w * .1f, h * .7f); close()
                    }, tint, style = Stroke(stroke, cap = StrokeCap.Round))
                    drawLine(tint, Offset(w * .3f, h * .4f), Offset(w * .7f, h * .4f), stroke, StrokeCap.Round)
                }
            }
        }
    }
}
