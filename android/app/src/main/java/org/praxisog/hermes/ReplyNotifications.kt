package org.praxisog.hermes

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.*

internal class ReplyNotifications(private val context: Context, private val scope: CoroutineScope,
                                  private val cache: SessionCache?, private val store: HermesStore,
                                  private val profileId: String = SecureConnectionStore.LEGACY,
                                  private val isVisible: (String) -> Boolean = { false }) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val pending = mutableMapOf<String, Job>()
    init {
        manager.createNotificationChannel(NotificationChannel(REPLIES, "Replies", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(ATTENTION, "Needs attention", NotificationManager.IMPORTANCE_HIGH))
    }
    fun reply(id: String, messageId: Int) {
        pending[id]?.cancel()
        pending[id] = scope.launch {
            delay(800) // let a visible transcript advance its read receipt first
            if (id !in store.readState.unreadIds || !enabled() || (cache?.notifiedReply(id) ?: 0) >= messageId) return@launch
            post(id, "reply", "New reply is ready", REPLIES)
            cache?.rememberNotifiedReply(id, messageId)
        }
    }
    fun attention(id: String, identity: String) {
        if (isVisible(id)) { cache?.rememberNotified(identity); return }
        if (!enabled() || cache?.wasNotified(identity) == true) return
        post(id, "attention", if (identity.startsWith("approval:")) "Hermes needs your approval" else "A run needs your attention", ATTENTION)
        cache?.rememberNotified(identity)
    }
    fun read(id: String) { manager.cancel("reply:$id", 0) }
    private fun enabled(): Boolean = BellpullSettings(context).value.notifications && manager.areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    private fun post(id: String, kind: String, text: String, channel: String) {
        val title = (store.sessions as? UiState.Ready)?.value?.firstOrNull { it.id == id }?.title?.takeIf { it.isNotBlank() } ?: "Bellpull"
        val notification = Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(text).setContentIntent(openChat(context, id, profileId))
            .setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Bellpull").setContentText(text).build()).build()
        manager.notify("$kind:$id", 0, notification)
    }
    companion object {
        const val REPLIES = "bellpull_replies"
        const val ATTENTION = "bellpull_attention"
        const val EXTRA_SESSION = "bellpull_notification_session"
        const val EXTRA_PROFILE = "bellpull_notification_profile"
        fun openChat(context: Context, id: String, profileId: String = SecureConnectionStore.LEGACY): PendingIntent = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).setData(Uri.parse("bellpull://profile/${Uri.encode(profileId)}/chat/${Uri.encode(id)}"))
                .putExtra(EXTRA_SESSION, id).putExtra(EXTRA_PROFILE, profileId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun session(intent: Intent?): String? = intent?.getStringExtra(EXTRA_SESSION)
            ?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,200}")) }
        fun belongsTo(intent: Intent?, profileId: String?): Boolean = profileId != null &&
            (intent?.getStringExtra(EXTRA_PROFILE) ?: SecureConnectionStore.LEGACY) == profileId
    }
}
