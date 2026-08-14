package com.vayunmathur.calendar.glance

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import com.vayunmathur.library.widgets.scheduleHourlyUpdate
import com.vayunmathur.library.widgets.updateWidgetPreviews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CalendarGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: CalendarGlanceWidget = CalendarGlanceWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        context.scheduleHourlyUpdate(CalendarGlanceWidget::class)
        context.updateWidgetPreviews(CalendarGlanceWidgetReceiver::class)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // Re-ensure periodic work is scheduled (covers reboot / work cancellation).
        context.scheduleHourlyUpdate(CalendarGlanceWidget::class)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_PROVIDER_CHANGED -> {
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        CalendarGlanceWidget().updateAll(context)
                    } catch (_: Exception) {
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}

