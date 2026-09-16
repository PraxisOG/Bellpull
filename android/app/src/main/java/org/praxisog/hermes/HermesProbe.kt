package org.praxisog.hermes

import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal object HermesProbe {
    fun check(baseUrl: String, key: String): String {
        val connection = URL("$baseUrl/v1/capabilities").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 10_000
            connection.useCaches = false
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Accept", "application/json")

            when (val code = connection.responseCode) {
                200 -> {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val features = JSONObject(body).getJSONObject("features")
                    if (features.optBoolean("session_chat_streaming")) {
                        "Authenticated. Session streaming is available."
                    } else {
                        "Authenticated, but this Hermes server does not report session streaming."
                    }
                }
                401, 403 -> "Authentication failed. Check the Hermes API key."
                429 -> "Hermes is rate limiting requests. Try again later."
                in 300..399 -> "Hermes redirected the request; check the server URL."
                else -> "Hermes returned HTTP $code."
            }
        } catch (_: UnknownHostException) {
            "Server name could not be resolved. Check the address or Tailscale."
        } catch (_: ConnectException) {
            "Could not connect to Hermes. Check that its API is reachable."
        } catch (_: SocketTimeoutException) {
            "Hermes did not respond in time. Check the network path."
        } catch (_: SSLException) {
            "TLS failed. Check the server certificate and address."
        } catch (_: SecurityException) {
            "Network access was denied by Android. Check app permissions."
        } catch (_: Exception) {
            "The Hermes request failed. Check the server and network settings."
        } finally {
            connection.disconnect()
        }
    }
}
