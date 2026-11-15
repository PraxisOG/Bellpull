package org.praxisog.hermes

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/** One window/inset policy for mobile dialogs, including the keyboard's hide animation. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun BellpullDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    val immersive = LocalImmersiveHeaders.current
    Dialog(onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view, immersive) {
            val window = (view.parent as DialogWindowProvider).window
            configureWindowChrome(window, immersive)
            onDispose { }
        }
        // A fixed window avoids wrap-content/IME feedback. Reserve bar space even when
        // hidden so immersive mode changes chrome, not editor geometry. Insets are
        // consumed here once; forms measure in the remaining area and keep buttons visible.
        Box(Modifier.fillMaxSize()) {
            Spacer(Modifier.matchParentSize().pointerInput(onDismissRequest) {
                detectTapGestures { onDismissRequest() }
            })
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout).union(WindowInsets.ime)),
                contentAlignment = Alignment.Center) {
                // In landscape a tall keyboard can leave less room than even the
                // title and buttons. Keep a usable form and let the entire dialog
                // scroll in that case, instead of measuring its footer to zero.
                val compact = maxHeight < 320.dp
                val scroll = rememberScrollState()
                val keyboardVisible = WindowInsets.isImeVisible
                LaunchedEffect(keyboardVisible) { if (!keyboardVisible) scroll.scrollTo(0) }
                Box(if (compact) Modifier.verticalScroll(scroll).testTag("dialog-compact-scroll") else Modifier) {
                    Box(Modifier.then(if (compact) Modifier.height(320.dp) else Modifier)
                        .testTag("bellpull-dialog-content").pointerInput(Unit) {
                            // Blank space within the form must not hit the outside-dismiss layer.
                            detectTapGestures { }
                        }) { content() }
                }
            }
        }
    }
}

/** Compact text-entry alerts use the same window as the larger editor dialogs. */
@Composable
internal fun BellpullTextDialog(onDismissRequest: () -> Unit, title: @Composable () -> Unit,
    text: @Composable () -> Unit, confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit) {
    BellpullDialog(onDismissRequest) {
        Surface(Modifier.padding(24.dp).widthIn(max = 560.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.headlineSmall, title)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { text() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    dismissButton()
                    confirmButton()
                }
            }
        }
    }
}
