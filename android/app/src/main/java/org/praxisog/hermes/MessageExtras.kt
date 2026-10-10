package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.praxisog.hermes.model.Message

/** A new response segment owns its spacing; leading empty lines must not add blank rows. */
internal fun transcriptText(text: String): String = text.lineSequence().dropWhile { it.isBlank() }.joinToString("\n")

@Composable
internal fun ThinkingBlock(text: String, collapseByDefault: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(!collapseByDefault) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth()) {
            CompactDisclosure(if (expanded) "Thinking ▴" else "Thinking ▾") { expanded = !expanded }
            if (expanded) SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp))
            }
        }
    }
}

@Composable
private fun CompactDisclosure(label: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
        .heightIn(min = 32.dp).padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = androidx.compose.ui.Alignment.CenterStart) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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
    Surface(shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column {
            if (count > 4) CompactDisclosure("$count tool calls · " + if (expanded) "Collapse" else "Expand") { expanded = !expanded }
            if (count <= 4 || expanded) content()
        }
    }
}

@Composable
internal fun LiveTranscriptView(parts: List<LivePart>, collapseThinking: Boolean, reduceMotion: Boolean,
    seenRows: List<String>, onRowSeen: (String) -> Unit) {
    parts.forEach { part ->
        key(part.id) {
            when (part) {
                is LivePart.Prose -> if (part.text.isNotBlank()) {
                    if (part.thinking) ThinkingBlock(part.text, collapseThinking)
                    else SelectionContainer { Text(transcriptText(part.text), style = MaterialTheme.typography.bodyLarge) }
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
                                    if (index > 0) HorizontalDivider()
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
