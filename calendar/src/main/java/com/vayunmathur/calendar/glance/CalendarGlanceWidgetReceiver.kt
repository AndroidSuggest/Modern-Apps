package com.vayunmathur.calendar.glance

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.vayunmathur.library.widgets.scheduleHourlyUpdate
import com.vayunmathur.library.widgets.updateWidgetPreviews

class CalendarGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: CalendarGlanceWidget = CalendarGlanceWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        context.scheduleHourlyUpdate(CalendarGlanceWidget::class)
        context.updateWidgetPreviews(CalendarGlanceWidgetReceiver::class)
    }
}

