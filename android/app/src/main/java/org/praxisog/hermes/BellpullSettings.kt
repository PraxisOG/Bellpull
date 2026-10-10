package org.praxisog.hermes

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal enum class Appearance(val label: String) { SYSTEM("System"), LIGHT("White"), DARK("Black") }
internal enum class Palette(val label: String, val seed: Long) {
    NEUTRAL("Neutral", 0xFF303030), MAROON("Maroon", 0xFF59091E), BROWN("Dark brown", 0xFF381B18),
    TEAL("Original teal", 0xFF24665C), WALLPAPER("Android wallpaper", 0xFF686868), CUSTOM("Custom", 0xFF59091E),
}
internal data class BellpullPreferences(
    val appearance: Appearance = Appearance.SYSTEM,
    val collapseThinking: Boolean = true,
    val reduceMotion: Boolean = false,
    val palette: Palette = Palette.NEUTRAL,
    val customAccent: String = "59091E",
    val notifications: Boolean = false,
    val immersiveMode: Boolean = false,
)

internal fun normalizedAccent(text: String): String? = text.trim().removePrefix("#")
    .takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.uppercase(java.util.Locale.ROOT)

/** Only presentation preferences. Credentials stay in SecureConnectionStore. */
internal class BellpullSettings(context: Context) {
    private val prefs = context.getSharedPreferences("bellpull_presentation", Context.MODE_PRIVATE)
    var value by mutableStateOf(BellpullPreferences(
        appearance = runCatching { Appearance.valueOf(prefs.getString("appearance", "SYSTEM")!!) }
            .getOrDefault(Appearance.SYSTEM),
        collapseThinking = prefs.getBoolean("collapse_thinking", true),
        reduceMotion = prefs.getBoolean("reduce_motion", false),
        palette = runCatching { Palette.valueOf(prefs.getString("palette", "NEUTRAL")!!) }.getOrDefault(Palette.NEUTRAL),
        customAccent = normalizedAccent(prefs.getString("custom_accent", "59091E").orEmpty()) ?: "59091E",
        notifications = prefs.getBoolean("notifications", false),
        immersiveMode = prefs.getBoolean("immersive_mode", false),
    ))
        private set
    fun update(next: BellpullPreferences) {
        val validated = next.copy(customAccent = normalizedAccent(next.customAccent) ?: value.customAccent)
        value = validated
        prefs.edit().putString("appearance", validated.appearance.name)
            .putBoolean("collapse_thinking", validated.collapseThinking)
            .putBoolean("reduce_motion", validated.reduceMotion)
            .putBoolean("immersive_mode", validated.immersiveMode)
            .putString("palette", validated.palette.name)
            .putString("custom_accent", validated.customAccent).apply()
        prefs.edit().putBoolean("notifications", validated.notifications).apply()
    }
}

@Composable
internal fun SettingsWorkspace(preferences: BellpullPreferences, onChange: (BellpullPreferences) -> Unit,
    notificationsAvailable: Boolean = false, onNotificationsChange: (Boolean) -> Unit = {}) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (notificationsAvailable) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Reply notifications", style = MaterialTheme.typography.titleMedium)
                    Text("Replies and requests needing attention. Keeps a quiet connection notification while enabled.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = preferences.notifications, onCheckedChange = onNotificationsChange)
            }
        }
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Appearance.entries.forEach { choice ->
                FilterChip(selected = choice == preferences.appearance,
                    onClick = { onChange(preferences.copy(appearance = choice)) }, label = { Text(choice.label) })
            }
        }
        Text("Color scheme", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Palette.entries.forEach { choice ->
                val supported = choice != Palette.WALLPAPER || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                FilterChip(selected = choice == preferences.palette, enabled = supported,
                    onClick = { onChange(preferences.copy(palette = choice)) }, label = { Text(choice.label) },
                    leadingIcon = {
                        Box(Modifier.size(14.dp).background(
                            if (choice == Palette.WALLPAPER) MaterialTheme.colorScheme.primary
                            else if (choice == Palette.CUSTOM) accentColor(preferences.customAccent)
                            else Color(choice.seed), CircleShape))
                    })
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Text("Wallpaper colors require Android 12 or later.", style = MaterialTheme.typography.bodySmall)
        } else if (preferences.palette == Palette.WALLPAPER) {
            Text("Uses your Android wallpaper and system color palette.", style = MaterialTheme.typography.bodySmall)
        }
        if (preferences.palette == Palette.CUSTOM) {
            var accent by rememberSaveable(preferences.customAccent) { mutableStateOf(preferences.customAccent) }
            val valid = normalizedAccent(accent)
            OutlinedTextField(value = accent, onValueChange = { accent = it }, label = { Text("Accent color") },
                prefix = { Text("#") }, singleLine = true, isError = valid == null,
                supportingText = { Text(if (valid == null) "Enter six hex digits, for example 59091E." else "Surfaces and chat bubbles follow this color.") },
                modifier = Modifier.fillMaxWidth())
            Button(onClick = { valid?.let { onChange(preferences.copy(customAccent = it)) } },
                enabled = valid != null && valid != preferences.customAccent) { Text("Apply color") }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Preview", style = MaterialTheme.typography.labelMedium)
                Surface(color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.align(Alignment.End)) {
                    Text("Your message", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
                Text("Hermes", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Text("A little more you.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        HorizontalDivider()
        PreferenceSwitch("Collapse thinking by default", "You can expand each thinking block.",
            preferences.collapseThinking) { onChange(preferences.copy(collapseThinking = it)) }
        PreferenceSwitch("Reduce motion", "Open panels and show new tool rows without animation.",
            preferences.reduceMotion) { onChange(preferences.copy(reduceMotion = it)) }
        PreferenceSwitch("Immersive mode", "Experimental. Hide system bars; swipe from an edge to reveal them.",
            preferences.immersiveMode) { onChange(preferences.copy(immersiveMode = it)) }
    }
}

@Composable
private fun PreferenceSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
