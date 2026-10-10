package org.praxisog.hermes

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.model.RunSubmission
import org.praxisog.hermes.model.Session

/** Destructive fixture tests: run ONLY on a dedicated emulator, never a personal phone. */
@RunWith(AndroidJUnit4::class)
class ServerProfileStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Before fun resetFixtureCredentials() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            BellpullRuntime.get(context).disconnect()
        }
        SecureConnectionStore(context).clear()
    }

    @Test fun encryptedProfilesAndEveryLocalNamespaceStaySeparate() {
        val store = SecureConnectionStore(context)
        val a = store.add("Desktop fixture", "https://a.example", "a-fixture-only")
        val b = store.add("Compute fixture", "https://b.example", "b-fixture-only")
        val cacheA = SessionCache(context, a.id)
        val cacheB = SessionCache(context, b.id)
        try {
            cacheA.write(listOf(Session("same-id", title = "Server A")), 1_700_000_000.0)
            cacheB.write(listOf(Session("same-id", title = "Server B")), 1_700_000_000.0)
            cacheA.writeDraft("same-id", TextFieldValue("Draft A", TextRange(3)), 7)
            cacheB.writeDraft("same-id", TextFieldValue("Draft B", TextRange(4)), 9)
            cacheA.writeRun(RunSubmission("same-request", "same-id", "A", 1.0, 7, emptySet()))
            cacheB.writeRun(RunSubmission("same-request", "same-id", "B", 1.0, 9, emptySet()))
            cacheA.writeReceipt("same-id", ConversationReceipt(1, 5))
            cacheB.writeReceipt("same-id", ConversationReceipt(8, 8))
            store.activate(a.id); store.activate(b.id); store.activate(a.id)
            assertEquals("a-fixture-only", SecureConnectionStore(context).loadKey())
            assertEquals("Server A", SessionCache(context, a.id).read()?.sessions?.single()?.title)
            assertEquals("Draft A", SessionCache(context, a.id).readDrafts()["same-id"]?.text)
            assertEquals("Draft B", SessionCache(context, b.id).readDrafts()["same-id"]?.text)
            assertEquals("A", cacheA.readRuns().single().text)
            assertEquals("B", cacheB.readRuns().single().text)
            assertEquals(5, cacheA.readReceipts()["same-id"]?.unreadReply)
            assertEquals(8, cacheB.readReceipts()["same-id"]?.unreadReply)
            store.activate(null)
            assertEquals("", SecureConnectionStore(context).loadKey())
            assertEquals(2, SecureConnectionStore(context).profiles().size)
            cacheA.clear(); store.remove(a.id)
            assertNull(SecureConnectionStore(context).connection(a.id))
            assertNull(SessionCache(context, a.id).read())
            assertEquals("B", SessionCache(context, b.id).readRuns().single().text)
            store.clear()
            assertTrue(SecureConnectionStore(context).profiles().isEmpty())
        } finally { cacheA.clear(); cacheB.clear(); store.clear() }
    }

    @Test fun revokedCacheCannotRecreateDeletedRunOrDraftData() {
        val id = "revoked-fixture"
        val stale = SessionCache(context, id)
        stale.writeDraft("same-id", TextFieldValue("old text"))
        stale.close()
        SessionCache(context, id).clear()
        assertFalse(stale.writeRun(RunSubmission("stale", "same-id", "must not return", 1.0, 1, emptySet())))
        stale.writeDraft("same-id", TextFieldValue("must not return"))
        val reopened = SessionCache(context, id)
        assertTrue(reopened.readRuns().isEmpty()); assertTrue(reopened.readDrafts().isEmpty())
        reopened.clear()
    }

    @Suppress("DEPRECATION")
    @Test fun realEncryptedLegacyConnectionMigratesWithoutLosingItsDraft() {
        val prefs = EncryptedSharedPreferences.create(context, "hermes_connection",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        prefs.edit().putString("base_url", "https://old.example").putString("api_key", "legacy-fixture-only").commit()
        val cache = SessionCache(context)
        cache.writeDraft("same-id", TextFieldValue("Keep my draft", TextRange(4)), 7)
        val migrated = SecureConnectionStore(context)
        assertEquals(SecureConnectionStore.LEGACY, migrated.activeId())
        assertEquals("legacy-fixture-only", migrated.loadKey())
        assertFalse(prefs.contains("api_key")); assertFalse(prefs.contains("base_url"))
        assertEquals("Keep my draft", SessionCache(context, migrated.activeId()!!).readDrafts()["same-id"]?.text)
        migrated.clear(); cache.clear()
    }
}
