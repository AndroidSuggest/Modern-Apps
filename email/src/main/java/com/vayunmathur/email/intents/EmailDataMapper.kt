package com.vayunmathur.email.intents

import com.vayunmathur.email.data.EmailMessage
import com.vayunmathur.email.data.plainTextBody
import com.vayunmathur.library.intents.email.EmailData

private const val EMAIL_DATA_BODY_SNIPPET_LEN = 2000

fun EmailMessage.toEmailData() = EmailData(
    subject = subject,
    from = from,
    to = to,
    date = date,
    body = plainTextBody()?.take(EMAIL_DATA_BODY_SNIPPET_LEN),
    isRead = isRead,
)
