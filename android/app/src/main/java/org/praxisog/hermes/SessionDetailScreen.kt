package org.praxisog.hermes

import androidx.compose.ui.input.key.*

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.praxisog.hermes.model.Message
import org.praxisog.hermes.model.ToolCall
import kotlinx.coroutines.launch

/**
 * One session: its transcript, the turn in flight, and the composer.
 *
 * Takes data and callbacks only — no client — so every state below is reachable in a
 * screenshot test without a network.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun SessionDetailScreen(
    history: UiState<List<Message>>,
    turn: TurnState,
    onSend: (String) -> Boolean,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    prefill: String = "",
    modifier: Modifier = Modifier,
    navigation: ChatNavigation? = null,
    onDismissNavigation: () -> Unit = {},
    onReadMessage: (Message) -> Unit = {},
    onBranchMessage: (Message) -> Unit = {},
    readingMessageId: Int? = null,
    branchingEnabled: Boolean = false,
    editingEnabled: Boolean = false,
    onForkEdit: (Message, String) -> Unit = { _, _ -> },
    branchingSession: Boolean = false,
    draft: TextFieldValue? = null,
    onDraftChange: (TextFieldValue) -> Unit = {},
    collapseThinking: Boolean = true,
    reduceMotion: Boolean = false,
    denseChatWindow: Boolean = false,
    pendingUserText: String? = null,
    readEnabled: Boolean = true,
    onReadThrough: (Int) -> Unit = {},
    sendBlocked: Boolean = false,
    composerLeading: (@Composable () -> Unit)? = null,
    attachmentStrip: (@Composable () -> Unit)? = null,
    artifacts: List<org.praxisog.hermes.model.Artifact> = emptyList(),
    runSubmission: org.praxisog.hermes.model.RunSubmission? = null,
    onViewArtifact: (org.praxisog.hermes.model.Artifact) -> Unit = {},
    onDownloadArtifact: (org.praxisog.hermes.model.Artifact) -> Unit = {},
    messageStats: Map<Int, MessageInfo> = emptyMap(),
) {
    var localDraft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(prefill)) }
    val composerDraft = draft ?: localDraft
    val changeDraft: (TextFieldValue) -> Unit = { if (draft == null) localDraft = it else onDraftChange(it) }
    val composerFocus = remember { FocusRequester() }
    var editingMessageId by rememberSaveable { mutableStateOf<Int?>(null) }
    var editedText by rememberSaveable { mutableStateOf("") }
    val editingMessage = (history as? UiState.Ready)?.value?.firstOrNull { it.id == editingMessageId }
    if (editingMessage != null) androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!branchingSession) editingMessageId = null },
        title = { Text("Fork & edit") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Create a branch before this prompt. Review your edit in the new chat, then press Send. The original stays intact.")
                OutlinedTextField(value = editedText, onValueChange = { editedText = it },
                    enabled = !branchingSession, minLines = 3, maxLines = 8, label = { Text("Edited prompt") })
            }
        }, confirmButton = {
            TextButton(enabled = !branchingSession && editedText.isNotBlank(),
                onClick = { onForkEdit(editingMessage, editedText) }) { Text(if (branchingSession) "Creating branch…" else "Create edited branch") }
        }, dismissButton = { TextButton(enabled = !branchingSession, onClick = { editingMessageId = null }) { Text("Cancel") } })
    var selectingMessage by remember { mutableStateOf<Message?>(null) }
    var infoMessage by remember { mutableStateOf<Message?>(null) }
    infoMessage?.let { message ->
        val stats = messageInfo(message, (history as? UiState.Ready)?.value.orEmpty(), messageStats)
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { infoMessage = null },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            MessageInfoContent(stats) { infoMessage = null }
        }
    }
    selectingMessage?.let { SelectMessageTextDialog(it) { selectingMessage = null } }
    var quoteMessage by remember { mutableStateOf<Message?>(null) }
    quoteMessage?.let { source ->
        QuoteDialog(source, onDismiss = { quoteMessage = null }, onQuote = { quote ->
            val reference = "From ${source.role} message #${source.id}:\n" + quote.lines().joinToString("\n") { "> $it" }
            val text = composerDraft.text.let { if (it.isBlank()) "" else it + "\n\n" } + reference + "\n\n"
            changeDraft(TextFieldValue(text, TextRange(text.length)))
            quoteMessage = null
            composerFocus.requestFocus()
        })
    }
    var followingTail by rememberSaveable { mutableStateOf(true) }
    var seenToolRows by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var highlightedId by rememberSaveable { mutableStateOf<Int?>(null) }
    var browsingHistory by rememberSaveable { mutableStateOf(false) }
    val bottomTolerance = with(androidx.compose.ui.platform.LocalDensity.current) { 16.dp.roundToPx() }
    // Follow is reader intent, not canScrollForward: arriving content changes the latter.
    val readerScroll = remember(listState, bottomTolerance) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) followingTail = false
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val layout = listState.layoutInfo
                val last = layout.visibleItemsInfo.lastOrNull()
                val nearBottom = !listState.canScrollForward || (last != null &&
                    last.index == layout.totalItemsCount - 1 &&
                    last.offset + last.size + layout.afterContentPadding - layout.viewportEndOffset <= bottomTolerance)
                // At the end the list consumes zero pixels; the rest of the
                // gesture must still restore the reader's intent to follow.
                if ((consumed.y < 0f || available.y < 0f) && nearBottom) {
                    followingTail = true
                    browsingHistory = false
                }
                return Offset.Zero
            }
        }
    }
    val messages = (history as? UiState.Ready)?.value.orEmpty()
    val toolCalls = remember(messages) {
        buildMap {
            messages.flatMap { it.toolCalls.orEmpty() }.forEach { call ->
                listOfNotNull(call.id, call.callId, call.responseItemId).forEach { put(it, call) }
            }
        }
    }
    val callsWithoutResults = remember(messages) {
        val resultIds = messages.filter { it.role == "tool" }.mapNotNull { it.toolCallId }.toSet()
        messages.associate { message -> message.id to message.toolCalls.orEmpty().filter { call ->
            listOfNotNull(call.id, call.callId, call.responseItemId).none { it in resultIds }
        } }
    }
    // A tool-only assistant envelope is represented by its result bars. Keep calls whose
    // results are absent inspectable too, without adding an empty Hermes bubble.
    val visibleMessages = remember(messages, callsWithoutResults) {
        messages.filterNot { it.role == "assistant" && it.content.isNullOrBlank() &&
            callsWithoutResults[it.id].isNullOrEmpty() && it.finishReason != "length" && it.reasoning.isNullOrBlank() && it.reasoningContent.isNullOrBlank() }
    }
    val groups = remember(visibleMessages) { groupTranscript(visibleMessages) }
    val entries = remember(groups, artifacts) { responseBlocks(artifactTranscript(groups, artifacts)) }
    val projection = remember(turn, visibleMessages, runSubmission) { projectLiveHistory(turn, visibleMessages, runSubmission) }
    val liveInLastBubble = pendingUserText == null && entries.lastOrNull()?.let { block ->
        block.assistant && block.messages.any { it.id == projection.appendTo }
    } == true
    val latestReply = messages.lastOrNull { it.role == "assistant" && !it.content.isNullOrBlank() }
    val readCallback by rememberUpdatedState(onReadThrough)
    val mayRead = readEnabled && navigation == null && infoMessage == null && selectingMessage == null && quoteMessage == null && editingMessage == null && turn !is TurnState.Streaming
    LaunchedEffect(listState, latestReply?.id, mayRead, entries) {
        if (mayRead && latestReply != null) {
            val replyIndex = entries.indexOfFirst { entry -> entry.messages.any { it.id == latestReply.id } }
            snapshotFlow {
                replyIndex >= 0 && !listState.canScrollForward && listState.layoutInfo.visibleItemsInfo.any { it.index >= replyIndex }
            }.collect { visible -> if (visible) readCallback(latestReply.id) }
        }
    }
    if (navigation != null) {
        ConversationNavigator(mode = navigation, messages = visibleMessages, onDismiss = onDismissNavigation,
            onJump = { id ->
                val index = entries.indexOfFirst { entry -> entry.messages.any { it.id == id } }
                if (index >= 0) {
                    browsingHistory = true
                    followingTail = false
                    highlightedId = null
                    scope.launch { listState.scrollToItem(index); highlightedId = id }
                }
                onDismissNavigation()
            })
    }
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
                        if (history.value.isEmpty() && artifacts.isEmpty() && turn is TurnState.Idle && pendingUserText == null) {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(28.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                BellpullMark(size = 48.dp)
                                Text("What are we working on?", style = MaterialTheme.typography.titleLarge,
                                    textAlign = TextAlign.Center)
                                Text("Ask a question, explore an idea, or give Hermes a task.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center)
                            }
                        } else {
                            val itemCount = entries.size + (if (pendingUserText == null) 0 else 1) +
                                (if (turn is TurnState.Idle || liveInLastBubble) 0 else 1)
                            LaunchedEffect(entries, turn, pendingUserText) {
                                if (itemCount > 0 && followingTail && !browsingHistory && !listState.isScrollInProgress) {
                                    listState.scrollToItem(itemCount - 1, SCROLL_TO_ITEM_END)
                                }
                            }
                            // Pane slides rewrap earlier rows even if the final row's height is unchanged.
                            // Reader intent survives those resizes, keyboard changes and incoming content.
                            LaunchedEffect(listState) {
                                snapshotFlow {
                                    val info = listState.layoutInfo
                                    listOf(info.totalItemsCount, info.visibleItemsInfo.lastOrNull()?.size ?: 0,
                                        info.viewportSize.width, info.viewportEndOffset,
                                        if (listState.canScrollForward) 1 else 0,
                                        if (listState.isScrollInProgress) 1 else 0)
                                }.collect { layout ->
                                    val count = layout.first()
                                    if (count > 0 && followingTail && !browsingHistory &&
                                        listState.canScrollForward && !listState.isScrollInProgress) {
                                        listState.scrollToItem(count - 1, SCROLL_TO_ITEM_END)
                                    }
                                }
                            }
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize().testTag("transcript").nestedScroll(readerScroll),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(entries, key = { it.key }) { block ->
                                    val renderEntry: @Composable (TranscriptEntry) -> Unit = { entry ->
                                        if (entry.artifact != null) ArtifactRow(entry.artifact, onDownloadArtifact, onViewArtifact)
                                        else {
                                            val group = requireNotNull(entry.group)
                                            val render: @Composable (Message) -> Unit = { message ->
                                                val target = remember { BringIntoViewRequester() }
                                                LaunchedEffect(highlightedId) {
                                                    if (highlightedId == message.id) {
                                                        androidx.compose.runtime.withFrameNanos { }
                                                        target.bringIntoView()
                                                    }
                                                }
                                                Box(Modifier.testTag("message-${message.id}")
                                                    .then(if (block.assistant) Modifier.semantics(mergeDescendants = true) {} else Modifier)
                                                    .bringIntoViewRequester(target).then(
                                                    if (highlightedId == message.id) Modifier.border(2.dp,
                                                        MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium).padding(6.dp)
                                                    else Modifier)) {
                                                    MessageView(message, toolCalls[message.toolCallId], callsWithoutResults[message.id].orEmpty(),
                                                        collapseThinking, onFollowUp = { quoteMessage = it }, groupedTool = group.tools,
                                                        canEdit = editingEnabled && !branchingSession && turn !is TurnState.Streaming,
                                                        onEdit = { editingMessageId = it.id; editedText = it.content.orEmpty() },
                                                        dense = denseChatWindow, onSelectText = { selectingMessage = it })
                                                }
                                            }
                                            if (group.tools) ToolGroup(group.messages.size,
                                                forceExpanded = group.messages.any { it.id == highlightedId }) {
                                                group.messages.forEachIndexed { index, message ->
                                                    if (index > 0 && !LocalInResponseBubble.current) HorizontalDivider()
                                                    androidx.compose.runtime.key(message.id) { render(message) }
                                                }
                                            } else render(group.messages.single())
                                        }
                                    }
                                    if (block.assistant) {
                                        val canonical = block.actionMessage
                                        val liveText = if (liveInLastBubble && block == entries.last()) (projection.turn as? TurnState.Streaming)?.let { live ->
                                            live.parts.filterIsInstance<LivePart.Prose>().filter { !it.thinking }.joinToString("\n\n") { it.text }.ifBlank { live.text }
                                        }.orEmpty() else ""
                                        val message = if (liveText.isNotBlank()) canonical.copy(content = listOf(canonical.content.orEmpty(), liveText)
                                            .filter { it.isNotBlank() }.joinToString("\n\n")) else canonical
                                        val canBranch = branchingEnabled && !branchingSession && turn !is TurnState.Streaming &&
                                            block.messages.any { it.role == "assistant" }
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            if (!denseChatWindow) AssistantHeading()
                                            MessageBubble(message, denseChatWindow, { selectingMessage = it },
                                                reading = readingMessageId == message.id, onRead = onReadMessage,
                                                onBranch = onBranchMessage, canBranch = canBranch,
                                                onInfo = { infoMessage = it },
                                                onFollowUp = { quoteMessage = it }) {
                                                Column {
                                                    block.entries.forEach { entry ->
                                                        androidx.compose.runtime.key(entry.key) { renderEntry(entry) }
                                                    }
                                                    if (liveInLastBubble && block == entries.last()) TurnView(projection.turn,
                                                        onReadMessage, readingMessageId == Int.MIN_VALUE, collapseThinking, reduceMotion,
                                                        seenToolRows, { if (it !in seenToolRows) seenToolRows = seenToolRows + it },
                                                        denseChatWindow, { selectingMessage = it }, embedded = true)
                                                }
                                            }
                                            if (!denseChatWindow && !message.content.isNullOrBlank()) MessageActions(message,
                                                readingMessageId == message.id, onReadMessage, onBranchMessage, canBranch,
                                                { quoteMessage = it }, { selectingMessage = it })
                                        }
                                    } else block.entries.forEach { renderEntry(it) }
                                }
                                if (pendingUserText != null) item(key = "pending-user") {
                                    PendingUserMessage(pendingUserText, denseChatWindow) { selectingMessage = it }
                                }
                                if (turn !is TurnState.Idle && !liveInLastBubble) {
                                    item(key = "live-turn") { TurnView(projection.turn, onReadMessage, readingMessageId == Int.MIN_VALUE, collapseThinking, reduceMotion,
                                        seenToolRows, { if (it !in seenToolRows) seenToolRows = seenToolRows + it },
                                        denseChatWindow, { selectingMessage = it }) }
                                }
                            }
                            if (browsingHistory || listState.canScrollForward) {
                                Surface(modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                                    shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, shadowElevation = 2.dp) {
                                    TextButton(onClick = {
                                        browsingHistory = false
                                        followingTail = true
                                        highlightedId = null
                                        scope.launch { listState.scrollToItem(itemCount - 1, SCROLL_TO_ITEM_END) }
                                    }) { Text("Jump to latest ↓") }
                                }
                            }
                        }
                    }
                }
            }
            if (branchingSession) {
                Text("Creating branch…", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            attachmentStrip?.invoke()
            Composer(turn = turn, onSend = onSend, onStop = onStop, busy = branchingSession, sendBlocked = sendBlocked,
                draft = composerDraft, onDraftChange = changeDraft, focusRequester = composerFocus, leading = composerLeading)
        }
    }
}

@Composable
private fun MessageView(
    message: Message, toolCall: ToolCall?, callsWithoutResults: List<ToolCall>,
    collapseThinking: Boolean, onFollowUp: (Message) -> Unit, groupedTool: Boolean = false,
    canEdit: Boolean = false, onEdit: (Message) -> Unit = {},
    dense: Boolean = false, onSelectText: (Message) -> Unit = {},
) {
    when (message.role) {
        "tool" -> ToolResultView(toolName = message.toolName ?: toolCall?.function?.name,
            rawContent = message.content, arguments = toolCall?.function?.arguments, grouped = groupedTool)
        else -> {
            val isUser = message.role == "user"
            if (isUser) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    MessageBubble(message, dense, onSelectText, onFollowUp = onFollowUp, canEdit = canEdit,
                        onEdit = onEdit, modifier = Modifier.fillMaxWidth(.93f))
                }
                return
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = if (LocalInResponseBubble.current) Arrangement.Top else Arrangement.spacedBy(8.dp),
            ) {
                val reasoning = message.reasoningContent?.takeIf { it.isNotBlank() }
                    ?: message.reasoning?.takeIf { it.isNotBlank() }
                if (reasoning != null) ThinkingBlock(reasoning, collapseThinking)
                if (!message.content.isNullOrBlank()) {
                    if (message.role == "assistant") {
                        MarkdownAnswer(message.content.orEmpty(),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = if (dense) 8.dp else 12.dp))
                    } else {
                        Text(message.role, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SelectionContainer { Text(transcriptText(message.content.orEmpty()), style = MaterialTheme.typography.bodyLarge) }
                    }
                }

                callsWithoutResults.forEach { call ->
                    ToolResultView(toolName = call.function?.name, rawContent = null, arguments = call.function?.arguments)
                }
                // A turn cut short by the provider has a finish_reason worth surfacing.
                message.finishReason?.takeIf { it == "length" }?.let {
                    Text(
                        "Cut off by the model's output limit.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** Local send acknowledged by Hermes, waiting for the canonical history row. */
@Composable
private fun PendingUserMessage(text: String, dense: Boolean, onSelectText: (Message) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        MessageBubble(Message(Int.MIN_VALUE + 1, role = "user", content = text), dense, onSelectText,
            modifier = Modifier.fillMaxWidth(.93f))
    }
}

/** The turn in flight, or how the last one ended. */
@Composable
private fun TurnView(turn: TurnState, onRead: (Message) -> Unit, reading: Boolean, collapseThinking: Boolean, reduceMotion: Boolean,
    seenToolRows: List<String>, onToolRowSeen: (String) -> Unit, dense: Boolean, onSelectText: (Message) -> Unit, embedded: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val text = when (turn) {
        is TurnState.Streaming -> turn.parts.filterIsInstance<LivePart.Prose>().filter { !it.thinking && it.text.isNotBlank() }
            .joinToString("\n\n") { it.text }.ifEmpty { turn.text }
        is TurnState.Finished -> turn.text
        is TurnState.StoppedByUser -> turn.text
        is TurnState.Truncated -> turn.text
        else -> ""
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val prose: @Composable (String) -> Unit = { value ->
            MarkdownAnswer(value,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = if (dense) 8.dp else 12.dp)
                    .semantics(mergeDescendants = true) {})
        }
        if (!embedded && !dense && (text.isNotBlank() || turn is TurnState.Streaming || turn is TurnState.Failed)) AssistantHeading()
        val wrap: @Composable (@Composable () -> Unit) -> Unit = { content ->
            if (embedded) content() else MessageBubble(Message(Int.MIN_VALUE, role = "assistant", content = text), dense,
                onSelectText, reading = reading, onRead = onRead, content = content)
        }
        wrap {
            Column {
                when (turn) {
                    is TurnState.Idle -> Unit

                    is TurnState.Streaming -> {
                        if (turn.parts.isNotEmpty()) LiveTranscriptView(turn.parts, collapseThinking, reduceMotion, seenToolRows, onToolRowSeen, prose)
                        else {
                            if (turn.reasoning.isNotBlank()) ThinkingBlock(turn.reasoning, collapseThinking)
                            if (turn.text.isNotEmpty()) prose(turn.text)
                            turn.runningTool?.let {
                                ToolResultView(toolName = turn.runningToolName, rawContent = it, running = true)
                            }
                        }
                        WorkingRow(if (turn.stopRequested) "Stopping…" else turn.statusLabel, reduceMotion)
                    }

                    // Gate 0d: the server reports a stopped turn as completed, so this label comes
                    // from the client remembering that it asked, not from a flag.
                    is TurnState.StoppedByUser -> {
                        if (turn.text.isNotEmpty()) {
                            prose(turn.text)
                        }
                        Banner("Stopped. This reply is partial.", scheme.tertiaryContainer)
                    }

                    is TurnState.Truncated -> {
                        if (turn.text.isNotEmpty()) {
                            prose(turn.text)
                        }
                        Banner(turn.error.toUserMessage(), scheme.errorContainer)
                    }

                    is TurnState.Finished -> if (turn.text.isNotBlank()) prose(turn.text)

                    is TurnState.Failed -> Banner(turn.error.toUserMessage(), scheme.errorContainer)
                }
            }
        }
        if (!embedded && !dense && turn !is TurnState.Streaming && text.isNotBlank()) {
            MessageActions(Message(id = Int.MIN_VALUE, role = "assistant", content = text),
                reading, onRead, {}, canBranch = false, onSelectText = onSelectText)
        }
    }
}

@Composable
private fun AssistantHeading() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        HermesMark(size = 22.dp)
        Text("Hermes", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Banner(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(color = color,
        shape = if (LocalInResponseBubble.current) androidx.compose.ui.graphics.RectangleShape else MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().responseRowEdges()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun Composer(
    turn: TurnState,
    onSend: (String) -> Boolean,
    onStop: () -> Unit,
    busy: Boolean,
    draft: TextFieldValue,
    onDraftChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    sendBlocked: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    val streaming = turn as? TurnState.Streaming
    var focused by remember { mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun submit() {
        if (!busy && !sendBlocked && draft.text.isNotBlank() && onSend(draft.text)) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = BottomBarPadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    leading?.invoke()
                    BasicTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 5,
                        enabled = !busy,
                        modifier = Modifier.weight(1f).focusRequester(focusRequester)
                            .onPreviewKeyEvent { event ->
                                if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                                    event.key == androidx.compose.ui.input.key.Key.Enter && event.isCtrlPressed) {
                                    submit(); true
                                } else false
                            }
                            .onFocusChanged { focused = it.isFocused }
                            .semantics { contentDescription = "Message Hermes" }
                            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
                            .border(1.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, MaterialTheme.shapes.medium),
                        decorationBox = { field ->
                            Box(Modifier.heightIn(min = BottomControlHeight).padding(horizontal = 12.dp, vertical = 10.dp),
                                contentAlignment = Alignment.CenterStart) {
                                if (draft.text.isEmpty()) Text("Message Hermes", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                field()
                            }
                        },
                    )
                    if (streaming != null) {
                        // Disabled until the first delta: stop before the agent is registered returns
                        // 409 run_not_active (Gate 0d).
                        OutlinedButton(
                            onClick = onStop,
                            enabled = streaming.canStop && !streaming.stopRequested,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.heightIn(min = BottomControlHeight),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        ) { Text("Stop") }
                    } else {
                        Button(
                            onClick = ::submit,
                            enabled = draft.text.isNotBlank() && !busy && !sendBlocked,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.heightIn(min = BottomControlHeight),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        ) { Text("Send") }
                    }
                }
            }
        }
    }
}

/**
 * Pixel offset large enough to land at the end of a streaming message, whatever its length.
 * `scrollToItem` clamps to the item's extent, so this means "the bottom of that item" without
 * needing to measure it.
 */
private const val SCROLL_TO_ITEM_END = 1_000_000
