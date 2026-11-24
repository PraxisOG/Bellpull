package org.praxisog.hermes

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*
import org.praxisog.hermes.model.CanvasDocument
import java.io.File

@Composable
internal fun CanvasExportMenu(workspace: CanvasWorkspace, doc: CanvasDocument) {
    val context = LocalContext.current.applicationContext
    val runtime = remember(context) { BellpullRuntime.get(context) }
    val files = runtime.phase3?.takeIf { runtime.canvas === workspace && it.filesSupported }
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun copyTo(uri: Uri?) {
        val staged = pending?.let(::File) ?: return
        pending = null
        if (uri == null) { staged.delete(); staged.parentFile?.delete(); return }
        working = true
        scope.launch {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not open the chosen file.")
                    output.use { dest -> staged.inputStream().use { it.copyTo(dest) } }
                }
                notice = "Drawing saved."
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notice = error.message ?: "Could not save the drawing."
            } finally { working = false; staged.delete(); staged.parentFile?.delete() }
        }
    }
    val saveSvg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(CanvasFormat.SVG_FILE.mime), ::copyTo)
    val savePng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(CanvasFormat.PNG.mime), ::copyTo)
    fun export(format: CanvasFormat, upload: Boolean) {
        menu = false
        val chat = workspace.session
        val drawing = CanvasExport(doc.content)
        val name = drawing.name(format)
        val file = ArtifactSharing.temporary(context, files?.profileId ?: "canvas-export", name)
        working = true
        scope.launch {
            var retained = false
            try {
                withContext(Dispatchers.IO) { file.outputStream().use { drawing.write(format, it) } }
                if (upload) {
                    check(chat != null && files != null && runtime.phase3 === files && workspace.session == chat) { "The server or conversation changed." }
                    val job = files.addUris(chat, listOf(ArtifactSharing.uri(context, file)), uploadOnly = true)
                    checkNotNull(job) { "The file upload is unavailable." }
                    job.invokeOnCompletion { file.delete(); file.parentFile?.delete() }
                    retained = true
                    notice = "Drawing queued in server uploads."
                } else {
                    pending = file.absolutePath
                    if (format == CanvasFormat.PNG) savePng.launch(name) else saveSvg.launch(name)
                    retained = true
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                pending = null
                notice = error.message ?: "Could not export the drawing."
            } finally { if (!retained) { file.delete(); file.parentFile?.delete() }; working = false }
        }
    }
    Box {
        TextButton(onClick = { menu = true }, enabled = !working && pending == null) { Text("Export") }
        DropdownMenu(menu, { menu = false }) {
            for (format in CanvasFormat.entries) {
                val label = format.extension.uppercase()
                DropdownMenuItem(text = { Text("Save $label…") }, onClick = { export(format, false) })
                if (files != null) DropdownMenuItem(text = { Text("Upload $label to conversation") }, onClick = { export(format, true) })
            }
        }
    }
    notice?.let { result -> LaunchedEffect(result) {
        android.widget.Toast.makeText(context, result, android.widget.Toast.LENGTH_LONG).show()
        notice = null
    } }
}
