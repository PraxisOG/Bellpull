package org.praxisog.hermes

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalAccessibilityManager
import kotlinx.coroutines.delay

internal class OfflineNotice(val visible: Boolean, private val onDismiss: () -> Unit) {
    fun dismiss() = onDismiss()
}

/** One deadline per outage, shared across both panes. Model/probe updates cannot prolong it. */
@Composable
internal fun rememberOfflineNotice(offline: Boolean): OfflineNotice {
    var dismissed by rememberSaveable(offline) { mutableStateOf(false) }
    val startedAt = rememberSaveable(offline) { android.os.SystemClock.elapsedRealtime() }
    val accessibility = LocalAccessibilityManager.current
    val timeout = accessibility?.calculateRecommendedTimeoutMillis(5000, containsText = true, containsControls = true) ?: 5000L
    LaunchedEffect(offline) {
        if (offline && !dismissed) { delay((timeout - (android.os.SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0)); dismissed = true }
    }
    return OfflineNotice(offline && !dismissed) { dismissed = true }
}
