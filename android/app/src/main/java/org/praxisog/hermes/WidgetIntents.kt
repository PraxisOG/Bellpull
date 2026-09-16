package org.praxisog.hermes

import android.content.Context
import android.content.Intent

/**
 * The contract between the widget and [MainActivity], kept in one place and free of Android
 * UI so it can be tested directly.
 *
 * The widget cannot create a Hermes session itself: doing so needs the bearer key out of
 * EncryptedSharedPreferences and a network call, and a widget process is the wrong place for
 * both. It therefore states an *intent to start a chat* and lets the Activity do the work.
 */
internal object WidgetIntents {

    /** Marks a launch as "the user tapped the new-chat widget". */
    const val EXTRA_NEW_CHAT = "org.praxisog.hermes.extra.NEW_CHAT"

    fun newChat(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_NEW_CHAT, true)
            // The widget may be tapped while the app is already in the background holding an
            // open session; reuse that task rather than stacking a second copy of the app.
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** True when [intent] is a new-chat request. Null-safe: Activities get null intents. */
    fun isNewChat(intent: Intent?): Boolean =
        intent?.getBooleanExtra(EXTRA_NEW_CHAT, false) == true
}
