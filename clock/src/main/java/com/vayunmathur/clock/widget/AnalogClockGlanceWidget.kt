package com.vayunmathur.clock.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import com.vayunmathur.clock.MainActivity
import com.vayunmathur.clock.R
import com.vayunmathur.library.widgets.DynamicThemeGlance

/**
 * Material You analog clock widget. The dial, the hands and the orbiting second-hand dot are
 * drawn and ticked by the framework [android.widget.AnalogClock] in
 * [R.layout.analog_clock_widget], which Glance embeds verbatim through [AndroidRemoteViews]:
 * Glance has no self-ticking clock, and recomposing the widget once a second to fake one is
 * exactly what widget-update throttling exists to stop. Glance's job here is the layout wrapper
 * and the tap-to-open-clock action.
 */
class AnalogClockGlanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            DynamicThemeGlance(context) {
                AnalogClockContent()
            }
        }
    }

    // No providePreview: the widget picker preview comes from `previewLayout` in
    // analog_clock_widget_info.xml. Composing AndroidRemoteViews inside setWidgetPreviews
    // crashes the preview host on API 35+, and a preview without ticking hands would be a
    // downgrade from the real layout the launcher already renders.
}

@Composable
private fun AnalogClockContent() {
    val context = LocalContext.current
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .clickable(
                actionStartActivity(
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                )
            ),
    ) {
        AndroidRemoteViews(RemoteViews(context.packageName, R.layout.analog_clock_widget))
    }
}
