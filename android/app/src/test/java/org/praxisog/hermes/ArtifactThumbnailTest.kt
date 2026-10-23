package org.praxisog.hermes

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.Artifact
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtifactThumbnailTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun authenticatedVerifiedImageIsDownsampledCachedAndFencedOnProfileClose() = runBlocking {
        val image = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val output = java.io.ByteArrayOutputStream(); image.compress(Bitmap.CompressFormat.PNG, 100, output); image.recycle()
        val bytes = output.toByteArray(); var calls = 0
        val http = HermesClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            calls++
            assertEquals("Bearer fixture", chain.request().header("Authorization"))
            assertEquals("/p/api/bellpull/artifacts/${"a".repeat(32)}/content", chain.request().url.encodedPath)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(bytes.toResponseBody()).build()
        }.build()
        val client = HermesClient("https://fixture.invalid/p", { "fixture" }, httpClient = http)
        val item = Artifact("a".repeat(32), "s", "image.png", "image/png", bytes.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, 1.0, "agent")
        val source = ArtifactThumbnails(temporary.newFolder(), client)
        try {
            val thumbnail = source.load(item)!!
            assertTrue(thumbnail.width <= 768); assertEquals(2, thumbnail.width / thumbnail.height)
            assertSame(thumbnail, source.load(item)); assertEquals(1, calls)
            assertNull(source.load(item.copy(sha256 = "0".repeat(64))))
            assertEquals(2, calls)
            assertFalse(ArtifactThumbnails.eligible(item.copy(size = 9L * 1024 * 1024)))
            assertFalse(ArtifactThumbnails.eligible(item.copy(id = "../private")))
            source.close(); assertNull(source.load(item)); assertEquals(2, calls)
        } finally { source.close(); client.close() }
    }
}
