package com.vayunmathur.email.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.vayunmathur.library.widgets.scheduleHourlyUpdate
import com.vayunmathur.library.widgets.updateWidgetPreviews

class EmailWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: EmailWidget = EmailWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        context.scheduleHourlyUpdate(EmailWidget::class)
        context.updateWidgetPreviews(EmailWidgetReceiver::class)
    }
}
