package org.praxisog.hermes

import android.animation.ValueAnimator
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment

internal fun paneMotionEnabled(reduceMotion: Boolean): Boolean = !reduceMotion && ValueAnimator.areAnimatorsEnabled()

@Composable
internal fun SlidingPane(visible: Boolean, fromLeft: Boolean, animate: Boolean, content: @Composable () -> Unit) {
    if (!animate) {
        if (visible) content()
        return
    }
    val edge = if (fromLeft) Alignment.Start else Alignment.End
    AnimatedVisibility(visible,
        enter = if (animate) expandHorizontally(tween(180), expandFrom = edge) +
            slideInHorizontally(tween(180)) { if (fromLeft) -it else it } else EnterTransition.None,
        exit = if (animate) shrinkHorizontally(tween(180), shrinkTowards = edge) +
            slideOutHorizontally(tween(180)) { if (fromLeft) -it else it } else ExitTransition.None,
    ) { content() }
}
