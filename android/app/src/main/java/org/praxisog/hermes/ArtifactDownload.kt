package org.praxisog.hermes

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.praxisog.hermes.model.Artifact
import java.io.File

/** The picker receives only the filename/MIME; the server URL and credentials stay private. */
internal class ArtifactDestination : ActivityResultContract<Artifact, Uri?>() {
    override fun createIntent(context: Context, input: Artifact) = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE).setType(input.mime).putExtra(Intent.EXTRA_TITLE, input.name)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

@Composable
internal fun rememberArtifactDownload(workspace: Phase3Workspace): (Artifact) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingId by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ArtifactDestination()) { uri ->
        val id = pendingId
        pendingId = null
        if (uri != null) scope.launch {
            busy = true
            try {
                val item = workspace.artifacts.find { it.id == id }
                val file = item?.let { workspace.download(it) }
                val saved = file != null && saveArtifact(context, file, uri)
                Toast.makeText(context, if (saved) "File saved" else workspace.errors[id] ?: "Could not save this file.", Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }
    return { item ->
        if (busy || pendingId != null) Toast.makeText(context, "A file download is already in progress.", Toast.LENGTH_SHORT).show()
        else { pendingId = item.id; picker.launch(item) }
    }
}

internal suspend fun saveArtifact(context: Context, source: File, uri: Uri): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.openOutputStream(uri, "w")?.use { output -> source.inputStream().use { it.copyTo(output) } }
            ?: error("Destination unavailable")
    }.isSuccess
}
