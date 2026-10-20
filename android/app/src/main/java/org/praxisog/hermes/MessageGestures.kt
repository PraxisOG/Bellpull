package org.praxisog.hermes

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/** Observe after children. Never claim a tap intended for a disclosure or selection. */
@Composable
internal fun Modifier.messageLongPress(onAction: () -> Unit): Modifier {
    val action by rememberUpdatedState(onAction)
    return semantics { onLongClick("Message actions") { action(); true } }.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(pass = PointerEventPass.Final)
            val finished = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                waitForUpOrCancellation(pass = PointerEventPass.Final)
                true
            }
            if (finished == null) {
                action()
                currentEvent.changes.forEach { it.consume() }
                waitForUpOrCancellation(pass = PointerEventPass.Final)
            }
        }
    }
}

/** A short, stationary, unclaimed tap collapses; selection and scrolling keep priority. */
@Composable
internal fun Modifier.collapseOnTap(onCollapse: () -> Unit): Modifier {
    val collapse by rememberUpdatedState(onCollapse)
    return semantics { onClick("Collapse thinking") { collapse(); true } }.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
            var cancelled = down.isConsumed
            // The selectable child has seen Main already. Shield this region from
            // the enclosing bubble's long-press observer on Final.
            down.consume()
            awaitPointerEvent(PointerEventPass.Final) // Finish our own consumed down.
            do {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                cancelled = cancelled || pointer.isConsumed || event.changes.size != 1 ||
                    (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                    pointer.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
                if (!pointer.pressed && !cancelled) {
                    collapse()
                    pointer.consume()
                }
            } while (event.changes.any { it.pressed })
        }
    }
}
