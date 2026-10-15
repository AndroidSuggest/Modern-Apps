package com.vayunmathur.email.widget

import com.vayunmathur.library.widgets.MaWidgetReceiver

class EmailWidgetReceiver : MaWidgetReceiver<EmailWidget>(
    widgetClass = EmailWidget::class,
    receiverClass = EmailWidgetReceiver::class,
    widgetFactory = ::EmailWidget,
)
