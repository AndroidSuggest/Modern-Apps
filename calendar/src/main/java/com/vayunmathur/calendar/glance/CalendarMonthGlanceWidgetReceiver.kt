package com.vayunmathur.calendar.glance

import com.vayunmathur.library.widgets.MaWidgetReceiver

class CalendarMonthGlanceWidgetReceiver : MaWidgetReceiver<CalendarMonthGlanceWidget>(
    widgetClass = CalendarMonthGlanceWidget::class,
    receiverClass = CalendarMonthGlanceWidgetReceiver::class,
    dateTriggers = true,
    widgetFactory = ::CalendarMonthGlanceWidget,
)
