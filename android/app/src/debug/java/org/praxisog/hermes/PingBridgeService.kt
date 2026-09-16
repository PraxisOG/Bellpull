package org.praxisog.hermes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Disposable Gate T2-0 harness. It handles only phone_ping; no phone data is exposed. */
class PingBridgeService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    @Volatile private var connection: HttpURLConnection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY

        val prefs = getSharedPreferences("ping_bridge_gate", MODE_PRIVATE)
        val server = intent?.getStringExtra(EXTRA_SERVER) ?: prefs.getString(EXTRA_SERVER, null)
        val requestTopic = intent?.getStringExtra(EXTRA_REQUEST_TOPIC)
            ?: prefs.getString(EXTRA_REQUEST_TOPIC, null)
        val replyTopic = intent?.getStringExtra(EXTRA_REPLY_TOPIC)
            ?: prefs.getString(EXTRA_REPLY_TOPIC, null)
        if (server.isNullOrBlank() || !server.startsWith("https://") ||
            !validTopic(requestTopic) || !validTopic(replyTopic)
        ) {
            Log.e(TAG, "Missing HTTPS server or invalid topic names")
            stopSelf()
            return START_NOT_STICKY
        }
        prefs.edit().putString(EXTRA_SERVER, server).putString(EXTRA_REQUEST_TOPIC, requestTopic)
            .putString(EXTRA_REPLY_TOPIC, replyTopic).apply()

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Phone ping gate", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Hermes phone ping gate")
            .setContentText("Listening for reachability probes")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        running = true
        worker.execute { listen(server, requestTopic!!, replyTopic!!) }
        return START_STICKY
    }

    private fun listen(server: String, requestTopic: String, replyTopic: String) {
        var lastMessageId: String? = null
        while (running) {
            try {
                val since = lastMessageId ?: "10m"
                val url = URL("${server.trimEnd('/')}/$requestTopic/sse?since=$since")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 0 // ntfy SSE is intentionally long-lived.
                    setRequestProperty("Accept", "text/event-stream")
                }
                connection = conn
                conn.inputStream.bufferedReader().use { reader ->
                    val data = StringBuilder()
                    while (running) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith("data: ")) data.append(line.removePrefix("data: "))
                        if (line.isEmpty() && data.isNotEmpty()) {
                            val event = JSONObject(data.toString())
                            data.clear()
                            if (event.optString("event") == "message") {
                                lastMessageId = event.optString("id").takeIf { it.isNotBlank() }
                                    ?: lastMessageId
                                onMessage(server, replyTopic, event.optString("message"))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "Subscription disconnected: ${e.javaClass.simpleName}")
            } finally {
                connection?.disconnect()
                connection = null
            }
            if (running) TimeUnit.SECONDS.sleep(5)
        }
    }

    private fun onMessage(server: String, replyTopic: String, raw: String) {
        val request = try { JSONObject(raw) } catch (_: Exception) { return }
        if (request.optString("kind") != "phone_ping") return
        val id = request.optString("id")
        val echo = request.optString("echo")
        if (id.length !in 1..80 || echo.length > 200) return
        val reply = JSONObject()
            .put("kind", "phone_pong")
            .put("id", id)
            .put("echo", echo)
            .put("received_at_ms", System.currentTimeMillis())
        try {
            val conn = (URL("${server.trimEnd('/')}/$replyTopic").openConnection() as HttpURLConnection)
                .apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            conn.outputStream.use { it.write(reply.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) Log.w(TAG, "Reply publish HTTP ${conn.responseCode}")
            else Log.i(TAG, "Replied to ping id=$id")
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Reply publish failed: ${e.javaClass.simpleName}")
        }
    }

    override fun onDestroy() {
        running = false
        connection?.disconnect()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun validTopic(topic: String?): Boolean =
        topic != null && topic.length in 1..100 && topic.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    companion object {
        const val ACTION_STOP = "org.praxisog.hermes.STOP_PING_GATE"
        const val EXTRA_SERVER = "server"
        const val EXTRA_REQUEST_TOPIC = "request_topic"
        const val EXTRA_REPLY_TOPIC = "reply_topic"
        private const val CHANNEL = "phone_ping_gate"
        private const val NOTIFICATION_ID = 2201
        private const val TAG = "PingBridgeGate"
    }
}
