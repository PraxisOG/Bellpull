package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.praxisog.hermes.model.*

@Composable
internal fun RoutineResultsDialog(workspace: PlanningWorkspace, job: String?, select: ((SavedRoutineResult) -> Unit)? = null, dismiss: () -> Unit) {
    var selected by remember { mutableStateOf<SavedRoutineResult?>(null) }
    val cursors = remember { mutableStateListOf<String>() }
    val offsets = remember(selected) { mutableStateListOf(0L) }
    var refresh by remember { mutableIntStateOf(0) }
    val file = selected
    val names = workspace.snapshot?.routines.orEmpty().associate { it.id to it.name }
    val back = { if (file == null) dismiss() else selected = null }
    AlertDialog(onDismissRequest = back,
        title = { Text(if (file != null) names[file.job] ?: file.job else job?.let { names[it] ?: it } ?: "Saved routine results") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                if (file == null) {
                    val cursor = cursors.lastOrNull()
                    key(cursor, refresh) {
                        val result by produceState<HermesResult<SavedRoutineResults>?>(null) {
                            value = workspace.savedResults(cursor, job)
                        }
                        when (val page = result) {
                            null -> CircularProgressIndicator()
                            is HermesResult.Err -> {
                                Text(page.error.toUserMessage())
                                TextButton(onClick = { refresh++ }) { Text("Retry") }
                            }
                            is HermesResult.Ok -> {
                                if (page.value.results.isEmpty()) Text("No saved results on this page.")
                                LazyColumn(Modifier.weight(1f, fill = false)) {
                                    items(page.value.results, key = { it.stamp + "/" + it.job }) { entry ->
                                        TextButton(onClick = { selected = entry }) {
                                            Column(Modifier.fillMaxWidth()) {
                                                Text(names[entry.job] ?: entry.job)
                                                Text(resultTime(entry.stamp), style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                                Row {
                                    if (cursors.isNotEmpty()) TextButton(onClick = { cursors.removeAt(cursors.lastIndex) }) { Text("Newer") }
                                    page.value.next?.let { next -> TextButton(onClick = { cursors.add(next) }) { Text("Older") } }
                                }
                            }
                        }
                    }
                } else {
                    val offset = offsets.last()
                    Text(resultTime(file.stamp), style = MaterialTheme.typography.bodySmall)
                    key(file, offset, refresh) {
                        val result by produceState<HermesResult<RoutineResultPage>?>(null) {
                            value = workspace.savedResult(file, offset)
                        }
                        when (val page = result) {
                            null -> CircularProgressIndicator()
                            is HermesResult.Err -> {
                                Text(page.error.toUserMessage())
                                TextButton(onClick = { refresh++ }) { Text("Retry") }
                            }
                            is HermesResult.Ok -> {
                                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                                    SelectionContainer { Text(page.value.text.ifEmpty { "Empty result." }) }
                                }
                                if (select != null) TextButton(onClick = { select(file) }) { Text("Use this saved result") }
                                Row {
                                    if (offsets.size > 1) TextButton(onClick = { offsets.removeAt(offsets.lastIndex) }) { Text("Previous") }
                                    page.value.next?.let { next -> TextButton(onClick = { offsets.add(next) }) { Text("Next") } }
                                }
                            }
                        }
                    }
                }
            }
        },
        dismissButton = { if (file != null) TextButton(onClick = back) { Text("Back to results") } },
        confirmButton = { TextButton(onClick = dismiss) { Text("Close") } })
}

private fun resultTime(stamp: String) = stamp.substringBefore('_') + " " + stamp.substringAfter('_').replace('-', ':')
