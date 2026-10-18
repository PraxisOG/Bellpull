package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal val LocalLabeledControls = staticCompositionLocalOf { false }

/** One action and focus target in either mode; labels are part of the button. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NavigationAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, description: String = label, onLongClick: (() -> Unit)? = null, icon: @Composable () -> Unit) {
    val actionModifier = modifier.semantics { contentDescription = description }
    if (onLongClick != null) {
        Box(actionModifier.heightIn(min = if (LocalLabeledControls.current) 56.dp else 48.dp).widthIn(min = 48.dp)
            .combinedClickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button,
                onLongClickLabel = "New local chat options", onLongClick = onLongClick, onClick = onClick), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                icon()
                if (LocalLabeledControls.current) Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    } else if (LocalLabeledControls.current) {
        TextButton(onClick, enabled = enabled, modifier = actionModifier.heightIn(min = 56.dp),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Box(Modifier.size(28.dp).graphicsLayer { scaleX = 1.2f; scaleY = 1.2f },
                    contentAlignment = Alignment.Center) { icon() }
                Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    } else IconButton(onClick, enabled = enabled, modifier = actionModifier) { icon() }
}
