package com.vayunmathur.calculator.widget

import com.vayunmathur.library.widgets.MaWidgetReceiver

/**
 * No periodic update is scheduled: a conversion only changes when the user changes it, and both
 * sides push an update when they do — the converter through `CalculatorViewModel`, the widget
 * through its own action callbacks.
 */
class UnitsGlanceWidgetReceiver : MaWidgetReceiver<UnitsGlanceWidget>(
    widgetClass = UnitsGlanceWidget::class,
    receiverClass = UnitsGlanceWidgetReceiver::class,
    hourly = false,
    widgetFactory = ::UnitsGlanceWidget,
)
