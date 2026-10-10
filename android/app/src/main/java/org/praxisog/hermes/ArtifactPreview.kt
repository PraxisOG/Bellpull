package org.praxisog.hermes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
internal fun ArtifactPreview(workspace: Phase3Workspace, item: Artifact, modifier: Modifier = Modifier, onDock: (() -> Unit)? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember(item.id) { mutableStateOf<File?>(null) }
    var loadError by remember(item.id) { mutableStateOf<String?>(null) }
    var page by remember(item.id) { mutableIntStateOf(0) }
    var rendered by remember(item.id) { mutableStateOf<PreviewPage?>(null) }
    var loading by remember(item.id) { mutableStateOf(true) }
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
            if (!saveArtifact(context, source, uri)) loadError = "Could not save the file to that destination."
            else android.widget.Toast.makeText(context, "File saved", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    BackHandler(onBack = onClose)
    ArtifactPreviewContent(item, rendered?.bitmap?.asImageBitmap(), page, rendered?.count ?: 0,
        loading, loadError, file != null, modifier,
        onPage = { page = it }, onOpen = { scope.launch { file?.let { ArtifactSharing.file(context, workspace.profileId, item, it, true) } } },
        onSave = { save.launch(item.name) },
        onShare = { scope.launch { file?.let { ArtifactSharing.file(context, workspace.profileId, item, it, false) } } },
        onDock = onDock, onClose = onClose)
}

/** Same rounded, generously sized bubble as tool details; wide screens still default here. */
@Composable
internal fun ArtifactPreviewDialog(onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalImmersiveHeaders provides false) {
            Surface(Modifier.padding(20.dp).widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(0.82f)
                .testTag("artifact-preview-dialog"), shape = MaterialTheme.shapes.large) { content() }
        }
    }
}

@Composable
internal fun ArtifactPreviewContent(item: Artifact, bitmap: ImageBitmap?, page: Int, pageCount: Int,
    loading: Boolean, error: String?, ready: Boolean, modifier: Modifier = Modifier,
    onPage: (Int) -> Unit, onOpen: () -> Unit, onSave: () -> Unit, onShare: () -> Unit,
    onDock: (() -> Unit)?, onClose: () -> Unit) {
    var zoom by rememberSaveable(item.id, page) { mutableFloatStateOf(1f) }
    var scrolling by rememberSaveable(item.id) { mutableStateOf(true) }
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val scope = rememberCoroutineScope()
    fun reset() { zoom = 1f; scope.launch { vertical.scrollTo(0); horizontal.scrollTo(0) } }
    LaunchedEffect(item.id, page) { vertical.scrollTo(0); horizontal.scrollTo(0) }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            ArtifactPreviewHeader(item.name, onClose)
            HorizontalDivider()
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.Center) {
                bitmap?.let { image ->
                    val ratio = image.height.toFloat() / image.width
                    val baseWidth = if (scrolling) maxWidth else minOf(maxWidth, maxHeight / ratio)
                    val transform = rememberTransformableState { factor, pan, _ ->
                        zoom = (zoom * factor).coerceIn(1f, 6f)
                        horizontal.dispatchRawDelta(-pan.x); vertical.dispatchRawDelta(-pan.y)
                    }
                    Box(Modifier.fillMaxSize().testTag("artifact-scroll")
                        .horizontalScroll(horizontal).verticalScroll(vertical)
                        .transformable(transform, canPan = { zoom > 1f }), contentAlignment = Alignment.Center) {
                        Image(image, item.name, Modifier.requiredSize(baseWidth * zoom, baseWidth * ratio * zoom), contentScale = ContentScale.Fit)
                    }
                }
                if (loading) CircularProgressIndicator()
                if (bitmap == null && ready && !loading && error == null)
                    Text("Open or share this file in a compatible app.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
            }
            error?.let { Text(it, Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                if (bitmap != null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    if (item.mime == "application/pdf") {
                        TextButton(onClick = { onPage(page - 1) }, enabled = page > 0 && !loading,
                            modifier = Modifier.width(32.dp).semantics { contentDescription = "Previous page" }, contentPadding = PaddingValues(0.dp)) { Text("‹") }
                        Text("${page + 1} / $pageCount", style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { onPage(page + 1) }, enabled = page + 1 < pageCount && !loading,
                            modifier = Modifier.width(32.dp).semantics { contentDescription = "Next page" }, contentPadding = PaddingValues(0.dp)) { Text("›") }
                    }
                    TextButton(onClick = { scrolling = !scrolling; reset() }, contentPadding = PaddingValues(6.dp),
                        modifier = Modifier.semantics { contentDescription = if (scrolling) "Fit page" else "Scroll page" }) {
                        Text(if (scrolling) "Fit" else "Scroll", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { zoom = (zoom * 1.5f).coerceAtMost(6f) }, enabled = zoom < 6f,
                        contentPadding = PaddingValues(6.dp), modifier = Modifier.semantics { contentDescription = "Zoom in" }) { Text("+", style = MaterialTheme.typography.labelLarge) }
                    TextButton(onClick = { reset() }, contentPadding = PaddingValues(6.dp)) { Text("Reset zoom", style = MaterialTheme.typography.labelSmall) }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(enabled = ready, onClick = onOpen) { Text("Open", style = MaterialTheme.typography.labelMedium) }
                    TextButton(enabled = ready, onClick = onSave) { Text("Save", style = MaterialTheme.typography.labelMedium) }
                    TextButton(enabled = ready, onClick = onShare) { Text("Share", style = MaterialTheme.typography.labelMedium) }
                }
                if (onDock != null) TextButton(onClick = onDock, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Open in right drawer", style = MaterialTheme.typography.labelMedium)
                }
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
