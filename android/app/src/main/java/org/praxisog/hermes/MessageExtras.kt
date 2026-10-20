package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.*
import org.praxisog.hermes.model.Message

/** A new response segment owns its spacing; leading empty lines must not add blank rows. */
internal fun transcriptText(text: String): String = text.lineSequence().dropWhile { it.isBlank() }.joinToString("\n")

@Composable
internal fun ThinkingBlock(text: String, collapseByDefault: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(!collapseByDefault) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.responseRowEdges(),
        shape = if (LocalInResponseBubble.current) androidx.compose.ui.graphics.RectangleShape else MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth()) {
            CompactDisclosure(if (expanded) "Thinking ▴" else "Thinking ▾") { expanded = !expanded }
            if (expanded) SelectionContainer(Modifier.collapseOnTap { expanded = false }) {
                Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp))
            }
        }
    }
}

/** Shares the response's boundary and separators, including when it is its only row. */
@Composable
internal fun WorkingRow(label: String, reduceMotion: Boolean) {
    val animate = !reduceMotion && android.animation.ValueAnimator.areAnimatorsEnabled()
    val opacity = if (animate) {
        val transition = rememberInfiniteTransition(label = "Working")
        val value by transition.animateFloat(.35f, 1f,
            infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "Activity pulse")
        value
    } else 1f
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().responseRowEdges().testTag("stream-tail").semantics(mergeDescendants = true) {}) {
        Row(Modifier.heightIn(min = 32.dp).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(6.dp).alpha(opacity).background(MaterialTheme.colorScheme.primary,
                androidx.compose.foundation.shape.CircleShape).testTag("working-indicator"))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun CompactDisclosure(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
        .heightIn(min = 32.dp).padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = androidx.compose.ui.Alignment.CenterStart) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** The original message remains the source for copy, selection, quotes and edits. */
@Composable
internal fun UserMessageText(text: String) {
    val (body, notificationContext) = remember(text) { splitPhoneNotificationContext(text) }
    if (notificationContext != null) {
        var expanded by rememberSaveable(notificationContext) { mutableStateOf(false) }
        CompactDisclosure(if (expanded) "Phone notification context ▴" else "Phone notification context ▾",
            Modifier.testTag("phone-context-toggle")) { expanded = !expanded }
        if (expanded) SelectionContainer { Text(notificationContext, style = MaterialTheme.typography.bodySmall) }
    }
    val voice = remember(body) { voicePromptPresentation(body) }
    if (voice == null) Text(body, style = MaterialTheme.typography.bodyLarge)
    else {
        var expanded by rememberSaveable(voice.details) { mutableStateOf(false) }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface, shape = MaterialTheme.shapes.small) {
            Column(Modifier.fillMaxWidth()) {
                CompactDisclosure(if (expanded) "Bellpull voice enabled ▴" else "Bellpull voice enabled ▾",
                    Modifier.testTag("voice-preset-toggle").semantics {
                        contentDescription = "Bellpull voice enabled"
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                    }) { expanded = !expanded }
                if (expanded) SelectionContainer {
                    Text(voice.details, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp))
                }
            }
        }
        if (voice.body.isNotEmpty()) Text(voice.body, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Explicit selection flow while the native text toolbar retains Android's standard actions. */
@Composable
internal fun QuoteDialog(message: Message, onDismiss: () -> Unit, onQuote: (String) -> Unit) {
    val text = message.content.orEmpty()
    var value by remember(message.id) { mutableStateOf(TextFieldValue(text, TextRange(0, text.length))) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Follow up") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Select the part you want to quote, then add your question in the chat.")
            OutlinedTextField(value = value, onValueChange = { value = it }, readOnly = true,
                minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = {
        TextButton(enabled = !value.selection.collapsed, onClick = {
            onQuote(text.substring(value.selection.min, value.selection.max))
        }) { Text("Quote selection") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
internal fun ToolGroup(count: Int, forceExpanded: Boolean = false, content: @Composable () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(forceExpanded) { if (forceExpanded) expanded = true }
    val embedded = LocalInResponseBubble.current
    Surface(shape = if (embedded) androidx.compose.ui.graphics.RectangleShape else MaterialTheme.shapes.small,
        border = if (embedded) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().responseRowEdges()) {
        Column {
            if (count > 4) CompactDisclosure("$count tool calls · " + if (expanded) "Collapse" else "Expand") { expanded = !expanded }
            if (count <= 4 || expanded) content()
        }
    }
}

@Composable
internal fun LiveTranscriptView(parts: List<LivePart>, collapseThinking: Boolean, reduceMotion: Boolean,
    seenRows: List<String>, onRowSeen: (String) -> Unit, prose: @Composable (String) -> Unit) {
    parts.forEach { part ->
        key(part.id) {
            when (part) {
                is LivePart.Prose -> if (part.text.isNotBlank()) {
                    if (part.thinking) ThinkingBlock(part.text, collapseThinking)
                    else prose(part.text)
                }
                is LivePart.Tools -> ToolGroup(part.rows.size) {
                    part.rows.forEachIndexed { index, row ->
                        key(row.id) {
                            val skipMotion = reduceMotion || !android.animation.ValueAnimator.areAnimatorsEnabled()
                            var visible by remember { mutableStateOf(skipMotion || row.id in seenRows) }
                            LaunchedEffect(row.id) { onRowSeen(row.id); visible = true }
                            androidx.compose.animation.AnimatedVisibility(visible,
                                enter = if (skipMotion) androidx.compose.animation.EnterTransition.None else
                                    androidx.compose.animation.expandVertically(expandFrom = androidx.compose.ui.Alignment.Top) +
                                    androidx.compose.animation.slideInVertically { -it / 2 },
                                exit = androidx.compose.animation.ExitTransition.None) {
                                Column {
                                    if (index > 0 && !LocalInResponseBubble.current) HorizontalDivider()
                                    ToolResultView(row.name, rawContent = null, arguments = row.preview,
                                        running = row.running, grouped = true,
                                        statusOverride = if (row.running) "Running…" else "Finished")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
