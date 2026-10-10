package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.praxisog.hermes.model.Message

/** Read-only native selection, separate from the bubble's long-press action menu. */
@Composable
internal fun SelectMessageTextDialog(message: Message, onDismiss: () -> Unit) {
    val text = message.content.orEmpty()
    var value by remember(message) { mutableStateOf(TextFieldValue(text, TextRange(0, text.length))) }
    val focus = remember { FocusRequester() }
    val copy = rememberMessageCopy()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(20.dp).widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(.82f),
            shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Select text", style = MaterialTheme.typography.titleLarge)
                Text("Drag the selection handles to choose part of the message.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = value, onValueChange = { value = value.copy(selection = it.selection) },
                    readOnly = true, textStyle = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).fillMaxWidth().focusRequester(focus).testTag("selected-message-text"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = !value.selection.collapsed, onClick = {
                        copy(text.substring(value.selection.min, value.selection.max)); onDismiss()
                    }) { Text("Copy selection") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
        LaunchedEffect(message.id) { focus.requestFocus() }
    }
}
