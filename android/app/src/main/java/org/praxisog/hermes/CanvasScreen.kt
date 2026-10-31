package org.praxisog.hermes

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import org.praxisog.hermes.model.*
import java.util.UUID
import kotlin.math.*

private fun operation(type: String, fields: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject { put("type", type); fields() }

@Composable
internal fun CanvasScreen(workspace: CanvasWorkspace, modifier: Modifier = Modifier, onClose: () -> Unit = {}) {
    val doc = workspace.snapshot?.document
    var selected by remember(doc?.id) { mutableStateOf<List<String>>(emptyList()) }
    var properties by remember(doc?.id) { mutableStateOf(false) }
    var connect by remember(doc?.id) { mutableStateOf(false) }
    var pan by remember { mutableStateOf(false) }
    var zoom by remember(doc?.id) { mutableFloatStateOf(1f) }
    var offset by remember(doc?.id) { mutableStateOf(Offset(30f, 30f)) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var fitted by remember(doc?.id) { mutableStateOf(false) }
    var dragged by remember { mutableStateOf<String?>(null) }
    var delta by remember { mutableStateOf(Offset.Zero) }
    var draggedRevision by remember { mutableLongStateOf(0) }
    var draggedNode by remember { mutableStateOf<CanvasNode?>(null) }
    var localMove by remember(doc?.id) { mutableStateOf<CanvasNode?>(null) }
    var pendingMove by remember(doc?.id) { mutableStateOf(false) }
    val editable = workspace.connected && !workspace.busy
    fun fit() {
        val nodes = doc?.content?.nodes.orEmpty()
        if (nodes.isEmpty() || size.width == 0) return
        val x = nodes.minOf { it.x }; val y = nodes.minOf { it.y }
        val w = nodes.maxOf { it.x + it.width } - x; val h = nodes.maxOf { it.y + it.height } - y
        zoom = min((size.width - 60) / max(w, 1.0), (size.height - 60) / max(h, 1.0)).toFloat().coerceIn(.15f, 3f)
        offset = Offset((size.width - w.toFloat() * zoom) / 2 - x.toFloat() * zoom, (size.height - h.toFloat() * zoom) / 2 - y.toFloat() * zoom)
        fitted = true
    }
    LaunchedEffect(doc?.id, doc?.content?.nodes?.size, size) { if (!fitted) fit() }
    LaunchedEffect(size, properties) { fit() }
    LaunchedEffect(doc?.revision) { selected = selected.filter { id -> doc?.content?.nodes?.any { it.id == id } == true } }
    val nodes = doc?.content?.nodes.orEmpty().map { node ->
        key(node.id) {
            // A local gesture already supplied the motion. Hold its absolute position
            // through the network receipt, then seed future remote animations there.
            val placed = localMove?.takeIf { it.id == node.id && (dragged == node.id || pendingMove || (it.x == node.x && it.y == node.y)) }
            val duration = if (placed == null && android.animation.ValueAnimator.areAnimatorsEnabled()) 430 else 0
            val x by animateFloatAsState((placed ?: node).x.toFloat(), tween(duration), label = "canvas x")
            val y by animateFloatAsState((placed ?: node).y.toFloat(), tween(duration), label = "canvas y")
            val w by animateFloatAsState(node.width.toFloat(), tween(duration), label = "canvas width")
            val h by animateFloatAsState(node.height.toFloat(), tween(duration), label = "canvas height")
            node.copy(x = placed?.x ?: x.toDouble(), y = placed?.y ?: y.toDouble(), width = w.toDouble(), height = h.toDouble())
        }
    }
    val currentNodes by rememberUpdatedState(nodes)
    val currentDoc by rememberUpdatedState(doc)
    val currentZoom by rememberUpdatedState(zoom)
    val currentOffset by rememberUpdatedState(offset)
    val canEdit by rememberUpdatedState(editable)
    val currentPan by rememberUpdatedState(pan)
    val currentConnect by rememberUpdatedState(connect)
    val currentSelected by rememberUpdatedState(selected)
    fun hit(position: Offset): CanvasNode? {
        val p = (position - currentOffset) / currentZoom
        return currentNodes.lastOrNull { p.x >= it.x && p.x <= it.x + it.width && p.y >= it.y && p.y <= it.y + it.height }
    }
    Surface(modifier.testTag("shared-canvas-panel")) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Canvas", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(12.dp))
                Row {
                    TextButton(enabled = !workspace.busy && dragged == null, onClick = { workspace.fullScreen = !workspace.fullScreen }) { Text(if (workspace.fullScreen) "Exit full screen" else "Full screen") }
                    TextButton(onClick = { workspace.fullScreen = false; onClose() }) { Text("Close") }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                var choose by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { choose = true }) { Text(doc?.name ?: "Choose drawing") }
                    DropdownMenu(choose, { choose = false }) {
                        workspace.snapshot?.documents.orEmpty().forEach { item ->
                            DropdownMenuItem(text = { Text(item.name) }, enabled = editable, onClick = { choose = false; workspace.mutate("select", item.id) })
                        }
                    }
                }
                TextButton(enabled = editable, onClick = { workspace.mutate("create", null, buildJsonObject { put("name", "Conversation canvas") }) }) { Text("New") }
                TextButton(enabled = editable && doc?.canUndo == true, onClick = { workspace.mutate("undo") }) { Text("Undo") }
                TextButton(enabled = editable && doc?.canRedo == true, onClick = { workspace.mutate("redo") }) { Text("Redo") }
            }
            Text(if (workspace.connected) "Shared with this conversation · revision ${doc?.revision ?: 0}" else "Cached drawing · reconnect to edit",
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp))
            workspace.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp)) }
            if (doc == null) {
                Text("Create a drawing or ask Hermes to draw in this chat.", modifier = Modifier.padding(16.dp))
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    var add by remember { mutableStateOf(false) }
                    Box {
                        TextButton(enabled = editable, onClick = { add = true }) { Text("Add object") }
                        DropdownMenu(add, { add = false }) {
                            listOf("rectangle", "ellipse", "note", "text").forEach { shape -> DropdownMenuItem(text = { Text(shape) }, onClick = {
                                add = false
                                val node = CanvasNode(UUID.randomUUID().toString(), shape, "New $shape", ((size.width / 2f - offset.x) / zoom).toDouble(),
                                    ((size.height / 2f - offset.y) / zoom).toDouble(), 180.0, 100.0, "sand")
                                workspace.edit(listOf(operation("add-node") { put("node", Json.encodeToJsonElement(node)) }))
                                selected = listOf(node.id); properties = true
                            }) }
                        }
                    }
                    TextButton(onClick = { pan = !pan; connect = false }) { Text(if (pan) "Select" else "Pan") }
                    TextButton(enabled = editable, onClick = { connect = !connect; pan = false; selected = emptyList() }) { Text(if (connect) "Tap two objects" else "Connect") }
                    TextButton(onClick = { fit() }) { Text("Fit") }
                    TextButton(onClick = { zoom = (zoom / 1.25f).coerceAtLeast(.15f) }) { Text("−") }
                    TextButton(onClick = { zoom = (zoom * 1.25f).coerceAtMost(3f) }) { Text("+") }
                    TextButton(enabled = selected.isNotEmpty(), onClick = { properties = !properties }) { Text("Properties") }
                }
                Canvas(Modifier.fillMaxWidth().weight(1f).clipToBounds().onSizeChanged { size = it }.testTag("shared-canvas-scene").semantics { contentDescription = "Drawing canvas" }
                    .pointerInput(doc.id) { detectTapGestures(onTap = { position ->
                        val target = hit(position)
                        if (currentConnect && target != null && currentSelected.size == 1 && currentSelected[0] != target.id && canEdit) {
                            workspace.edit(listOf(operation("add-edge") { put("edge", Json.encodeToJsonElement(CanvasEdge(UUID.randomUUID().toString(), currentSelected[0], target.id, "", true))) }))
                            connect = false; selected = listOf(target.id)
                        } else selected = target?.let { listOf(it.id) }.orEmpty()
                    }, onLongPress = { position -> hit(position)?.let { selected = (currentSelected + it.id).distinct(); properties = true } }) }
                    .pointerInput(doc.id) { detectDragGestures(onDragStart = { position ->
                        draggedNode = if (!currentPan && !currentConnect && canEdit) hit(position) else null
                        dragged = draggedNode?.id; draggedRevision = currentDoc?.revision ?: 0; delta = Offset.Zero
                        localMove = draggedNode
                        dragged?.let { selected = listOf(it) }
                    }, onDrag = { change, amount ->
                        change.consume()
                        if (dragged != null) {
                            delta += amount / currentZoom
                            draggedNode?.let { localMove = it.copy(x = it.x + delta.x, y = it.y + delta.y) }
                        } else offset += amount
                    }, onDragEnd = {
                        val n = draggedNode
                        if (n != null && delta != Offset.Zero) {
                            pendingMove = true
                            workspace.edit(listOf(operation("update-node") {
                                put("id", n.id); put("changes", buildJsonObject { put("x", n.x + delta.x); put("y", n.y + delta.y) })
                            }), draggedRevision) { confirmed -> pendingMove = false; if (!confirmed) localMove = null }
                        }
                        dragged = null; draggedNode = null; delta = Offset.Zero
                    }, onDragCancel = { dragged = null; draggedNode = null; delta = Offset.Zero; localMove = null }) }) {
                    fun position(n: CanvasNode) = Offset(n.x.toFloat(), n.y.toFloat())
                    fun center(n: CanvasNode) = offset + (position(n) + Offset(n.width.toFloat() / 2, n.height.toFloat() / 2)) * zoom
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.DKGRAY; textAlign = android.graphics.Paint.Align.CENTER }
                    doc.content.edges.forEach { edge ->
                        val a = nodes.find { it.id == edge.from }; val b = nodes.find { it.id == edge.to }
                        if (a != null && b != null) {
                            val start = center(a); val end = center(b); val direction = end - start
                            val scale = min((b.width.toFloat() * zoom / 2) / max(abs(direction.x), .001f), (b.height.toFloat() * zoom / 2) / max(abs(direction.y), .001f))
                            val tip = end - direction * scale.coerceAtMost(.9f)
                            drawLine(Color(0xff92877b), start, tip, 2f)
                            if (edge.arrow) {
                                val angle = atan2(direction.y, direction.x)
                                for (side in listOf(-.5, .5)) drawLine(Color(0xff92877b), tip, tip - Offset(cos(angle + side).toFloat(), sin(angle + side).toFloat()) * 10f, 2f)
                            }
                            paint.textSize = max(8f, 12f * zoom)
                            drawContext.canvas.nativeCanvas.drawText(edge.label.take(80), (start.x + tip.x) / 2, (start.y + tip.y) / 2 - 5, paint)
                        }
                    }
                    nodes.forEach { n ->
                        val at = offset + position(n) * zoom; val bounds = Size(n.width.toFloat() * zoom, n.height.toFloat() * zoom)
                        val color = when (n.color) { "rose" -> Color(0xffead8de); "sage" -> Color(0xffe0eadf); "blue" -> Color(0xffdfe8f1); "white" -> Color.White; else -> Color(0xffeee7cc) }
                        if (n.shape != "text") {
                            if (n.shape == "ellipse") drawOval(color, at, bounds) else drawRoundRect(color, at, bounds, androidx.compose.ui.geometry.CornerRadius(8f))
                        }
                        val border = if (n.id in selected) Color(0xff762b42) else Color(0xffa7998b)
                        if (n.shape == "ellipse") drawOval(border, at, bounds, style = Stroke(if (n.id in selected) 3f else 1.5f))
                        else if (n.shape != "text" || n.id in selected) drawRoundRect(border, at, bounds, androidx.compose.ui.geometry.CornerRadius(8f), style = Stroke(if (n.id in selected) 3f else 1.5f))
                        val textPaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.DKGRAY; textSize = 14f * zoom }
                        val layout = android.text.StaticLayout.Builder.obtain(n.label, 0, n.label.length, textPaint, max(1, (bounds.width - 8).toInt()))
                            .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).setMaxLines(max(1, (bounds.height / max(1f, textPaint.textSize * 1.2f)).toInt())).build()
                        drawContext.canvas.nativeCanvas.apply { save(); translate(at.x + 4, at.y + (bounds.height - layout.height) / 2); layout.draw(this); restore() }
                    }
                }
                if (properties && selected.isNotEmpty()) CanvasProperties(doc, selected, editable, workspace)
                Text("Drag to move · long press to select more · Pan to move the view", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

@Composable
private fun CanvasProperties(doc: CanvasDocument, selected: List<String>, editable: Boolean, workspace: CanvasWorkspace) {
    val node = doc.content.nodes.find { it.id == selected.firstOrNull() } ?: return
    var label by remember(node.id, node.label) { mutableStateOf(node.label) }
    var width by remember(node.id, node.width) { mutableStateOf(node.width.toInt().toString()) }
    var height by remember(node.id, node.height) { mutableStateOf(node.height.toInt().toString()) }
    Column(Modifier.padding(horizontal = 8.dp)) {
        OutlinedTextField(label, { label = it.take(500) }, label = { Text("Object text") }, maxLines = 2, modifier = Modifier.fillMaxWidth(), enabled = editable)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(width, { width = it }, label = { Text("Width") }, modifier = Modifier.weight(1f), singleLine = true, enabled = editable)
            OutlinedTextField(height, { height = it }, label = { Text("Height") }, modifier = Modifier.weight(1f), singleLine = true, enabled = editable)
            TextButton(enabled = editable && width.toDoubleOrNull()?.let { it in 40.0..2000.0 } == true && height.toDoubleOrNull()?.let { it in 40.0..2000.0 } == true, onClick = {
                workspace.edit(listOf(operation("update-node") { put("id", node.id); put("changes", buildJsonObject { put("label", label); put("width", width.toDouble()); put("height", height.toDouble()) }) }))
            }) { Text("Save") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("sand", "rose", "sage", "blue", "white").forEach { color -> TextButton(enabled = editable, onClick = {
                workspace.edit(selected.map { id -> operation("update-node") { put("id", id); put("changes", buildJsonObject { put("color", color) }) } })
            }) { Text(color) } }
            TextButton(enabled = editable, onClick = { workspace.edit(selected.mapNotNull { id -> doc.content.nodes.find { it.id == id } }.map { n ->
                operation("add-node") { put("node", Json.encodeToJsonElement(n.copy(id = UUID.randomUUID().toString(), x = n.x + 30, y = n.y + 30))) }
            }) }) { Text("Duplicate") }
            TextButton(enabled = editable, onClick = { workspace.edit(listOf(operation("remove-nodes") { put("ids", JsonArray(selected.map(::JsonPrimitive))) })) }) { Text("Delete") }
        }
    }
}
