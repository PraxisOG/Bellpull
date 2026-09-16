package org.praxisog.hermes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val requests = Executors.newSingleThreadExecutor()
    private var status by mutableStateOf("Enter a server URL and API key.")
    private var checking by mutableStateOf(false)
    private var store: SecureConnectionStore? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val connectionStore = runCatching { SecureConnectionStore(this) }.getOrElse {
            status = "Secure storage is unavailable on this device."
            null
        }
        store = connectionStore
        val initialUrl = runCatching { connectionStore?.loadUrl().orEmpty() }.getOrElse {
            status = "Saved connection settings could not be read."
            ""
        }
        val initialKey = runCatching { connectionStore?.loadKey().orEmpty() }.getOrElse {
            status = "Saved API key could not be read."
            ""
        }

        setContent {
            ConnectionScreen(initialUrl, initialKey, status, checking, ::checkConnection)
        }
    }

    private fun checkConnection(rawUrl: String, rawKey: String) {
        val baseUrl = HermesEndpoint.parse(rawUrl).getOrElse {
            status = it.message ?: "Enter a valid server URL."
            return
        }
        val key = rawKey.trim()
        if (key.isEmpty()) {
            status = "Enter the Hermes API key."
            return
        }
        val connectionStore = store
        if (connectionStore == null) {
            status = "Secure storage is unavailable on this device."
            return
        }
        val saved = runCatching { connectionStore.save(baseUrl, key) }.getOrDefault(false)
        if (!saved) {
            status = "Could not save the API key securely."
            return
        }
        checking = true
        status = "Checking Hermes…"
        requests.execute {
            val result = HermesProbe.check(baseUrl, key)
            runOnUiThread {
                if (!isDestroyed) {
                    checking = false
                    status = result
                }
            }
        }
    }

    override fun onDestroy() {
        requests.shutdownNow()
        super.onDestroy()
    }
}
