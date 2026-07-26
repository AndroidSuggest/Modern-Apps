package com.vayunmathur.photos.glance

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.vayunmathur.library.widgets.scheduleHourlyUpdate
import com.vayunmathur.library.widgets.updateWidgetPreviews

class PhotoGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: PhotoGlanceWidget = PhotoGlanceWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        context.scheduleHourlyUpdate(PhotoGlanceWidget::class)
        context.updateWidgetPreviews(PhotoGlanceWidgetReceiver::class)
    }
}

