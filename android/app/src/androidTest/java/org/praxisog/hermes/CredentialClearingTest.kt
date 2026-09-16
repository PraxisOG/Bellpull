package org.praxisog.hermes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.model.Session

/**
 * The lost-phone path, against real encrypted storage.
 *
 * These are instrumentation tests because EncryptedSharedPreferences needs a real
 * AndroidKeyStore, which Robolectric does not provide — on the JVM they fail with
 * "AndroidKeyStore not found". Storage that only ever gets exercised by a mock is storage
 * nobody has actually tested.
 *
 * "Disconnect" claims to forget a root-equivalent key. A control that says that while
 * leaving the key in storage is worse than no control at all.
 */
@RunWith(AndroidJUnit4::class)
class CredentialClearingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val sessions = listOf(
        Session(id = "api_1_alpha", title = "Tailscale routing", preview = "Checked the ACLs"),
    )

    @Before fun startClean() {
        SecureConnectionStore(context).clear()
        SessionCache(context).clear()
    }

    @Test fun clearingTheStoreForgetsUrlAndKey() {
        val store = SecureConnectionStore(context)
        store.save("https://host.tail0b3e3a.ts.net", "a-test-key-not-a-real-one")
        assertEquals("https://host.tail0b3e3a.ts.net", store.loadUrl())

        store.clear()
        assertEquals("", store.loadUrl())
        assertEquals("", store.loadKey())
    }

    @Test fun clearingTheCacheForgetsSessionTitlesAndPreviews() {
        // The cache holds the opening lines of real conversations with an agent that has
        // shell access; a rotated key must not leave them readable on the device.
        val cache = SessionCache(context)
        cache.write(sessions, 1_700_000_000.0)
        assertEquals(1, cache.read()?.sessions?.size)

        cache.clear()
        assertNull(cache.read())
    }

    @Test fun theCacheRoundTripsSessionsAndItsTimestamp() {
        val cache = SessionCache(context)
        cache.write(sessions, 1_700_000_000.0)
        val cached = cache.read()
        assertEquals("api_1_alpha", cached?.sessions?.first()?.id)
        assertEquals("Tailscale routing", cached?.sessions?.first()?.title)
        assertEquals(1_700_000_000.0, cached?.cachedAtEpochSeconds ?: 0.0, 60.0)
    }
}
