package com.vayunmathur.communicate.data.rcs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * IMDN delivery/display receipt tracker (RFC 5438).
 *
 * Outgoing CPIM carries `imdn.Message-ID`; the far end answers with
 * `message/imdn+xml` reporting `positive-delivery` / `display` / `failed`.
 * Reports arrive via [RcsSessionManager.onSipRequest] → [onReportReceived].
 * Statuses feed the `SmsMessage.status` ticks through the repository layer.
 */
object RcsImdn {
    /** message-id → latest disposition. */
    private val _reports = MutableStateFlow<Map<String, ImdnDisposition>>(emptyMap())
    val reports: StateFlow<Map<String, ImdnDisposition>> = _reports.asStateFlow()

    fun onReportReceived(body: String) {
        if (!RcsFeature.enabled) return
        val (id, disposition) = parseImdnBody(body) ?: return
        _reports.value = _reports.value + (id to disposition)
    }

    fun statusFor(messageId: String): ImdnDisposition? = _reports.value[messageId]
}
