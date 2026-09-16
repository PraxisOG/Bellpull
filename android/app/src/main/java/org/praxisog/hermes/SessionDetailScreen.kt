package org.praxisog.hermes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.praxisog.hermes.model.Message

/**
 * One session: its transcript, the turn in flight, and the composer.
 *
 * Takes data and callbacks only — no client — so every state below is reachable in a
 * screenshot test without a network.
 */
@Composable
internal fun SessionDetailScreen(
    history: UiState<List<Message>>,
    turn: TurnState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                when (history) {
                    is UiState.Loading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    is UiState.Failed -> Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Could not load this session", style = MaterialTheme.typography.titleMedium)
                        Text(history.error.toUserMessage(), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = onRetry) { Text("Try again") }
                    }

                    is UiState.Ready -> {
                        val listState = rememberLazyListState()
                        val itemCount = history.value.size + if (turn is TurnState.Idle) 0 else 1
                        // Follow the tail as tokens arrive, and when a turn settles, so the
                        // newest text and any error banner are on screen without a manual
                        // scroll. Keyed on the streamed length so every delta nudges it.
                        val streamedLength = when (turn) {
                            is TurnState.Streaming -> turn.text.length
                            is TurnState.StoppedByUser -> turn.text.length
                            is TurnState.Truncated -> turn.text.length
                            is TurnState.Finished -> turn.text.length
                            else -> 0
                        }
                        LaunchedEffect(itemCount, streamedLength, turn::class) {
                            if (itemCount > 0) listState.scrollToItem(itemCount - 1)
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(history.value, key = { it.id }) { MessageView(it) }
                            if (turn !is TurnState.Idle) {
                                item { TurnView(turn) }
                            }
                        }
                    }
                }
            }
            Composer(turn = turn, onSend = onSend, onStop = onStop)
        }
    }
}

@Composable
private fun MessageView(message: Message) {
    when (message.role) {
        "tool" -> ToolResultView(toolName = message.toolName, rawContent = message.content)
        else -> {
            val isUser = message.role == "user"
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    if (isUser) "you" else message.role,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(message.content.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                // A turn cut short by the provider has a finish_reason worth surfacing.
                message.finishReason?.takeIf { it == "length" }?.let {
                    Text(
                        "Cut off by the model's output limit.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** The turn in flight, or how the last one ended. */
@Composable
private fun TurnView(turn: TurnState) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "assistant",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
        when (turn) {
            is TurnState.Idle -> Unit

            is TurnState.Streaming -> {
                if (turn.text.isNotEmpty()) {
                    Text(turn.text, style = MaterialTheme.typography.bodyLarge)
                }
                turn.runningTool?.let {
                    Surface(color = scheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                        Text(
                            "running $it",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                Text(
                    if (turn.stopRequested) "stopping…" else "streaming…",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }

            // Gate 0d: the server reports a stopped turn as completed, so this label comes
            // from the client remembering that it asked, not from a flag.
            is TurnState.StoppedByUser -> {
                if (turn.text.isNotEmpty()) {
                    Text(turn.text, style = MaterialTheme.typography.bodyLarge)
                }
                Banner("Stopped. This reply is partial.", scheme.tertiaryContainer)
            }

            is TurnState.Truncated -> {
                if (turn.text.isNotEmpty()) {
                    Text(turn.text, style = MaterialTheme.typography.bodyLarge)
                }
                Banner(turn.error.toUserMessage(), scheme.errorContainer)
            }

            is TurnState.Finished -> Text(turn.text, style = MaterialTheme.typography.bodyLarge)

            is TurnState.Failed -> Banner(turn.error.toUserMessage(), scheme.errorContainer)
        }
    }
}

@Composable
private fun Banner(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(color = color, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun Composer(turn: TurnState, onSend: (String) -> Unit, onStop: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    val streaming = turn as? TurnState.Streaming
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Message Hermes") },
                enabled = streaming == null,
                modifier = Modifier.weight(1f),
            )
            if (streaming != null) {
                // Disabled until the first delta: stop before the agent is registered returns
                // 409 run_not_active (Gate 0d).
                OutlinedButton(
                    onClick = onStop,
                    enabled = streaming.canStop && !streaming.stopRequested,
                ) { Text("Stop") }
            } else {
                Button(
                    onClick = { onSend(draft); draft = "" },
                    enabled = draft.isNotBlank(),
                ) { Text("Send") }
            }
        }
    }
}
