package com.vayunmathur.clock.widget

import com.vayunmathur.library.widgets.MaWidgetReceiver

/**
 * Receiver for [AnalogClockGlanceWidget]. Keeps the `...WidgetProvider` name (rather than the
 * `...GlanceWidgetReceiver` the other apps use) because the launcher keys placed widgets by
 * component name: renaming it would silently drop every clock widget already on a home screen.
 */
class AnalogClockWidgetProvider : MaWidgetReceiver<AnalogClockGlanceWidget>(
    widgetClass = AnalogClockGlanceWidget::class,
    receiverClass = AnalogClockWidgetProvider::class,
    // No periodic refresh (the framework AnalogClock ticks itself) and no composed
    // picker preview (the preview comes from `previewLayout` XML in the widget info).
    hourly = false,
    previews = false,
    widgetFactory = ::AnalogClockGlanceWidget,
)
