package org.praxisog.hermes

import android.content.Context
import android.util.AtomicFile
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import org.praxisog.hermes.model.*
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal fun offlineId() = UUID.randomUUID().toString().replace("-", "")
internal fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
@Serializable internal data class OfflineSource(val id: String, val title: String, val messages: List<Message>,
    val savedAt: Long = System.currentTimeMillis(), val revision: String = digest(Json.encodeToString(messages)))
@Serializable internal data class OfflineTurn(val id: String = offlineId(), val role: String, val text: String = "",
    val thought: String = "", val status: String = "complete", val citations: List<String> = emptyList(),
    val tools: List<String> = emptyList(), val metrics: String = "")
@Serializable internal data class OfflineFork(val id: String = offlineId(), val title: String = "Local conversation",
    @Transient val source: OfflineSource? = null, val sourceId: String? = source?.id, val sourceRevision: String? = source?.revision,
    val memory: OfflineMemory = OfflineMemory(), val brief: String = "",
    val thinking: Boolean = false, val turns: List<OfflineTurn> = emptyList(), val draft: String = "",
    val updatedAt: Long = System.currentTimeMillis(), val pending: OfflineHandoff? = null, val remoteId: String? = null)
@Serializable internal data class OfflineChunk(val id: String, val messageId: Int, val role: String, val text: String,
    val vector: List<Float> = emptyList())
@Serializable internal data class OfflineIndex(val revision: String, val model: String = SearchEncoder.VERSION, val chunks: List<OfflineChunk>)

/** AES-GCM records with profile/record AAD and atomic replacement. No plaintext transcripts on disk. */
@Suppress("DEPRECATION")
internal class OfflineStorage(context: Context, profile: String) {
    companion object { private val records = Any() }
    init { require(profile.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) }
    private val root = File(context.filesDir, "bellpull-files/$profile/offline").apply { mkdirs() }
    private val aad = "bellpull-offline-v1:$profile:"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key: SecretKey by lazy {
        val alias = MasterKey.DEFAULT_MASTER_KEY_ALIAS
        MasterKey.Builder(context.applicationContext, alias).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        (KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as SecretKey)
    }
    private fun file(name: String) = AtomicFile(File(root, name))
    private fun read(name: String): String? = synchronized(records) {
        if (!file(name).baseFile.exists() && !File(root, "$name.bak").exists()) return@synchronized null
        val bytes = file(name).readFully()
        require(bytes.size >= 28) { "Offline record is damaged." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD((aad + name).toByteArray())
        cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
    }
    private fun write(name: String, value: String) = synchronized(records) {
        root.mkdirs()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD((aad + name).toByteArray())
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray())
        val target = file(name); val output = target.startWrite()
        try { output.write(bytes); target.finishWrite(output) } catch (e: Throwable) { target.failWrite(output); throw e }
    }
    fun source(id: String): OfflineSource? = read("source-${digest(id)}")?.let { json.decodeFromString(it) }
    fun save(source: OfflineSource) = write("source-${digest(source.id)}", json.encodeToString(source))
    private fun snapshotName(fork: OfflineFork) = "snapshot-${digest("${fork.sourceId}:${fork.sourceRevision}")}"
    fun fork(id: String): OfflineFork? = read("fork-${digest(id)}")?.let {
        val fork = json.decodeFromString<OfflineFork>(it)
        if (fork.sourceId == null) fork else fork.copy(source = json.decodeFromString<OfflineSource>(
            checkNotNull(read(snapshotName(fork))) { "Saved source snapshot is missing." }))
    }
    fun save(fork: OfflineFork) = synchronized(records) {
        // Immutable source snapshots are written once, not re-encrypted every streamed token/draft.
        if (fork.source != null && !file(snapshotName(fork)).baseFile.exists())
            write(snapshotName(fork), json.encodeToString(fork.source))
        write("fork-${digest(fork.id)}", json.encodeToString(fork))
    }
    fun forks(): List<OfflineFork> = root.listFiles().orEmpty().filter { it.name.matches(Regex("fork-[a-f0-9]{64}")) }
        .map { json.decodeFromString<OfflineFork>(checkNotNull(read(it.name))) }.sortedByDescending { it.updatedAt }
    fun delete(fork: OfflineFork) = synchronized(records) {
        file("fork-${digest(fork.id)}").delete(); file("index-${digest(fork.id)}").delete()
        if (forks().none { it.sourceId == fork.sourceId && it.sourceRevision == fork.sourceRevision }) file(snapshotName(fork)).delete()
    }
    fun memory(): OfflineMemory = read("memory")?.let { json.decodeFromString(it) } ?: OfflineMemory()
    fun save(memory: OfflineMemory) = write("memory", json.encodeToString(memory))
    fun index(id: String): OfflineIndex? = read("index-${digest(id)}")?.let { json.decodeFromString(it) }
    fun save(id: String, index: OfflineIndex) = write("index-${digest(id)}", json.encodeToString(index))
}
