package org.praxisog.hermes

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

/**
 * Home-screen widget that opens a fresh Hermes chat.
 *
 * The whole surface is the tap target rather than a small button inside it, because the
 * accept criterion is a new session in under two taps from cold start — a widget you have to
 * aim at costs a retry, which costs a tap.
 *
 * Day/night colours are supplied explicitly. The widget host renders outside this app's
 * Compose theme, so it cannot inherit one, and a widget pinned to a dark home screen with a
 * hardcoded light background looks broken.
 *
 * This is **not** a launcher shell: it is an ordinary app widget that starts this app's own
 * Activity. It hosts nothing.
 */
internal class HermesWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(R.color.widget_background))
                    .cornerRadius(16.dp)
                    .padding(12.dp)
                    .clickable(actionStartActivity(WidgetIntents.newChat(context))),
                verticalAlignment = Alignment.Vertical.CenterVertically,
                horizontalAlignment = Alignment.Horizontal.Start,
            ) {
                Text(
                    "Hermes",
                    style = TextStyle(
                        color = ColorProvider(R.color.widget_on_background),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Text(
                    "New chat",
                    style = TextStyle(
                        color = ColorProvider(R.color.widget_on_background),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }
    }
}

/** Registered in the manifest; hands the host our [HermesWidget]. */
internal class HermesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HermesWidget()
}
