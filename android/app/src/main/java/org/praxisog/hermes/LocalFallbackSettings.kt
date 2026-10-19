package org.praxisog.hermes

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

internal data class LocalFallbackConfiguration(val autoLoad: Boolean = true, val thinking: Boolean = false,
    val contextTokens: Int = 2048, val systemPrompt: String = OfflinePrompt.DEFAULT_SYSTEM_PROMPT)

/** Device settings, independent of server credentials and the experimental GPU lab. */
@Suppress("DEPRECATION")
internal class LocalFallbackSettings(context: Context, testPromptPreferences: SharedPreferences? = null) {
    companion object { val contexts = listOf(2048, 4096, 8192); const val MAX_PROMPT_CHARS = 16000 }
    private val prefs = context.getSharedPreferences("local-fallback", Context.MODE_PRIVATE)
    // The prompt can contain personal facts. Keep it inside the existing encrypted boundary.
    private val prompts = testPromptPreferences ?: EncryptedSharedPreferences.create(
        context, "local-fallback-prompt", MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    var value by mutableStateOf(LocalFallbackConfiguration(prefs.getBoolean("auto_load", true),
        prefs.getBoolean("thinking", false), prefs.getInt("context", 2048).takeIf { it in contexts } ?: 2048,
        prompts.getString("system_prompt", null) ?: OfflinePrompt.DEFAULT_SYSTEM_PROMPT))
        private set
    fun update(next: LocalFallbackConfiguration) {
        require(next.contextTokens in contexts)
        require(next.systemPrompt.isNotBlank() && next.systemPrompt.length <= MAX_PROMPT_CHARS)
        if (next.systemPrompt != value.systemPrompt) prompts.edit().putString("system_prompt", next.systemPrompt).apply()
        prefs.edit().putBoolean("auto_load", next.autoLoad).putBoolean("thinking", next.thinking)
            .putInt("context", next.contextTokens).apply()
        value = next
    }
}
