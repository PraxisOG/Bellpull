package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun BatchApprovalDialog(
    reviewed: List<TodayWork>,
    current: List<TodayWork>,
    decide: (TodayWork, String, (ApprovalOutcome) -> Unit) -> Unit,
    onClose: () -> Unit,
) {
    val choices = remember { mutableStateMapOf<String, String>() }
    val results = remember { mutableStateMapOf<String, ApprovalOutcome>() }
    var submitted by remember { mutableStateOf(false) }
    val live = current.associateBy { it.sessionId }
    val eligible = reviewed.filter { row ->
        val latest = live[row.sessionId]
        latest != null && !latest.checking && latest.approval == row.approval
    }
    val selected = eligible.filter { choices[it.sessionId] != null }
    // Freeze the reviewed rows and choices at submission. New and replaced requests need a new review.
    AlertDialog(onDismissRequest = onClose, title = { Text(if (submitted) "Decision results" else "Review approvals") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().testTag("approval-batch"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!submitted) item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (choice in listOf("once", "deny")) {
                            val available = eligible.filter { choice in it.approval!!.request.choices }
                            if (available.isNotEmpty()) {
                                val count = if (available.size == eligible.size) "all" else available.size.toString()
                                val label = if (choice == "once") "Set $count to allow once" else "Set $count to deny"
                                TextButton(onClick = {
                                    choices.clear()
                                    available.forEach { choices[it.sessionId] = choice }
                                }) { Text(label) }
                            }
                        }
                    }
                }
                items(reviewed, key = { it.sessionId }) { row ->
                    val request = checkNotNull(row.approval).request
                    val id = row.sessionId
                    Column(Modifier.fillMaxWidth().testTag("review-$id")) {
                        Text(row.title, style = MaterialTheme.typography.titleSmall)
                        request.description?.let { Text(it) }
                        request.command?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        request.editPreview?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (submitted) {
                            val message = when (results[id]) {
                                ApprovalOutcome.ACCEPTED -> if (choices[id] == "once") "Allowed once" else "Denied"
                                ApprovalOutcome.CHANGED -> "Request changed or ended. Decision skipped."
                                ApprovalOutcome.CHECK_FAILED -> "Could not check the request. Decision skipped."
                                ApprovalOutcome.UNCONFIRMED -> "Decision unconfirmed. Check the chat before retrying."
                                null -> if (id in choices) "Checking with Hermes…" else "Skipped"
                            }
                            Text(message, style = MaterialTheme.typography.labelLarge)
                        } else if (row !in eligible) {
                            Text("Request unavailable for this review.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for ((choice, label) in listOf("once" to "Allow once", "deny" to "Deny")) {
                                    if (choice in request.choices) FilterChip(selected = choices[id] == choice,
                                        onClick = { if (choices[id] == choice) choices.remove(id) else choices[id] = choice },
                                        label = { Text(label) }, modifier = Modifier.testTag("review-$id-$choice"))
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            if (submitted) TextButton(onClick = onClose) { Text("Done") }
            else Button(enabled = selected.isNotEmpty(), onClick = {
                if (!submitted) {
                    submitted = true
                    reviewed.forEach { row ->
                        val choice = choices[row.sessionId] ?: return@forEach
                        if (row !in eligible) results[row.sessionId] = ApprovalOutcome.CHANGED
                        else decide(row, choice) { result -> results[row.sessionId] = result }
                    }
                }
            }) { Text("Send decisions (${selected.size})") }
        },
        dismissButton = { if (!submitted) TextButton(onClick = onClose) { Text("Cancel") } })
}
