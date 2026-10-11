package org.praxisog.hermes

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.HermesResult

/** Run only with -PbellpullDeviceFixture=true and tools/stock-device-fixture.py --streaming. */
@RunWith(AndroidJUnit4::class)
class ConversationSourcesDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Suppress("DEPRECATION")
    @Test fun oldApiDefaultMigratesAndStockConversationsLoadAcrossSources() {
        check(context.packageName == "org.praxisog.hermes.fixture") { "Use the separate device fixture application ID" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { BellpullRuntime.get(context).disconnect() }
        val secure = SecureConnectionStore(context)
        secure.clear() // Only the explicitly guarded fixture sandbox.
        val profile = secure.add("Stock fixture", "http://127.0.0.1:18646", "bellpull-stock-device-fixture")
        secure.activate(profile.id)
        val prefs = EncryptedSharedPreferences.create(context, SessionCache.fileName(profile.id),
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        prefs.edit().putString("session_source", "api_server").commit()
        val cache = SessionCache(context, profile.id)
        assertNull(cache.sessionSource())
        cache.rememberSessionSource("cli")
        assertEquals("cli", SessionCache(context, profile.id).sessionSource())
        cache.rememberSessionSource(null)
        cache.rememberSession("phone_stream")

        val client = HermesClient("http://127.0.0.1:18646", { "bellpull-stock-device-fixture" })
        val capabilities = (client.capabilities() as HermesResult.Ok).value
        assertTrue(capabilities.features["bellpull_reasoning_streaming"] == true)
        val runtime = BellpullRuntime.get(context)
        instrumentation.runOnMainSync { runtime.prepare(profile.id); runtime.connect(client, capabilities) }
        val until = System.nanoTime() + 15_000_000_000L
        var loaded = emptySet<String>()
        while (System.nanoTime() < until) {
            instrumentation.runOnMainSync {
                assertNull(runtime.store!!.sessionSource)
                loaded = (runtime.store!!.sessions as? UiState.Ready)?.value.orEmpty().mapNotNull { it.source }.toSet()
            }
            if (loaded.containsAll(setOf("api_server", "cli", "tui", "telegram"))) break
            Thread.sleep(50)
        }
        assertTrue("Actual stock session sources: $loaded", loaded.containsAll(setOf("api_server", "cli", "tui", "telegram")))
        instrumentation.runOnMainSync { runtime.disconnect() }
        // Leave the isolated profile configured for manual on-glass streaming checks.
    }
}
