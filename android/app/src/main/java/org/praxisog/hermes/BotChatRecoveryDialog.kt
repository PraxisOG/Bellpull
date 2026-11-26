package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.praxisog.hermes.model.*

@Composable
internal fun BotChatRecoveryDialog(workspace: PlanningWorkspace, ref: BotChatReference, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val cursors = remember { mutableStateListOf<String>() }
    val before = cursors.lastOrNull()
    var refresh by remember { mutableIntStateOf(0) }
    var selecting by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<ResultSelection?>(null) }
    var sending by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    if (selecting) {
        RoutineResultsDialog(workspace, ref.job, select = { file ->
            selection = ResultSelection(file.stamp, file.revision)
            selecting = false
        }) { selecting = false }
        return
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Review Bot Chat delivery") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            feedback?.let { Text(it) }
            key(before, refresh) {
                val response by produceState<HermesResult<BotChatRecovery>?>(null) {
                    value = workspace.botChatRecovery(ref, before)
                }
                when (val page = response) {
                    null -> CircularProgressIndicator()
                    is HermesResult.Err -> Text(page.error.toUserMessage())
                    is HermesResult.Ok -> {
                        val history = page.value
                        val original = history.original
                        Text(if (original.source == "review") "Original at last review · ${original.status}" else "Original · ${original.label()}")
                        SelectionContainer { Text(original.id, style = MaterialTheme.typography.bodySmall) }
                        history.attempts.forEach { attempt ->
                            val label = when (attempt.admission) {
                                "recorded" -> attempt.receipt().label()
                                "record_missing" -> "Earlier retry receipt is no longer available."
                                else -> "Retry requested. Native admission is unconfirmed."
                            }
                            Text(label)
                            SelectionContainer { Text(attempt.id, style = MaterialTheme.typography.bodySmall) }
                            attempt.selection?.let { Text("Selected result ${it.stamp}", style = MaterialTheme.typography.bodySmall) }
                        }
                        val last = history.attempts.firstOrNull()
                        val status = last?.status ?: original.status
                        val parent = last?.id ?: original.id
                        val eligible = if (last == null) original.source != "review" else last.admission == "recorded"
                        if (before == null && eligible && status in reviewableBotChatStatuses) {
                            val needsFile = last == null && original.degradedNotice
                            if (needsFile) {
                                Text("The original receipt covers a timeout notice. Choose the saved answer for this attempt.")
                                TextButton(enabled = !sending, onClick = { selecting = true }) {
                                    Text(selection?.let { "Change result · ${it.stamp}" } ?: "Choose saved result")
                                }
                            }
                            Text("The previous attempt was $status. Send the saved result to Bot Chat for a new processing attempt? The scheduled job will not run again.")
                            Button(enabled = !sending && (!needsFile || selection != null), onClick = {
                                if (!sending) {
                                    sending = true
                                    scope.launch {
                                        try {
                                            val result = workspace.retryBotChat(ref, parent, status, if (needsFile) selection else null)
                                            feedback = when (result) {
                                                is HermesResult.Ok -> if (result.value.admission == "recorded") "Retry receipt saved." else "Retry admission unconfirmed."
                                                is HermesResult.Err -> result.error.toUserMessage()
                                            }
                                        } finally {
                                            sending = false
                                            refresh++
                                        }
                                    }
                                }
                            }) { Text(if (sending) "Requesting retry…" else "Retry Bot Chat processing") }
                        } else if (before == null && status in setOf("queued", "claimed")) {
                            Text("The current attempt is awaiting a terminal outcome.")
                        }
                        Row {
                            if (before != null) TextButton(enabled = !sending, onClick = { cursors.removeAt(cursors.lastIndex) }) { Text("Newer") }
                            history.next?.let { next -> TextButton(enabled = !sending, onClick = { cursors.add(next) }) { Text("Older") } }
                        }
                    }
                }
            }
        }
    }, dismissButton = { TextButton(enabled = !sending, onClick = { refresh++ }) { Text("Refresh status") } },
        confirmButton = { TextButton(onClick = dismiss) { Text("Close") } })
}
