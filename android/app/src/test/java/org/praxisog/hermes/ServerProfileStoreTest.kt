package org.praxisog.hermes

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServerProfileStoreTest {
    private lateinit var prefs: SharedPreferences
    @Before fun setup() {
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("profile-fixture", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }
    @Test fun migrationIsAtomicAndKeepsTheLegacyCacheIdentity() {
        prefs.edit().putString("base_url", "https://old.example").putString("api_key", "fixture-only").commit()
        val store = SecureConnectionStore(prefs)
        assertEquals(SecureConnectionStore.LEGACY, store.activeId())
        assertEquals("fixture-only", store.loadKey())
        assertEquals("hermes_session_cache", SessionCache.fileName(store.activeId()!!))
        assertFalse(prefs.contains("base_url")); assertFalse(prefs.contains("api_key"))
        assertEquals(1, SecureConnectionStore(prefs).profiles().size)
    }
    @Test fun failedMigrationRetainsOriginalCredentials() {
        prefs.edit().putString("base_url", "https://old.example").putString("api_key", "fixture-only").commit()
        val failing = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val delegate = prefs.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor { delegate.putString(key, value); return this }
                    override fun remove(key: String?): SharedPreferences.Editor { delegate.remove(key); return this }
                    override fun commit() = false
                }
            }
        }
        assertThrows(IllegalStateException::class.java) { SecureConnectionStore(failing) }
        assertEquals("fixture-only", prefs.getString("api_key", null))
        assertFalse(prefs.contains("profiles"))
    }
    @Test fun signOutPersistsWithoutDiscardingProfiles() {
        val store = SecureConnectionStore(prefs)
        val a = store.add("Desktop", "https://a.example", "a-fixture")
        val b = store.add("Compute", "https://b.example", "b-fixture")
        store.activate(a.id); store.activate(b.id); store.activate(a.id)
        assertEquals("a-fixture", store.loadKey())
        store.activate(null)
        val reopened = SecureConnectionStore(prefs)
        assertNull(reopened.activeId()); assertEquals("", reopened.loadKey())
        assertEquals(2, reopened.profiles().size)
        assertEquals("b-fixture", reopened.connection(b.id)?.key)
    }
    @Test fun removingAnInactiveProfileDoesNotChangeTheActiveOne() {
        val store = SecureConnectionStore(prefs)
        val a = store.add("Desktop", "https://same.example", "a-fixture")
        val b = store.add("Compute", "https://same.example", "b-fixture")
        assertNotEquals(SessionCache.fileName(a.id), SessionCache.fileName(b.id))
        store.activate(a.id); store.remove(b.id)
        assertEquals(a.id, store.activeId()); assertNull(store.connection(b.id))
        store.remove(a.id)
        assertNull(store.activeId()); assertEquals("", store.loadKey())
    }
    @Test fun clearErasesEveryCredentialAndMetadata() {
        val store = SecureConnectionStore(prefs)
        store.activate(store.add("Desktop", "https://a.example", "a-fixture").id)
        store.add("Compute", "https://b.example", "b-fixture")
        assertTrue(store.clear())
        assertTrue(prefs.all.isEmpty())
        assertTrue(SecureConnectionStore(prefs).profiles().isEmpty())
    }
    @Test fun notificationsCannotTargetTheSameSessionOnAnotherProfile() {
        val intent = Intent().putExtra(ReplyNotifications.EXTRA_SESSION, "same-session")
            .putExtra(ReplyNotifications.EXTRA_PROFILE, "profile-a")
        assertTrue(ReplyNotifications.belongsTo(intent, "profile-a"))
        assertFalse(ReplyNotifications.belongsTo(intent, "profile-b"))
        assertFalse(ReplyNotifications.belongsTo(intent, null))
        intent.removeExtra(ReplyNotifications.EXTRA_PROFILE)
        assertTrue(ReplyNotifications.belongsTo(intent, SecureConnectionStore.LEGACY))
        assertFalse(ReplyNotifications.belongsTo(intent, "profile-a"))
    }
}
