package com.vayunmathur.calendar.glance

import com.vayunmathur.library.widgets.MaWidgetReceiver

class CalendarGlanceWidgetReceiver : MaWidgetReceiver<CalendarGlanceWidget>(
    widgetClass = CalendarGlanceWidget::class,
    receiverClass = CalendarGlanceWidgetReceiver::class,
    dateTriggers = true,
    widgetFactory = ::CalendarGlanceWidget,
)
