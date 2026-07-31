@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.vayunmathur.messages.telegram.mtproto.proto

import kotlin.concurrent.atomics.*

object MessageId {
    private val lastNano = AtomicLong(0)
    private const val MIN_RESOLUTION_NANOS = 10L
    private const val MESSAGE_ID_MODULO = 4L

    // Offset (seconds) between the server clock and this device's clock, learned from
    // the handshake / new_session_created / inbound server msg_ids. msg_id encodes
    // unixtime<<32 and MUST use SERVER time, otherwise a skewed local clock (e.g.
    // hotel wifi) makes every outgoing msg_id invalid → bad_msg 16/17/32 and the
    // server rejects all authenticated requests. This is the SINGLE source of truth
    // for the offset — used for BOTH outgoing generation AND incoming validation, and
    // shared across connections (Telegram server time is universal), so generation and
    // validation can never diverge.
    private val timeOffsetSeconds = AtomicLong(0)
    private val offsetInitialized = AtomicBoolean(false)

    fun setTimeOffsetSeconds(seconds: Long) {
        timeOffsetSeconds.store(seconds)
        offsetInitialized.store(true)
    }

    fun timeOffsetSeconds(): Long = timeOffsetSeconds.load()

    fun isOffsetInitialized(): Boolean = offsetInitialized.load()

    /** Current server time in seconds (local clock + learned offset). */
    fun serverNowSeconds(): Long = System.currentTimeMillis() / 1000 + timeOffsetSeconds.load()

    private const val YIELD_CLIENT = 0L
    private const val YIELD_SERVER_RESPONSE = 1L
    private const val YIELD_FROM_SERVER = 3L

    const val MAX_PAST_SECONDS = 300L
    const val MAX_FUTURE_SECONDS = 30L

    enum class MessageType {
        UNKNOWN, FROM_CLIENT, SERVER_RESPONSE, FROM_SERVER
    }

    fun generate(): Long {
        val nowNano = (System.currentTimeMillis() + timeOffsetSeconds.load() * 1000L) * 1_000_000L
        val nano = lastNano.updateAndFetch { prev ->
            if (nowNano > prev) nowNano else prev + MIN_RESOLUTION_NANOS
        }
        return newMessageId(nano, YIELD_CLIENT)
    }

    private fun newMessageId(nowNano: Long, yield: Long): Long {
        val nano = 1_000_000_000L
        val intPart = nowNano / nano
        var fracPart = nowNano % nano
        fracPart = fracPart and -MESSAGE_ID_MODULO
        fracPart += yield
        return (intPart shl 32) or fracPart
    }

    fun reset() {
        lastNano.store(0)
    }

    fun type(id: Long): MessageType {
        return when (id % MESSAGE_ID_MODULO) {
            YIELD_CLIENT -> MessageType.FROM_CLIENT
            YIELD_SERVER_RESPONSE -> MessageType.SERVER_RESPONSE
            YIELD_FROM_SERVER -> MessageType.FROM_SERVER
            else -> MessageType.UNKNOWN
        }
    }

    fun timeSeconds(id: Long): Long = id ushr 32

    fun isServerType(id: Long): Boolean {
        val t = type(id)
        return t == MessageType.FROM_SERVER || t == MessageType.SERVER_RESPONSE
    }

    fun checkMessageId(nowSeconds: Long, rawId: Long): Boolean {
        val t = type(rawId)
        if (t != MessageType.FROM_SERVER && t != MessageType.SERVER_RESPONSE) return false
        if (nowSeconds > 0) {
            val created = timeSeconds(rawId)
            if (created < nowSeconds && nowSeconds - created > MAX_PAST_SECONDS) return false
            if (created > nowSeconds && created - nowSeconds > MAX_FUTURE_SECONDS) return false
        }
        return true
    }

    private const val BUF_SIZE = 100
    private val idBuf = LongArray(BUF_SIZE)
    private val idBufLock = Any()

    fun consume(msgId: Long): Boolean {
        synchronized(idBufLock) {
            var minIdx = 0
            var minId = 0L
            for (i in idBuf.indices) {
                if (idBuf[i] == msgId) return false
                if (idBuf[i] < minId) {
                    minIdx = i
                    minId = idBuf[i]
                }
            }
            if (msgId < minId) return false
            idBuf[minIdx] = msgId
            return true
        }
    }

    @Deprecated("Use consume() instead", ReplaceWith("!consume(msgId)"))
    fun isReplay(msgId: Long): Boolean = !consume(msgId)
}
