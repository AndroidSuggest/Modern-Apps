package com.vayunmathur.messages.whatsapp

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-app diagnostics sink. The dev build strips Log output from logcat, so during pairing
 * we mirror every handshake step, server response and error into an observable list that the
 * login screen renders on-screen.
 */
object WhatsAppDiag {
    private const val MAX_ENTRIES = 300
    private val timeFmt = LocalTime.Format {
        hour(); char(':'); minute(); char(':'); second(); char('.'); secondFraction(3)
    }

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    @Synchronized
    fun log(tag: String, msg: String) {
        val line = "${Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).time.format(timeFmt)} $tag  $msg"
        _log.value = (_log.value + line).takeLast(MAX_ENTRIES)
        Log.i(tag, msg)
    }

    @Synchronized
    fun clear() {
        _log.value = emptyList()
    }

    /** Short hex preview of a byte array for inspecting raw frames. */
    fun preview(data: ByteArray, max: Int = 24): String {
        val n = minOf(data.size, max)
        val hex = StringBuilder()
        for (i in 0 until n) {
            hex.append(String.format("%02x", data[i]))
        }
        return "${data.size}B[${hex}${if (data.size > max) "…" else ""}]"
    }
}
