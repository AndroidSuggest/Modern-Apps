package com.vayunmathur.photos.glance

import com.vayunmathur.library.widgets.MaWidgetReceiver

class PhotoGlanceWidgetReceiver : MaWidgetReceiver<PhotoGlanceWidget>(
    widgetClass = PhotoGlanceWidget::class,
    receiverClass = PhotoGlanceWidgetReceiver::class,
    widgetFactory = ::PhotoGlanceWidget,
)
