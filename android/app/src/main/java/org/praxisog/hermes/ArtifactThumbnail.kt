package org.praxisog.hermes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.Artifact
import org.praxisog.hermes.model.HermesResult
import java.io.File

internal enum class ImagePreviewSize(val label: String, val height: Int) {
    OFF("Off", 0), SMALL("Small", 96), MEDIUM("Medium", 180), LARGE("Large", 300);
    companion object { fun decode(value: String?) = entries.find { it.name == value } ?: MEDIUM }
}
internal val LocalArtifactThumbnails = staticCompositionLocalOf<ArtifactThumbnails?> { null }
internal val LocalImagePreviewSize = staticCompositionLocalOf { ImagePreviewSize.MEDIUM }

/** Profile-owned, bounded native thumbnails. Never follows links in message text. */
internal class ArtifactThumbnails(private val root: File, private val client: HermesClient) {
    private val lock = Mutex()
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    @Volatile private var closed = false
    @Volatile private var transfer: HermesClient.Streaming? = null
    suspend fun load(item: Artifact, allowPdf: Boolean = false): Bitmap? = withContext(Dispatchers.IO) {
        if (!eligible(item, allowPdf) || closed) return@withContext null
        val key = "${item.id}-${item.sha256}"
        cache.get(key)?.let { return@withContext it }
        lock.withLock {
            ensureActive()
            if (closed) return@withLock null
            cache.get(key)?.let { return@withLock it }
            root.mkdirs()
            val target = File(root, key)
            if (!target.isFile || target.length() != item.size) {
                val temporary = File.createTempFile("thumb-", ".tmp", root)
                try {
                    val result = client.downloadArtifact(item, temporary) { transfer = it; if (closed) it.cancel() }
                    if (closed || result !is HermesResult.Ok || !temporary.renameTo(target)) return@withLock null
                } finally { transfer = null; temporary.delete() }
            }
            target.setLastModified(System.currentTimeMillis())
            val bitmap = when (item.mime) {
                "image/svg+xml" -> SvgPreview.render(target, 768)
                "application/pdf" -> pdfThumbnail(target)
                else -> decode(target)
            }
            if (bitmap == null) return@withLock null
            if (closed) return@withLock null
            cache.put(key, bitmap)
            var bytes = 0L
            root.listFiles().orEmpty().sortedByDescending { it.lastModified() }.forEach { file ->
                bytes += file.length(); if (bytes > 64L * 1024 * 1024 && file != target) file.delete()
            }
            bitmap
        }
    }
    fun close() { closed = true; transfer?.cancel(); cache.evictAll() }
    companion object {
        fun eligible(item: Artifact, allowPdf: Boolean = false) = item.available && !item.deleted && item.size in 1..8L * 1024 * 1024 &&
            (item.mime in setOf("image/png", "image/jpeg", "image/webp", "image/svg+xml") || (allowPdf && item.mime == "application/pdf")) &&
            (item.mime != "image/svg+xml" || item.size <= SvgPreview.MAX_BYTES) &&
            item.id.matches(Regex("[a-f0-9]{32}")) && item.sha256.matches(Regex("[a-f0-9]{64}"))
        internal fun decode(file: File): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 100_000 || bounds.outHeight > 100_000) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 768) sample *= 2
            return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        private fun pdfThumbnail(file: File): Bitmap? = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            android.graphics.pdf.PdfRenderer(descriptor).use { document ->
                if (document.pageCount == 0) return null
                document.openPage(0).use { page ->
                    val scale = 768f / maxOf(page.width, page.height, 1)
                    Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(android.graphics.Color.WHITE)
                        page.render(it, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ArtifactThumbnail(item: Artifact, onView: (Artifact) -> Unit, height: androidx.compose.ui.unit.Dp? = null, allowPdf: Boolean = false) {
    val source = LocalArtifactThumbnails.current ?: return
    val size = LocalImagePreviewSize.current
    if (size == ImagePreviewSize.OFF || !ArtifactThumbnails.eligible(item, allowPdf)) return
    var retry by remember(source, item.id, item.sha256) { mutableIntStateOf(0) }
    var bitmap by remember(source, item) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(source, item) { mutableStateOf(true) }
    LaunchedEffect(source, item, retry, allowPdf) {
        loading = true
        bitmap = try { source.load(item, allowPdf) } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
        loading = false
    }
    Box(Modifier.fillMaxWidth().height(height ?: size.height.dp).testTag("thumbnail-${item.id}").padding(8.dp)
        .clickable(onClickLabel = "Open ${item.name}") { onView(item) }, contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = item.name,
            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            ?: if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            else TextButton(onClick = { retry++ }) { Text("Retry image preview", style = MaterialTheme.typography.labelSmall) }
    }
}
