package com.vayunmathur.weather.widget.glance

import android.content.Context
import com.vayunmathur.library.widgets.MaWidgetReceiver
import com.vayunmathur.weather.data.WeatherRefreshWorker

class WeatherGlanceWidgetReceiver : MaWidgetReceiver<WeatherGlanceWidget>(
    widgetClass = WeatherGlanceWidget::class,
    receiverClass = WeatherGlanceWidgetReceiver::class,
    widgetFactory = ::WeatherGlanceWidget,
) {
    override fun onEnabledExtra(context: Context) {
        WeatherRefreshWorker.scheduleHourlyRefresh(context)
    }
}
