package org.praxisog.hermes

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

@Suppress("DEPRECATION") // Required by the current project security contract in CLAUDE.md.
internal class SecureConnectionStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "hermes_connection",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun loadUrl(): String = preferences.getString("base_url", "") ?: ""

    fun loadKey(): String = preferences.getString("api_key", "") ?: ""

    fun save(url: String, key: String): Boolean = preferences.edit()
        .putString("base_url", url)
        .putString("api_key", key)
        .commit()
}
