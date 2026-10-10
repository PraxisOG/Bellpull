package org.praxisog.hermes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
internal fun ConnectionScreen(
    initialUrl: String,
    initialKey: String,
    status: String,
    checking: Boolean,
    preferences: BellpullPreferences = BellpullPreferences(),
    onCheck: (String, String) -> Unit,
) {
    // Keyed, so that clearing the stored credentials actually blanks the fields. Unkeyed
    // remember held the old key on screen after the user asked the app to forget it.
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var key by remember(initialKey) { mutableStateOf(initialKey) }

    HermesTheme(appearance = preferences.appearance, palette = preferences.palette, customAccent = preferences.customAccent) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(
                        MaterialTheme.colorScheme.surfaceContainerLow,
                        MaterialTheme.colorScheme.surface,
                    )))
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        BellpullMark(size = 52.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Bellpull", style = MaterialTheme.typography.headlineMedium,
                                fontFamily = FontFamily.Serif)
                            Text("Your agent, within reach.", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        shape = MaterialTheme.shapes.extraLarge,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            Text("Make the connection", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Connect to your Hermes API server over Tailscale.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = url,
                                onValueChange = { url = it },
                                label = { Text("Server URL") },
                                placeholder = { Text("http://100.64.0.1:8642") },
                                singleLine = true,
                                shape = MaterialTheme.shapes.small,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = key,
                                onValueChange = { key = it },
                                label = { Text("Hermes API key") },
                                singleLine = true,
                                shape = MaterialTheme.shapes.small,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = { onCheck(url, key) },
                                enabled = !checking,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                            ) {
                                Text(if (checking) "Checking…" else "Check connection")
                            }
                            Text(status, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "cover", widthDp = 393, heightDp = 850, showBackground = true)
@Composable
private fun CoverPreview() {
    ConnectionScreen("", "", "Enter a server URL and API key.", false) { _, _ -> }
}

@Preview(name = "unfolded", widthDp = 840, heightDp = 900, showBackground = true)
@Composable
private fun UnfoldedPreview() {
    ConnectionScreen("", "", "Enter a server URL and API key.", false) { _, _ -> }
}
