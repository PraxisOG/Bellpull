package org.praxisog.hermes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.praxisog.hermes.model.Artifact
import java.io.File

private data class PreviewPage(val bitmap: Bitmap, val count: Int)

/** Native image/PDF rendering only: artifacts never receive a WebView or bridge credentials. */
@Composable
internal fun ArtifactPreview(workspace: Phase3Workspace, item: Artifact, modifier: Modifier = Modifier, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember(item.id) { mutableStateOf<File?>(null) }
    var loadError by remember(item.id) { mutableStateOf<String?>(null) }
    var page by remember(item.id) { mutableIntStateOf(0) }
    var rendered by remember(item.id) { mutableStateOf<PreviewPage?>(null) }
    var loading by remember(item.id) { mutableStateOf(true) }
    var zoom by remember(item.id, page) { mutableFloatStateOf(1f) }
    var offset by remember(item.id, page) { mutableStateOf(Offset.Zero) }
    val previewable = item.mime == "application/pdf" || item.mime in setOf("image/png", "image/jpeg", "image/webp")
    LaunchedEffect(workspace, item.id) {
        if (!item.available) { loadError = "This file is no longer available on the server."; loading = false; return@LaunchedEffect }
        file = workspace.download(item)
        if (file == null) loadError = workspace.errors[item.id] ?: "Could not download this file."
        loading = false
    }
    DisposableEffect(item.id) { onDispose { workspace.cancelDownload(item.id) } }
    LaunchedEffect(file, page) {
        val source = file ?: return@LaunchedEffect
        if (!previewable) return@LaunchedEffect
        loading = true
        val result = withContext(Dispatchers.IO) { runCatching { render(source, item.mime, page) } }
        result.fold(onSuccess = { rendered = it; loadError = null }, onFailure = { rendered = null; loadError = "Preview unavailable. You can still open or share the original file." })
        loading = false
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(item.mime)) { uri ->
        val source = file
        if (uri != null && source != null) scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                context.contentResolver.openOutputStream(uri, "w")?.use { output -> source.inputStream().use { it.copyTo(output) } }
                    ?: error("Destination unavailable")
            } }
            if (result.isFailure) loadError = "Could not save the file to that destination."
            else android.widget.Toast.makeText(context, "File saved", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    BackHandler(onBack = onClose)
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            ArtifactPreviewHeader(item.name, onClose)
            HorizontalDivider()
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().pointerInput(item.id, page) {
                detectTransformGestures { _, pan, factor, _ -> zoom = (zoom * factor).coerceIn(1f, 6f); offset = if (zoom == 1f) Offset.Zero else offset + pan }
            }, contentAlignment = Alignment.Center) {
                rendered?.let { content -> Image(content.bitmap.asImageBitmap(), item.name,
                    Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y },
                    contentScale = ContentScale.Fit) }
                if (loading) CircularProgressIndicator()
                if (!previewable && file != null) Text("Open or share this file in a compatible app.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
            }
            loadError?.let { Text(it, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            rendered?.let { content -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (item.mime == "application/pdf") {
                    TextButton(onClick = { page-- }, enabled = page > 0 && !loading) { Text("‹") }
                    Text("${page + 1} / ${content.count}", style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { page++ }, enabled = page + 1 < content.count && !loading) { Text("›") }
                }
                TextButton(onClick = { zoom = 1f; offset = Offset.Zero }) { Text("Reset zoom") }
            } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(enabled = file != null, onClick = { scope.launch { file?.let { ArtifactSharing.file(context, workspace.profileId, item, it, true) } } }) { Text("Open") }
                TextButton(enabled = file != null, onClick = { save.launch(item.name) }) { Text("Save") }
                TextButton(enabled = file != null, onClick = { scope.launch { file?.let { ArtifactSharing.file(context, workspace.profileId, item, it, false) } } }) { Text("Share") }
            }
        }
    }
}

@Composable
internal fun ArtifactPreviewHeader(name: String, onClose: () -> Unit) {
    val title: @Composable () -> Unit = {
        Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val close: @Composable () -> Unit = {
        IconButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = "Close preview" }) {
            Text("×", style = MaterialTheme.typography.titleLarge)
        }
    }
    if (LocalImmersiveHeaders.current) CutoutHeader(title = title, trailing = close)
    else Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { title() }; close()
    }
}

private fun render(file: File, mime: String, pageIndex: Int): PreviewPage {
    if (mime == "application/pdf") {
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
            require(renderer.pageCount > 0)
            renderer.openPage(pageIndex.coerceIn(0, renderer.pageCount - 1)).use { page ->
                val scale = minOf(2f, 2048f / maxOf(page.width, page.height))
                val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1),
                    (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return PreviewPage(bitmap, renderer.pageCount)
            }
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("Unsupported image")
    return PreviewPage(bitmap, 1)
}
