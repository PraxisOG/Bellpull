package org.praxisog.hermes

import android.content.Context
import androidx.compose.runtime.*

internal data class LocalFallbackConfiguration(val autoLoad: Boolean = true, val thinking: Boolean = false,
    val contextTokens: Int = 2048)

/** Device settings, independent of server credentials and the experimental GPU lab. */
internal class LocalFallbackSettings(context: Context) {
    companion object { val contexts = listOf(2048, 4096, 8192) }
    private val prefs = context.getSharedPreferences("local-fallback", Context.MODE_PRIVATE)
    var value by mutableStateOf(LocalFallbackConfiguration(prefs.getBoolean("auto_load", true),
        prefs.getBoolean("thinking", false), prefs.getInt("context", 2048).takeIf { it in contexts } ?: 2048))
        private set
    fun update(next: LocalFallbackConfiguration) {
        require(next.contextTokens in contexts)
        prefs.edit().putBoolean("auto_load", next.autoLoad).putBoolean("thinking", next.thinking)
            .putInt("context", next.contextTokens).apply()
        value = next
    }
}
