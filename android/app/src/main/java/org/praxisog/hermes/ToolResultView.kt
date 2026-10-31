package org.praxisog.hermes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.praxisog.hermes.model.ToolResult
import java.util.Locale

/** Presentation names only; unknown tools retain their original name in the detail dialog. */
internal fun toolDisplayName(name: String?): String = when (name?.lowercase(Locale.ROOT)) {
    null, "" -> "Tool call"
    "web_search" -> "Web search"
    "web_extract", "web_fetch" -> "Read webpage"
    "read_file", "file_read" -> "Read file"
    "write_file", "file_write" -> "Write file"
    "edit_file", "patch_file" -> "Edit file"
    "list_directory", "list_files" -> "Browse files"
    "terminal" -> "Terminal"
    "bellpull_canvas" -> "Drawing"
    else -> name.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
}

/** A compact row; warning/refusal states stay visible without expanding the payload. */
@Composable
internal fun ToolResultView(
    toolName: String?,
    rawContent: String?,
    modifier: Modifier = Modifier,
    arguments: String? = null,
    running: Boolean = false,
    grouped: Boolean = false,
    statusOverride: String? = null,
) {
    val parsed = remember(rawContent, running) { if (running) null else ToolResult.parse(rawContent) }
    var expanded by remember(toolName, rawContent, arguments) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val failed = parsed?.let { it.status == "error" || !it.error.isNullOrBlank() || (it.exitCode != null && it.exitCode != 0) } == true
    val status = statusOverride ?: when {
        running -> "Running…"
        parsed?.isBlocked == true -> "Refused"
        parsed?.wasFlaggedAndAllowed == true && failed -> "Flagged · failed"
        parsed?.wasFlaggedAndAllowed == true -> "Flagged · allowed"
        failed -> "Failed"
        else -> "Details"
    }
    val container = when {
        parsed?.isBlocked == true || failed -> scheme.errorContainer
        parsed?.wasFlaggedAndAllowed == true -> scheme.tertiaryContainer
        else -> scheme.surfaceContainerLow
    }
    val outline = when {
        parsed?.isBlocked == true || failed -> scheme.error
        parsed?.wasFlaggedAndAllowed == true -> scheme.tertiary.copy(alpha = 0.4f)
        else -> scheme.outlineVariant
    }
    val title = toolDisplayName(toolName)
    val embedded = LocalInResponseBubble.current
    Surface(
        color = container,
        shape = if (grouped || embedded) androidx.compose.ui.graphics.RectangleShape else MaterialTheme.shapes.small,
        border = if (grouped || embedded) null else BorderStroke(1.dp, outline),
        modifier = modifier.fillMaxWidth().responseRowEdges().clickable(onClickLabel = "Open $title details") { expanded = true },
    ) {
        Row(
            modifier = Modifier.heightIn(min = 32.dp).padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(">_", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
            Text(title, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, style = MaterialTheme.typography.labelMedium)
            Text("›", style = MaterialTheme.typography.titleMedium)
        }
    }
    if (expanded) {
        ToolDataDialog(title, toolName, rawContent, arguments, status, running) { expanded = false }
    }
}

@Composable
private fun ToolDataDialog(
    title: String,
    toolName: String?,
    rawContent: String?,
    arguments: String?,
    status: String,
    running: Boolean,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(20.dp).widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(0.82f),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(listOfNotNull(toolName, status.takeIf { it != "Details" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                SelectionContainer(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        arguments?.let {
                            Text("Input", style = MaterialTheme.typography.labelLarge)
                            Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider()
                        }
                        Text(if (running) "Live preview" else "Output", style = MaterialTheme.typography.labelLarge)
                        // Keep the complete original payload, including fields unknown to this app.
                        Text(rawContent ?: if (running) "Waiting for tool output." else "No output was returned.",
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider()
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = rawContent != null || arguments != null, onClick = {
                        clipboard.setText(AnnotatedString(
                            listOfNotNull(arguments?.let { "Input\n$it" }, rawContent?.let { "${if (running) "Live preview" else "Output"}\n$it" }).joinToString("\n\n"),
                        ))
                        copied = true
                    }) { Text(if (copied) "Copied" else "Copy data") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}
