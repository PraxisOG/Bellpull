package org.praxisog.hermes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class PhoneNotificationRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "notification-test-${UUID.randomUUID()}"
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private var now = 1_800_000_000_000L
    @Volatile private var access = true
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = object : NotificationCipher {
        override fun encrypt(value: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.ENCRYPT_MODE, key); iv + doFinal(value) }
        override fun decrypt(value: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, value.copyOfRange(0, 12))); doFinal(value.copyOfRange(12, value.size))
        }
    }
    private fun storage() = PhoneNotificationStorage(context, cipher, prefs, "$name.db")
    private fun repository() = PhoneNotificationRepository(::storage, { access }, "own.app", { now })
    private fun row(key: String = "key", text: String = "Private workshop message") = PhoneNotificationRecord("id", key, "source.app", "Messenger",
        "Alice", text, now, now, now)

    @Test fun defaultOffUpdatesRemovalRepostAndEncryptedRestart() = runBlocking {
        var repo = repository(); repo.awaitIdle()
        try {
            repo.posted(row()); repo.awaitIdle(); assertTrue(repo.state.value.records.isEmpty())
            repo.capture(true); repo.share("server-a", true); repo.awaitIdle()
            repo.posted(row()); repo.posted(row()); repo.awaitIdle()
            assertEquals(1, repo.state.value.records.size)
            now++; repo.posted(row(text = "New private message")); repo.awaitIdle()
            assertEquals(1, repo.state.value.records.single().updateCount)
            repo.removed("key", "dismissed", now); repo.awaitIdle()
            assertEquals("dismissed", repo.state.value.records.single().removalReason)
            now++; repo.posted(row()); repo.awaitIdle()
            assertEquals(2, repo.state.value.records.size)
            assertNotNull(repo.contextFor("server-a", "Alice")); assertNull(repo.contextFor("server-b", "Alice"))
            val rows = repo.state.value.records
            repo.close(); repo = repository(); repo.awaitIdle()
            assertEquals(rows, repo.state.value.records)
            val raw = context.getDatabasePath("$name.db").readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(raw.contains("Private workshop")); assertFalse(raw.contains("source.app")); assertFalse(raw.contains("Alice"))
        } finally { repo.close() }
    }
    @Test fun revocationStopsSharingImmediatelyAndRequiresNewOptInEvenAfterRestart() = runBlocking {
        val repo = repository(); repo.awaitIdle()
        try {
            repo.capture(true); repo.share("server", true); repo.posted(row()); repo.awaitIdle()
            assertNotNull(repo.contextFor("server", "Alice"))
            access = false
            assertNull(repo.contextFor("server", "Alice"))
            repo.refresh(); repo.awaitIdle()
            assertFalse(repo.state.value.policy.capture); assertTrue(repo.state.value.policy.sharingProfiles.isEmpty())
            access = true; repo.refresh(); repo.posted(row("later")); repo.awaitIdle()
            assertEquals(1, repo.state.value.records.size); assertNull(repo.contextFor("server", "Alice"))
        } finally { repo.close() }
    }
    @Test fun clearExclusionsAndRetentionAlsoGateQueuedAndReconnectedRecords() = runBlocking {
        val repo = repository(); repo.awaitIdle()
        try {
            repo.capture(true); repo.posted(row()); repo.awaitIdle()
            val old = row(); repo.clear(); repo.posted(old); repo.connected(listOf(old)); repo.awaitIdle()
            assertTrue(repo.state.value.records.isEmpty())
            now++; repo.posted(row()); repo.awaitIdle(); assertEquals(1, repo.state.value.records.size)
            repo.exclude("source.app", true); repo.posted(row("queued")); repo.awaitIdle()
            assertTrue(repo.state.value.records.isEmpty())
            repo.exclude("source.app", false); now++; repo.posted(row()); repo.awaitIdle()
            now += 2 * NOTIFICATION_DAY_MS; repo.retention(1); repo.awaitIdle()
            assertTrue(repo.state.value.records.isEmpty())
        } finally { repo.close() }
    }
    @Test fun reconnectMarksMissingRecordsUnknownInsteadOfClaimingUserDismissedThem() = runBlocking {
        val repo = repository(); repo.awaitIdle()
        try {
            repo.capture(true); repo.posted(row()); repo.awaitIdle()
            repo.disconnected(); repo.connected(emptyList()); repo.awaitIdle()
            val record = repo.state.value.records.single()
            assertEquals("no longer present after reconnect", record.removalReason)
            assertTrue(repo.state.value.connected); assertTrue(repo.state.value.gap)
            repo.posted(row("own").copy(packageName = "own.app")); repo.awaitIdle()
            assertEquals(1, repo.state.value.records.size)
        } finally { repo.close() }
    }
    @Test fun storageFailureNeverFallsBackToPlaintextOrSharesOldData() = runBlocking {
        val repo = PhoneNotificationRepository({ error("Fixture key unavailable") }, { true }, "own.app", { now })
        try {
            repo.awaitIdle(); repo.capture(true); repo.share("server", true); repo.posted(row()); repo.awaitIdle()
            assertFalse(repo.state.value.ready); assertNotNull(repo.state.value.error)
            assertTrue(repo.state.value.records.isEmpty()); assertNull(repo.contextFor("server", "Alice"))
        } finally { repo.close() }
    }
}
