package org.praxisog.hermes

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.HermesResult
import java.io.File

/** Explicitly provisioned read-only audit. Never loads personal app storage or submits a turn. */
class ConnectionDeviceTest {
    @Test fun measureAuthenticatedConnection() {
        DeviceFixtureUi.guard()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configFile = File(context.filesDir, "connection-audit.json")
        assumeTrue("Explicit read-only connection audit configuration required", configFile.isFile)
        val config = JSONObject(configFile.readText())
        check(configFile.delete())
        val args = InstrumentationRegistry.getArguments()
        val samples = (args.getString("samples")?.toInt() ?: 30).coerceIn(1, 300)
        val label = args.getString("scenario") ?: "network"
        require(label.matches(Regex("[a-z-]+")))
        val client = HermesClient(config.getString("url"), { config.getString("key") })
        val rows = JSONArray()
        val output = File(context.getExternalFilesDir(null), "connection-$label.json")
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        try {
            repeat(samples) { index ->
                val start = SystemClock.elapsedRealtime()
                val result = client.checkConnection()
                val network = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                rows.put(JSONObject().put("sample", index).put("elapsed_ms", start)
                    .put("duration_ms", SystemClock.elapsedRealtime() - start)
                    .put("ok", result is HermesResult.Ok)
                    .put("error_type", (result as? HermesResult.Err)?.error?.javaClass?.simpleName ?: JSONObject.NULL)
                    .put("wifi", network?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
                    .put("cellular", network?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true)
                    .put("vpn", network?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true))
                output.writeText(rows.toString(2))
                Thread.sleep(1000)
            }
            assertEquals(samples, rows.length())
        } finally { client.close(); configFile.delete() }
    }
}
