package org.praxisog.hermes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
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
    onCheck: (String, String) -> Unit,
) {
    var url by remember { mutableStateOf(initialUrl) }
    var key by remember { mutableStateOf(initialKey) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Hermes connection", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Connect to your Hermes API server over Tailscale.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Server URL") },
                        placeholder = { Text("http://100.64.0.1:8642") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text("Hermes API key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { onCheck(url, key) },
                        enabled = !checking,
                    ) {
                        Text(if (checking) "Checking…" else "Check connection")
                    }
                    Text(status, style = MaterialTheme.typography.bodyMedium)
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
