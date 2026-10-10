package org.praxisog.hermes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h850dp")
class ThemeScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private fun capture(palette: Palette, appearance: Appearance, settings: Boolean = false) {
        val preferences = BellpullPreferences(appearance = appearance, palette = palette, customAccent = "336699")
        compose.setContent {
            HermesTheme(appearance, palette, preferences.customAccent) {
                Surface {
                    if (settings) SettingsWorkspace(preferences, {})
                    else SessionDetailScreen(UiState.Ready(UiFixtures.history), TurnState.Idle, {}, {}, {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.onRoot().captureRoboImage()
    }
    @Test fun maroonWhiteCover() = capture(Palette.MAROON, Appearance.LIGHT)
    @Test fun maroonBlackCover() = capture(Palette.MAROON, Appearance.DARK)
    @Test @Config(qualifiers = "w840dp-h900dp") fun brownBlackUnfolded() = capture(Palette.BROWN, Appearance.DARK)
    @Test fun customSettingsCover() = capture(Palette.CUSTOM, Appearance.LIGHT, settings = true)
    @Test fun wallpaperSettingsCover() = capture(Palette.WALLPAPER, Appearance.DARK, settings = true)
    @Test fun launcherIcon() {
        compose.setContent {
            Surface(Modifier.size(128.dp)) {
                AndroidView(factory = { context -> android.widget.ImageView(context).apply {
                    setImageDrawable(context.getDrawable(R.mipmap.ic_launcher))
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                } }, modifier = Modifier.padding(16.dp))
            }
        }
        compose.onRoot().captureRoboImage()
    }
}
