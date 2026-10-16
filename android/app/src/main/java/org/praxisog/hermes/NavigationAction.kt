package org.praxisog.hermes

import androidx.compose.foundation.layout.*
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
@Composable
internal fun NavigationAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, description: String = label, icon: @Composable () -> Unit) {
    val actionModifier = modifier.semantics { contentDescription = description }
    if (LocalLabeledControls.current) {
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
