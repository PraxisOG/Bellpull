package org.praxisog.hermes

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface NotificationCipher {
    fun encrypt(value: ByteArray): ByteArray
    fun decrypt(value: ByteArray): ByteArray
}

/** Each row has an independent GCM nonce. Titles, sources and notification keys are encrypted. */
internal class KeystoreNotificationCipher : NotificationCipher {
    private val key: SecretKey = synchronized(lock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    override fun encrypt(value: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, key)
        iv + doFinal(value)
    }
    override fun decrypt(value: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        require(value.size > 28)
        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, value.copyOfRange(0, 12)))
        doFinal(value.copyOfRange(12, value.size))
    }
    private companion object { const val ALIAS = "bellpull_notification_history_v1"; val lock = Any() }
}

/** Incremental row writes keep notification storms away from chat and avoid rewriting the history. */
@Suppress("DEPRECATION")
internal class PhoneNotificationStorage(context: Context,
    private val cipher: NotificationCipher = KeystoreNotificationCipher(),
    testPreferences: SharedPreferences? = null,
    databaseName: String = "phone-notifications-v1.db",
) : SQLiteOpenHelper(context, databaseName, null, 1) {
    private val json = Json { ignoreUnknownKeys = true }
    private val preferences = testPreferences ?: EncryptedSharedPreferences.create(context, "phone-notification-policy-v1",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE notifications (id TEXT PRIMARY KEY NOT NULL, activity_at INTEGER NOT NULL, payload BLOB NOT NULL)")
        db.execSQL("CREATE INDEX notifications_activity ON notifications(activity_at)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported notification history version")
    fun policy(): PhoneNotificationPolicy = preferences.getString("policy", null)?.let {
        json.decodeFromString<PhoneNotificationPolicy>(it)
    } ?: PhoneNotificationPolicy()
    fun policy(value: PhoneNotificationPolicy) {
        require(value.retentionDays in setOf(1, 7, 30))
        check(preferences.edit().putString("policy", json.encodeToString(value)).commit())
    }
    fun read(now: Long, policy: PhoneNotificationPolicy): List<PhoneNotificationRecord> {
        prune(now, policy)
        val records = readableDatabase.query("notifications", arrayOf("payload"), null, null, null, null, "activity_at DESC",
            NOTIFICATION_HISTORY_LIMIT.toString()).use { cursor -> buildList {
            while (cursor.moveToNext()) add(json.decodeFromString<PhoneNotificationRecord>(cipher.decrypt(cursor.getBlob(0)).toString(Charsets.UTF_8)))
        } }
        val retained = retainedNotifications(records, policy, now)
        remove(records.map { it.id }.toSet() - retained.map { it.id }.toSet())
        return retained
    }
    fun put(value: PhoneNotificationRecord) {
        val row = ContentValues().apply {
            put("id", value.id); put("activity_at", value.activityAt)
            put("payload", cipher.encrypt(json.encodeToString(value).toByteArray(Charsets.UTF_8)))
        }
        check(writableDatabase.insertWithOnConflict("notifications", null, row, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }
    fun remove(ids: Collection<String>) {
        writableDatabase.beginTransaction()
        try { ids.forEach { writableDatabase.delete("notifications", "id = ?", arrayOf(it)) }; writableDatabase.setTransactionSuccessful() }
        finally { writableDatabase.endTransaction() }
    }
    fun clear() { writableDatabase.delete("notifications", null, null) }
    fun prune(now: Long, policy: PhoneNotificationPolicy) {
        writableDatabase.delete("notifications", "activity_at <= ?", arrayOf((now - policy.retentionDays * NOTIFICATION_DAY_MS).toString()))
        writableDatabase.execSQL("DELETE FROM notifications WHERE id NOT IN (SELECT id FROM notifications ORDER BY activity_at DESC LIMIT $NOTIFICATION_HISTORY_LIMIT)")
    }
}
