package org.praxisog.hermes

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.praxisog.hermes.model.Artifact
import java.io.File
import java.util.UUID

internal object ArtifactSharing {
    fun text(context: Context, value: String) {
        val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, value) }
        launch(context, Intent.createChooser(intent, "Share message"))
    }
    fun temporary(context: Context, profileId: String, name: String): File {
        require(profileId.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        val folder = File(context.cacheDir, "bellpull-shared/$profileId/${UUID.randomUUID()}").apply { mkdirs() }
        val safe = name.substringAfterLast('/').substringAfterLast('\\').filter { it.code >= 32 }.take(180).ifBlank { "attachment" }
        return File(folder, safe)
    }
    fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    /** Copies only the selected item into the FileProvider's narrowly scoped directory. */
    suspend fun file(context: Context, profileId: String, artifact: Artifact, source: File, open: Boolean) {
        val target = temporary(context, profileId, artifact.name)
        try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { source.copyTo(target, overwrite = true) } }
        catch (_: java.io.IOException) {
            android.widget.Toast.makeText(context, "Could not prepare the file for sharing.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val uri = uri(context, target)
        val intent = Intent(if (open) Intent.ACTION_VIEW else Intent.ACTION_SEND).apply {
            if (open) setDataAndType(uri, artifact.mime) else { type = artifact.mime; putExtra(Intent.EXTRA_STREAM, uri) }
            clipData = ClipData.newRawUri(artifact.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(context, Intent.createChooser(intent, if (open) "Open attachment" else "Share attachment"))
    }
    private fun launch(context: Context, intent: Intent) {
        try { context.startActivity(intent) }
        catch (_: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(context, "No app can handle this attachment.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
