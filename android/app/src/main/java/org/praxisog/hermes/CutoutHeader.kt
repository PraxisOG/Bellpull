package org.praxisog.hermes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor

internal data class HeaderSpan(val start: Int, val end: Int) {
    val width get() = (end - start).coerceAtLeast(0)
}

internal data class HeaderPlacement(
    val leadingX: Int,
    val trailingX: Int,
    val title: HeaderSpan,
    val rowY: Int,
    val height: Int,
)

/** Place whole touch targets in safe intervals; only the title may ellipsize. */
internal fun placeHeader(
    width: Int, rowHeight: Int, padding: Int, gap: Int,
    leadingWidth: Int, trailingWidth: Int, cutouts: List<Rect>,
): HeaderPlacement {
    val start = padding.coerceAtMost(width / 2)
    val end = (width - padding).coerceAtLeast(start)
    val relevant = cutouts.filter { it.bottom > -gap && it.top < rowHeight + gap &&
        it.right > start - gap && it.left < end + gap }
    var spans = listOf(HeaderSpan(start, end))
    for (rect in relevant.sortedBy { it.left }) {
        val left = floor(rect.left).toInt() - gap
        val right = ceil(rect.right).toInt() + gap
        spans = spans.flatMap { span ->
            if (right <= span.start || left >= span.end) listOf(span)
            else listOf(HeaderSpan(span.start, left.coerceIn(span.start, span.end)),
                HeaderSpan(right.coerceIn(span.start, span.end), span.end)).filter { it.width > 0 }
        }
    }
    val leading = if (leadingWidth == 0) start else spans.firstOrNull { it.width >= leadingWidth }?.start
    val trailing = if (trailingWidth == 0) end else spans.lastOrNull { it.width >= trailingWidth }?.let { it.end - trailingWidth }
    val contentStart = leading?.let { it + leadingWidth + if (leadingWidth > 0) gap else 0 }
    val contentEnd = trailing?.let { it - if (trailingWidth > 0) gap else 0 }
    if (leading == null || trailing == null || contentStart!! > contentEnd!!) {
        // Very wide notches or very small windows: keep controls accessible below the cutout.
        val below = relevant.maxOfOrNull { ceil(it.bottom).toInt() + gap }?.coerceAtLeast(0) ?: 0
        val left = start + leadingWidth + if (leadingWidth > 0) gap else 0
        val right = (end - trailingWidth - if (trailingWidth > 0) gap else 0).coerceAtLeast(left)
        return HeaderPlacement(start, end - trailingWidth, HeaderSpan(left, right), below, below + rowHeight)
    }
    val title = spans.map { HeaderSpan(maxOf(it.start, contentStart), minOf(it.end, contentEnd)) }
        .filter { it.width > 0 }.maxByOrNull { it.width } ?: HeaderSpan(contentStart, contentStart)
    val height = maxOf(rowHeight, relevant.maxOfOrNull { ceil(it.bottom).toInt() + gap } ?: 0)
    return HeaderPlacement(leading, trailing, title, 0, height)
}

/** Used only by immersive headers; normal mode retains its existing spacing. */
@Composable
internal fun CutoutHeader(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    val cutouts = LocalWindowCutouts.current
    var origin by remember { mutableStateOf<Offset?>(null) }
    Layout(
        content = { Box { leading() }; Box { title() }; Box { trailing() } },
        modifier = modifier.fillMaxWidth().onGloballyPositioned { origin = it.positionInWindow() },
    ) { children, constraints ->
        val width = constraints.maxWidth
        val loose = Constraints(maxWidth = width, maxHeight = constraints.maxHeight)
        val leadingPlaceable = children[0].measure(loose)
        val trailingPlaceable = children[2].measure(loose)
        val rowHeight = maxOf(64.dp.roundToPx(), leadingPlaceable.height, trailingPlaceable.height)
        val position = origin
        // Until the first placement, prefer a safe row below the camera to an obscured button.
        val localCutouts = if (position == null && cutouts.isNotEmpty()) {
            listOf(Rect(0f, 0f, width.toFloat(), cutouts.maxOf { it.bottom }))
        } else cutouts.map { it.translate(-(position ?: Offset.Zero)) }
        val placement = placeHeader(width, rowHeight, 4.dp.roundToPx(), 6.dp.roundToPx(),
            leadingPlaceable.width, trailingPlaceable.width, localCutouts)
        val titlePlaceable = children[1].measure(Constraints(maxWidth = placement.title.width, maxHeight = rowHeight))
        layout(width, constraints.constrainHeight(placement.height)) {
            leadingPlaceable.place(placement.leadingX, placement.rowY + (rowHeight - leadingPlaceable.height) / 2)
            titlePlaceable.place(placement.title.start, placement.rowY + (rowHeight - titlePlaceable.height) / 2)
            trailingPlaceable.place(placement.trailingX, placement.rowY + (rowHeight - trailingPlaceable.height) / 2)
        }
    }
}
