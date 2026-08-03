package com.vayunmathur.clock.widget

import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * Receiver for [AnalogClockGlanceWidget]. Keeps the `...WidgetProvider` name (rather than the
 * `...GlanceWidgetReceiver` the other apps use) because the launcher keys placed widgets by
 * component name: renaming it would silently drop every clock widget already on a home screen.
 */
class AnalogClockWidgetProvider : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: AnalogClockGlanceWidget = AnalogClockGlanceWidget()
}
