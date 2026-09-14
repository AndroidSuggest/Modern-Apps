package com.vayunmathur.screentime.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.vayunmathur.library.widgets.DynamicThemeGlance
import com.vayunmathur.screentime.R
import com.vayunmathur.screentime.ui.DashboardActivity

private const val TAG = "ScreenTimeWidget"

internal val TotalMinutesKey = longPreferencesKey("screentime_total_minutes")
internal val TopLabelKey = stringPreferencesKey("screentime_top_label")
internal val TopMinutesKey = longPreferencesKey("screentime_top_minutes")

/**
 * Today's screen time at a glance.
 *
 * Shows the day's total plus the top app, refreshed whenever the coordinator reconciles
 * (timer spent, window boundary) and on the standard widget update path. Tapping opens the
 * dashboard. State is written by the app process into Glance preferences; the widget itself
 * never queries UsageStats (no cross-process permission story to maintain).
 */
class ScreenTimeGlanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val total = prefs[TotalMinutesKey] ?: 0L
            val topLabel = prefs[TopLabelKey]
            val topMinutes = prefs[TopMinutesKey] ?: 0L
            DynamicThemeGlance(context) {
                ScreenTimeWidgetContent(
                    context = context,
                    total = total,
                    topLabel = topLabel,
                    topMinutes = topMinutes,
                )
            }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        try {
            provideContent {
                DynamicThemeGlance(context) {
                    ScreenTimeWidgetContent(
                        context = context,
                        total = 187,
                        topLabel = "Example",
                        topMinutes = 64,
                    )
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "providePreview failed", t)
        }
    }
}

@Composable
private fun ScreenTimeWidgetContent(
    context: Context,
    total: Long,
    topLabel: String?,
    topMinutes: Long,
) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .cornerRadius(20.dp)
            .padding(16.dp)
            .clickable(
                actionStartActivity(
                    Intent(LocalContext.current, DashboardActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = GlanceModifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = context.getString(R.string.widget_today),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                ),
            )
            Text(
                text = formatWidgetMinutes(total),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 28.sp,
                    textAlign = TextAlign.Center,
                ),
            )
            if (topLabel != null) {
                Text(
                    text = "$topLabel · ${formatWidgetMinutes(topMinutes)}",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }
    }
}

private fun formatWidgetMinutes(minutes: Long): String =
    if (minutes < 60) "${minutes}m"
    else "${minutes / 60}h ${minutes % 60}m".trim()
