package org.praxisog.hermes

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.praxisog.hermes.core.HermesClient
import org.praxisog.hermes.model.Artifact
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtifactThumbnailUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun previewResizesOpensAndCanBeDisabledWithoutLosingFileActions() {
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val out = java.io.ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        val bytes = out.toByteArray()
        val client = HermesClient("https://fixture.invalid", { "fixture" }, httpClient = HermesClient.defaultHttpClient().newBuilder()
            .addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(bytes.toResponseBody()).build() }.build())
        val context = RuntimeEnvironment.getApplication()
        val source = ArtifactThumbnails(java.io.File(context.cacheDir, "thumbnail-ui"), client)
        val item = Artifact("a".repeat(32), "s", "Picture.png", "image/png", bytes.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, 1.0, "agent")
        var size by mutableStateOf(ImagePreviewSize.SMALL); var viewed = false
        try {
            runBlocking { assertNotNull(source.load(item)) }
            compose.setContent { HermesTheme {
                CompositionLocalProvider(LocalArtifactThumbnails provides source, LocalImagePreviewSize provides size) {
                    ArtifactRow(item, {}, { viewed = true })
                }
            } }
            // The clickable image area excludes the 8 dp inset on each edge.
            compose.onNodeWithTag("thumbnail-${item.id}", useUnmergedTree = true).assertHeightIsEqualTo(80.dp).performClick()
            assertTrue(viewed)
            compose.runOnIdle { size = ImagePreviewSize.LARGE }
            compose.onNodeWithTag("thumbnail-${item.id}", useUnmergedTree = true).assertHeightIsEqualTo(284.dp)
            compose.runOnIdle { size = ImagePreviewSize.OFF }
            compose.onNodeWithTag("thumbnail-${item.id}", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithText("View").assertExists(); compose.onNodeWithText("Download").assertExists()
            val settings = BellpullSettings(context)
            settings.update(settings.value.copy(imagePreviewSize = ImagePreviewSize.LARGE))
            assertEquals(ImagePreviewSize.LARGE, BellpullSettings(context).value.imagePreviewSize)
        } finally { source.close(); client.close() }
    }
}
