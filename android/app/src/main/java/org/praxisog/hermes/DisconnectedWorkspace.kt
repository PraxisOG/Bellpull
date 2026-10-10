package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.praxisog.hermes.model.Message
import org.praxisog.hermes.model.Session

/** Bundled, fictional content. No client, credentials, inference or network access. */
internal object BellpullExample {
    const val ID = "bellpull-local-example"
    val session = Session(id = ID, title = "A look around Bellpull", source = "local example",
        messageCount = 2, preview = "A sample conversation. No server connected.")
    val messages = listOf(
        Message(id = 1, sessionId = ID, role = "user", content = "What can I try without connecting an agent?"),
        Message(id = 2, sessionId = ID, role = "assistant", content = "Welcome to Bellpull. This is a sample conversation stored on your phone.\n\n" +
            "Try the conversation and context panels, copy a message, or use read aloud if your phone has a speech engine. " +
            "Open Settings to try the colors, compact layout and immersive mode.\n\n" +
            "When you're ready, add your own Hermes server in **Settings → Server profiles**. " +
            "You can keep separate profiles for testing and everyday use. Nothing here contacts an agent or generates a reply."),
    )
}

@Composable
internal fun DisconnectedWelcome(onExample: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        BellpullMark(size = 64.dp)
        Text("Your agent, within reach.", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text("Take a look around. Connect your own server when you're ready.",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Button(onClick = onExample) { Text("Explore a sample chat") }
        TextButton(onClick = onSettings) { Text("Open Settings") }
    }
}

@Composable
internal fun DisconnectedBanner(onSettings: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Local example · No server connected", style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onSettings) { Text("Connect") }
        }
    }
}
