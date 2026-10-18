package org.praxisog.hermes

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class LocalLlmLabTest {
    @get:Rule val compose = createComposeRule()
    private val model = LocalModel("gemma", "Gemma 4 E2B Q4_0.gguf", 3349516256, "abc")
    private val loaded = LocalLabState(models = listOf(model), selected = model.id, ready = true,
        status = "CPU ready • entirely on this phone", validation = "GPU failed exact token validation. GPU chat is disabled; load CPU to continue.",
        messages = listOf(LocalTurn("user", "What is 2 plus 3?"), LocalTurn("assistant", "5", "complete")))
    @Test fun emptyCover() {
        compose.setContent { HermesTheme { LocalLlmScreen(LocalLabState()) } }
        compose.onNodeWithText("Load CPU").assertIsNotEnabled()
        compose.onNodeWithText("Send locally").assertIsNotEnabled()
        compose.onRoot().captureRoboImage()
    }
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun loadedUnfolded() {
        compose.setContent { HermesTheme { LocalLlmScreen(loaded) } }
        compose.onNodeWithText("Load GPU").assertIsNotEnabled()
        compose.onRoot().captureRoboImage()
    }
    @Test fun chatCover() {
        compose.setContent { HermesTheme { LocalLlmScreen(loaded.copy(draft = "Give another example")) } }
        compose.onNodeWithText("Hide setup").performClick()
        compose.onNodeWithText("Send locally").assertIsEnabled()
        compose.onRoot().captureRoboImage()
    }
    @Test @Config(qualifiers = "w840dp-h900dp-night") fun runningUnfolded() {
        var stopped = false
        compose.setContent { HermesTheme { LocalLlmScreen(loaded.copy(busy = true, status = "Vulkan: prompt 1/4…"), onStop = { stopped = true }) } }
        compose.onNodeWithText("Import GGUF").assertIsNotEnabled()
        compose.onNodeWithText("Stop").performClick()
        assertTrue(stopped)
        compose.onRoot().captureRoboImage()
    }
    @Test fun gpuRequiresMatchingModelValidation() {
        compose.setContent { HermesTheme { LocalLlmScreen(loaded.copy(gpuValidatedFor = "different")) } }
        compose.onNodeWithText("Load GPU").assertIsNotEnabled()
    }
    @Test fun rejectsEmptyDifferentAndUnstableTokenStreams() {
        val correct = listOf(listOf(1, 2), listOf(1, 2), listOf(3), listOf(3))
        assertTrue(LocalLlmLab.validationPass(correct, correct))
        assertFalse(LocalLlmLab.validationPass(emptyList(), emptyList()))
        assertFalse(LocalLlmLab.validationPass(List(4) { emptyList() }, List(4) { emptyList() }))
        assertFalse(LocalLlmLab.validationPass(correct, correct.map { it + 0 }))
        val unstable = correct.toMutableList().apply { this[1] = listOf(99) }
        assertFalse(LocalLlmLab.validationPass(unstable, unstable))
    }
    @Test fun resultsCover() {
        val report = """{"model":"Gemma 4 E2B Q4_0","sha256":"fa401b55…","gpu_exact_match":false,"runs":[{"backend":"CPU","offloaded_layers":0,"prompt":"What is 2 plus 3?","text":"5","prompt_tps":74.2,"generation_tps":12.1,"finish":"complete","observation":{"pss_mib":4565}},{"backend":"Vulkan","offloaded_layers":36,"prompt":"What is 2 plus 3?","text":"8","prompt_tps":8.0,"generation_tps":1.5,"finish":"complete","observation":{"pss_mib":4780}}]}"""
        assertTrue(localLlmReportSummary(report).contains("GPU failed validation"))
        compose.setContent { HermesTheme { LocalLlmResults(report) {} } }
        compose.onRoot().captureRoboImage()
    }
}
