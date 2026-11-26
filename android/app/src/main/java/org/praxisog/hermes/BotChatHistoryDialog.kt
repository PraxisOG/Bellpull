package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.praxisog.hermes.model.*

@Composable
internal fun BotChatHistoryDialog(workspace: PlanningWorkspace, dismiss: () -> Unit, results: (String) -> Unit,
    review: (BotChatReference) -> Unit) {
    val cursors = remember(workspace) { mutableStateListOf<String>() }
    val before = cursors.lastOrNull()
    var attempt by remember { mutableIntStateOf(0) }
    val names = workspace.snapshot?.routines.orEmpty().associate { it.id to it.name }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Bot Chat delivery history") }, text = {
        key(workspace, before, attempt) {
            val response by produceState<HermesResult<BotChatHistory>?>(null) {
                value = workspace.botChatHistory(before)
            }
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                when (val page = response) {
                    null -> CircularProgressIndicator()
                    is HermesResult.Err -> {
                        Text(page.error.toUserMessage())
                        TextButton(onClick = { attempt++ }) { Text("Retry") }
                    }
                    is HermesResult.Ok -> {
                        if (page.value.unreadable > 0) {
                            Text("Could not read ${page.value.unreadable} receipt records.", color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { attempt++ }) { Text("Retry") }
                        } else if (page.value.deliveries.isEmpty()) Text("No Bot Chat receipts for these executions.")
                        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(page.value.deliveries, key = { it.receipt.id }) { row ->
                                Column {
                                    Text(names[row.job] ?: row.job, style = MaterialTheme.typography.titleSmall)
                                    Text(row.receipt.label())
                                    if (row.receipt.degradedNotice) Text("Notice about a delivery timeout.")
                                    Text("Execution claimed ${row.claimedAt}", style = MaterialTheme.typography.bodySmall)
                                    SelectionContainer { Text("Execution ${row.receipt.executionId}", style = MaterialTheme.typography.bodySmall) }
                                    SelectionContainer { Text("Receipt ${row.receipt.id}", style = MaterialTheme.typography.bodySmall) }
                                    val ref = row.receipt.reference(row.job)
                                    if (workspace.snapshot?.botChatRecoveryAvailable == true && ref != null) {
                                        TextButton(onClick = { review(ref) }) { Text("Review delivery") }
                                    }
                                    if (workspace.snapshot?.savedResultsAvailable == true) {
                                        TextButton(onClick = { results(row.job) }) { Text("View saved results") }
                                    }
                                }
                            }
                        }
                        Row {
                            if (cursors.isNotEmpty()) TextButton(onClick = { cursors.removeAt(cursors.lastIndex) }) { Text("Newer") }
                            page.value.next?.let { next ->
                                TextButton(onClick = { cursors.add(next) }) { Text("Older") }
                            }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Close") } })
}
