package org.praxisog.hermes

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import android.os.Build
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Original palette retained as a selectable preset. Semantic warning/error roles stay distinct.
private val DayColors = lightColorScheme(
    primary = Color(0xFF24665C), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9EDE5), onPrimaryContainer = Color(0xFF143E37),
    secondary = Color(0xFF51645C), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4ECE5), onSecondaryContainer = Color(0xFF24382F),
    tertiary = Color(0xFF805A20), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFEAC4), onTertiaryContainer = Color(0xFF50360F),
    background = Color(0xFFF9FAF7), onBackground = Color(0xFF202C28),
    surface = Color(0xFFF9FAF7), onSurface = Color(0xFF202C28),
    surfaceVariant = Color(0xFFEAF0EB), onSurfaceVariant = Color(0xFF59675F),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF0F3EE),
    surfaceContainer = Color(0xFFEAEFE9), surfaceContainerHigh = Color(0xFFE3E9E2),
    surfaceContainerHighest = Color(0xFFDDE4DD),
    outline = Color(0xFF7A8980), outlineVariant = Color(0xFFD1DBD2),
    error = Color(0xFFAB362E), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE3DD), onErrorContainer = Color(0xFF64251E),
)

private val NightColors = darkColorScheme(
    primary = Color(0xFF9DD5C4), onPrimary = Color(0xFF073B30),
    primaryContainer = Color(0xFF254B41), onPrimaryContainer = Color(0xFFD4EFE3),
    secondary = Color(0xFFB6CDC0), onSecondary = Color(0xFF23382D),
    secondaryContainer = Color(0xFF31473B), onSecondaryContainer = Color(0xFFD9E9DD),
    tertiary = Color(0xFFE6C080), onTertiary = Color(0xFF442E0A),
    tertiaryContainer = Color(0xFF513D1F), onTertiaryContainer = Color(0xFFFFDFAB),
    background = Color(0xFF111B17), onBackground = Color(0xFFE0E9E1),
    surface = Color(0xFF111B17), onSurface = Color(0xFFE0E9E1),
    surfaceVariant = Color(0xFF26352D), onSurfaceVariant = Color(0xFFB1C2B6),
    surfaceContainerLowest = Color(0xFF0C1511), surfaceContainerLow = Color(0xFF18241E),
    surfaceContainer = Color(0xFF1E2D25), surfaceContainerHigh = Color(0xFF29392F),
    surfaceContainerHighest = Color(0xFF33443A),
    outline = Color(0xFF82968A), outlineVariant = Color(0xFF34493C),
    error = Color(0xFFFFB4A9), onError = Color(0xFF651D17),
    errorContainer = Color(0xFF572922), onErrorContainer = Color(0xFFFFDAD3),
)

private val HermesTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
)

internal fun accentColor(hex: String): Color = Color(0xFF000000L or (normalizedAccent(hex) ?: "59091E").toLong(16))

internal fun contrastRatio(a: Color, b: Color): Float {
    val first = a.luminance(); val second = b.luminance()
    return (maxOf(first, second) + .05f) / (minOf(first, second) + .05f)
}

/** Bring custom accents to readable text contrast without assuming the user's hue is dark. */
private fun readableAccent(seed: Color, background: Color, dark: Boolean): Color {
    val target = if (dark) Color.White else Color.Black
    for (step in 0..100) {
        val candidate = lerp(seed, target, step / 100f)
        if (contrastRatio(candidate, background) >= 4.5f &&
            contrastRatio(candidate, lerp(background, candidate, if (dark) .21f else .12f)) >= 4.5f) return candidate
    }
    return target
}

internal fun paletteColors(palette: Palette, dark: Boolean, customAccent: String = "59091E"): ColorScheme {
    val base = if (dark) NightColors else DayColors
    if (palette == Palette.TEAL) return base
    val seed = if (palette == Palette.CUSTOM) accentColor(customAccent) else Color(if (palette == Palette.WALLPAPER) Palette.NEUTRAL.seed else palette.seed)
    val neutral = palette == Palette.NEUTRAL || palette == Palette.WALLPAPER
    val background = if (neutral) { if (dark) Color.Black else Color.White }
        else if (dark) lerp(seed, Color.Black, .78f) else lerp(seed, Color.White, .98f)
    val ink = if (dark) Color(0xFFF4F1F2) else Color(0xFF201D1E)
    val primary = readableAccent(seed, background, dark)
    val container = lerp(background, primary, if (dark) .21f else .12f)
    fun surface(amount: Float) = lerp(background, primary, amount)
    return base.copy(
        primary = primary, onPrimary = if (contrastRatio(primary, Color.White) >= 4.5f) Color.White else Color.Black,
        primaryContainer = container, onPrimaryContainer = ink,
        secondary = primary, onSecondary = if (dark) Color.Black else Color.White,
        secondaryContainer = surface(.10f), onSecondaryContainer = ink,
        background = background, onBackground = ink, surface = background, onSurface = ink,
        surfaceVariant = surface(.12f), onSurfaceVariant = if (dark) Color(0xFFCCC5C7) else Color(0xFF655D60),
        surfaceContainerLowest = background, surfaceContainerLow = surface(.05f),
        surfaceContainer = surface(.08f), surfaceContainerHigh = surface(.12f), surfaceContainerHighest = surface(.16f),
        outline = if (dark) Color(0xFF9B9194) else Color(0xFF807679), outlineVariant = surface(.23f),
        inverseSurface = if (dark) Color(0xFFF5F1F2) else Color(0xFF302A2C),
        inverseOnSurface = if (dark) Color(0xFF302A2C) else Color(0xFFF5F1F2),
        inversePrimary = readableAccent(seed, if (dark) Color.White else Color.Black, !dark),
        surfaceTint = primary,
    )
}

@Composable
internal fun HermesTheme(
    appearance: Appearance = Appearance.SYSTEM,
    palette: Palette = Palette.NEUTRAL,
    customAccent: String = "59091E",
    content: @Composable () -> Unit,
) {
    val dark = appearance == Appearance.DARK || (appearance == Appearance.SYSTEM && isSystemInDarkTheme())
    val context = LocalContext.current
    val colors = if (palette == Palette.WALLPAPER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val dynamic = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        val semantic = if (dark) NightColors else DayColors
        // Warning and refusal retain their meaning when the wallpaper changes.
        dynamic.copy(tertiary = semantic.tertiary, onTertiary = semantic.onTertiary,
            tertiaryContainer = semantic.tertiaryContainer, onTertiaryContainer = semantic.onTertiaryContainer,
            error = semantic.error, onError = semantic.onError,
            errorContainer = semantic.errorContainer, onErrorContainer = semantic.onErrorContainer)
    } else paletteColors(palette, dark, customAccent)
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        var current: android.content.Context = view.context
        while (current is android.content.ContextWrapper && current !is android.app.Activity) current = current.baseContext
        (current as? android.app.Activity)?.window?.let { window ->
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = HermesTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}

/** Decorative mark. The adjacent wordmark or heading supplies its accessible name. */
@Composable
internal fun HermesMark(modifier: Modifier = Modifier, size: Dp = 36.dp) {
    Surface(
        modifier = modifier.size(size).clearAndSetSemantics {},
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(size * 0.3f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("H", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.62f).sp)
        }
    }
}

/** Bellpull's supplied artwork, without recoloring or redrawing it. */
@Composable
internal fun BellpullMark(modifier: Modifier = Modifier, size: Dp = 48.dp) {
    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.bellpull_icon),
        contentDescription = null, modifier = modifier.size(size).clip(RoundedCornerShape(size * .25f)))
}
