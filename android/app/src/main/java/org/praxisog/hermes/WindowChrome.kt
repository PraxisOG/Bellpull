package org.praxisog.hermes

import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Window coordinates, updated by Android when the display, rotation or window changes. */
internal val LocalWindowCutouts = staticCompositionLocalOf<List<Rect>> { emptyList() }
internal val LocalImmersiveHeaders = staticCompositionLocalOf { false }

internal fun configureWindowChrome(window: Window, immersive: Boolean) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.attributes = window.attributes.apply {
        layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    WindowCompat.getInsetsController(window, window.decorView).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (immersive) hide(WindowInsetsCompat.Type.systemBars())
        else show(WindowInsetsCompat.Type.systemBars())
    }
}

/** Reconcile Compose descendants with the final IME geometry once the animation has settled. */
internal fun refreshImeInsetsOnEnd(window: Window) {
    val decor = window.decorView
    ViewCompat.setWindowInsetsAnimationCallback(decor, object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
        override fun onProgress(insets: WindowInsetsCompat, runningAnimations: MutableList<WindowInsetsAnimationCompat>) = insets
        override fun onEnd(animation: WindowInsetsAnimationCompat) {
            if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) decor.post { ViewCompat.requestApplyInsets(decor) }
        }
    })
}
