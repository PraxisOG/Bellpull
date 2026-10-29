package org.praxisog.hermes

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role

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
    val denseChatWindow: Boolean = true,
    val titleOnlyConversations: Boolean = true,
    val labeledControls: Boolean = false,
    val debuggingVoiceInterface: Boolean = false,
    val imagePreviewSize: ImagePreviewSize = ImagePreviewSize.MEDIUM,
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
        denseChatWindow = prefs.getBoolean("dense_chat_window", true),
        titleOnlyConversations = prefs.getBoolean("title_only_conversations", true),
        labeledControls = prefs.getBoolean("labeled_controls", false),
        imagePreviewSize = ImagePreviewSize.decode(prefs.getString("image_preview_size", null)),
        debuggingVoiceInterface = prefs.getBoolean("debugging_voice_interface", false),
    ))
        private set
    fun update(next: BellpullPreferences) {
        val validated = next.copy(customAccent = normalizedAccent(next.customAccent) ?: value.customAccent)
        value = validated
        prefs.edit().putString("appearance", validated.appearance.name)
            .putBoolean("collapse_thinking", validated.collapseThinking)
            .putBoolean("reduce_motion", validated.reduceMotion)
            .putBoolean("immersive_mode", validated.immersiveMode)
            .putBoolean("dense_chat_window", validated.denseChatWindow)
            .putBoolean("title_only_conversations", validated.titleOnlyConversations)
            .putBoolean("labeled_controls", validated.labeledControls)
            .putBoolean("debugging_voice_interface", validated.debuggingVoiceInterface)
            .putString("image_preview_size", validated.imagePreviewSize.name)
            .putString("palette", validated.palette.name)
            .putString("custom_accent", validated.customAccent).apply()
        prefs.edit().putBoolean("notifications", validated.notifications).apply()
    }
}

@Composable
internal fun SettingsWorkspace(preferences: BellpullPreferences, onChange: (BellpullPreferences) -> Unit,
    notificationsAvailable: Boolean = false, onNotificationsChange: (Boolean) -> Unit = {},
    serverProfiles: (@Composable () -> Unit)? = null,
    pluginVersions: () -> List<ServerPluginVersion> = { emptyList() },
    speechLab: (@Composable () -> Unit)? = null,
    voiceSettings: (@Composable () -> Unit)? = null,
    voiceOutputSettings: (@Composable () -> Unit)? = null,
    voiceAnnouncementSettings: (@Composable () -> Unit)? = null,
    phoneNotificationSettings: (@Composable () -> Unit)? = null,
    localLlmSettings: (@Composable () -> Unit)? = null,
    assistantSettings: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        serverProfiles?.let { SettingsSection("Servers", "Connections and profiles") { it() } }
        if (notificationsAvailable) {
            SettingsSection("Notifications", "Reply and approval alerts") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Reply notifications", style = MaterialTheme.typography.titleMedium)
                        Text("Replies and requests needing attention. Keeps a quiet connection notification while enabled.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = preferences.notifications, onCheckedChange = onNotificationsChange)
                }
            }
        }
        assistantSettings?.let { SettingsSection("Phone assistant", "Default assistant and phone actions") { it() } }
        phoneNotificationSettings?.let { SettingsSection("Phone notification context", "Local history and sharing with Hermes") { it() } }
        localLlmSettings?.let { SettingsSection("Local fallback", "Gemma on this phone") { it() } }
        SettingsSection("Appearance", "Theme, colors and preview") {
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
                    if (!preferences.denseChatWindow) Text("Hermes", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    Surface(color = Color.Transparent, shape = MaterialTheme.shapes.small,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Text("A little more you.", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        SettingsSection("Display", "Chat density, image previews, thinking, motion and system bars") {
            Text("Image preview size", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ImagePreviewSize.entries.forEach { size ->
                    FilterChip(selected = preferences.imagePreviewSize == size,
                        onClick = { onChange(preferences.copy(imagePreviewSize = size)) }, label = { Text(size.label) })
                }
            }
            Text("Previews load for server images up to 8 MiB. Tap an image to view it full size.", style = MaterialTheme.typography.bodySmall)
            PreferenceSwitch("Dense chat window", "Hide speaker names and action bars. Long-press a message for its actions.",
                preferences.denseChatWindow) { onChange(preferences.copy(denseChatWindow = it)) }
            PreferenceSwitch("Title-only conversations", "A compact list of chat titles. Turn off to include previews and statistics.",
                preferences.titleOnlyConversations) { onChange(preferences.copy(titleOnlyConversations = it)) }
            PreferenceSwitch("Larger labeled controls", "Show labels and larger icons on navigation controls.",
                preferences.labeledControls) { onChange(preferences.copy(labeledControls = it)) }
            PreferenceSwitch("Collapse thinking by default", "You can expand each thinking block.",
                preferences.collapseThinking) { onChange(preferences.copy(collapseThinking = it)) }
            PreferenceSwitch("Reduce motion", "Open panels and show new tool rows without animation.",
                preferences.reduceMotion) { onChange(preferences.copy(reduceMotion = it)) }
            PreferenceSwitch("Immersive mode", "Experimental. Hide system bars; swipe from an edge to reveal them.",
                preferences.immersiveMode) { onChange(preferences.copy(immersiveMode = it)) }
        }
        if (voiceSettings != null || speechLab != null) SettingsSection("Voice input", "Recognition, conversation controls and experiments") {
            SettingsSection("Using voice", "Tap for conversation; hold for dictation") {
                Text("Tap the microphone to start conversation mode; tap again to end. The pulsing button means voice is on (steady with Reduce motion). Hold to dictate: wait for the soft chime, speak, then release to put words in the draft for review.")
            }
            PreferenceSwitch("Debugging voice interface", "Open the detailed voice panel when tapping the microphone.",
                preferences.debuggingVoiceInterface) { onChange(preferences.copy(debuggingVoiceInterface = it)) }
            voiceSettings?.invoke()
            speechLab?.let { SettingsSection("Experimental", "On-device models and direct-audio benchmarks") { it() } }
        }
        voiceOutputSettings?.let { SettingsSection("Voice output", "Models, server offload and playback") { it() } }
        voiceAnnouncementSettings?.let { SettingsSection("Voice announcements", "Tool, search, thinking and working speech") { it() } }
        AboutSettings(pluginVersions)
    }
}

@Composable
internal fun SettingsSection(title: String, detail: String, initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse" else "Expand") {
                expanded = !expanded
            }.semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(detail, style = MaterialTheme.typography.bodySmall)
                }
                Text(if (expanded) "−" else "+", style = MaterialTheme.typography.titleLarge)
            }
            if (expanded) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
internal fun PreferenceSwitch(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
    }
}
