package com.vayunmathur.messages.gmessages

import android.util.Base64
import android.util.Log
import authentication.Authentication.AuthMessage
import authentication.Authentication.PairedData
import client.Client.ReceiveMessagesRequest
import com.google.protobuf.InvalidProtocolBufferException
import com.vayunmathur.library.network.NetworkDataStream
import events.Events.RPCPairData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rpc.Rpc.BugleRoute
import rpc.Rpc.IncomingRPCMessage
import rpc.Rpc.LongPollingPayload
import rpc.Rpc.RPCMessageData

/**
 * Port of `pkg/libgm/longpoll.go` (minus the ditto pinger).
 *
 * Long-poll response body framing:
 *   - Opens with `[[`
 *   - Each message: a pblite-encoded JSON array delimited by `,`
 *   - Closes with `]]`
 *
 * We accumulate bytes into a buffer; once the buffer parses as valid
 * pblite, we dispatch the [LongPollingPayload] and clear the
 * accumulator. Reconnect on any clean close or error with exponential
 * backoff (5 s → 60 s).
 */
class LongPoll(
    private val rpc: RpcClient,
    private val authProvider: () -> AuthData,
    private val sessionHandler: SessionHandler,
    private val onEvent: suspend (LongPollEvent) -> Unit,
    private val refreshToken: suspend () -> Unit = {},
) {
    private var job: Job? = null

    /** Number of old messages to skip at reconnect (set from ack payload). */
    @Volatile var skipCount: Int = 0

    /** Stable listen request ID reused across reconnections within a single
     *  poll session. Matches Go's `listenReqID` in `doLongPoll` which is
     *  generated once per session and reused for all iterations. */
    @Volatile private var listenReqId: String = java.util.UUID.randomUUID().toString()

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        listenReqId = java.util.UUID.randomUUID().toString()
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun loop() {
        var backoffMs = 0L
        var errorCount = 0
        while (true) {
            if (backoffMs > 0) {
                Log.i(TAG, "reconnecting in ${backoffMs / 1000}s")
                delay(backoffMs)
            }
            val ok = try {
                openAndRead()
            } catch (e: CancellationException) {
                throw e
            } catch (e: FatalLongPollException) {
                Log.e(TAG, "long-poll fatal: ${e.message}")
                onEvent(LongPollEvent.FatalError(e.message ?: "fatal long-poll error"))
                return
            } catch (t: Throwable) {
                Log.w(TAG, "long-poll error: ${t.message}")
                onEvent(LongPollEvent.TemporaryError(t.message ?: "unknown error"))
                false
            }
            if (!kotlin.coroutines.coroutineContext.isActive) return
            if (ok) {
                if (errorCount > 0) {
                    errorCount = 0
                    onEvent(LongPollEvent.Recovered)
                }
                backoffMs = 0L
            } else {
                errorCount++
                backoffMs = if (backoffMs == 0L) 5_000L else minOf(60_000L, backoffMs * 2)
            }
        }
    }

    private suspend fun openAndRead(): Boolean {
        try { refreshToken() } catch (t: Throwable) {
            Log.w(TAG, "token refresh before long-poll failed: ${t.message}")
        }
        val auth = authProvider()
        val token = auth.tachyonToken() ?: run {
            Log.w(TAG, "no tachyon token, can't open long-poll")
            return false
        }

        val payload = ReceiveMessagesRequest.newBuilder()
            .setAuth(
                AuthMessage.newBuilder()
                    .setRequestID(listenReqId)
                    .setNetwork(auth.authNetwork())
                    .setTachyonAuthToken(com.google.protobuf.ByteString.copyFrom(token))
                    .setConfigVersion(PairFlow.ConfigVersion)
            )
            .setUnknown(
                ReceiveMessagesRequest.UnknownEmptyObject2.newBuilder()
                    .setUnknown(ReceiveMessagesRequest.UnknownEmptyObject1.getDefaultInstance())
            )
            .build()

        Log.i(TAG, "opening long-poll (network=${auth.authNetwork()})")
        val url = if (auth.hasCookies())
            Endpoints.ReceiveMessagesUrlGoogle else Endpoints.ReceiveMessagesUrl
        return rpc.openLongPoll(url, payload) { response, stream ->
            val status = response.status
            if (status == 401 || status == 403) {
                throw FatalLongPollException("HTTP $status (unauthorized/forbidden)")
            }
            if (status !in 200..299 || stream == null) {
                Log.e(TAG, "long-poll HTTP $status")
                onEvent(LongPollEvent.TemporaryError("long-poll HTTP $status"))
                return@openLongPoll false
            }
            Log.i(TAG, "long-poll open (HTTP $status)")
            val sawEvents = consumeBody(stream)
            if (!sawEvents) {
                onEvent(LongPollEvent.NoDataReceived)
            }
            sawEvents
        }
    }

    private suspend fun consumeBody(channel: NetworkDataStream): Boolean {
        val readBuf = ByteArray(64 * 1024)
        val accumulator = ByteArrayBuilder()
        var sawAnyEvent = false
        var depth = 0
        var inString = false
        var escape = false
        var skippedOpening = false
        var openingBracketsSeen = 0
        var totalBytesRead = 0L

        while (!channel.isClosedForRead) {
            val n = channel.read(readBuf, 0, readBuf.size)
            if (n <= 0) break
            totalBytesRead += n
            if (totalBytesRead <= 512) {
                Log.i(
                    TAG,
                    "first $n bytes (acc=${totalBytesRead}): ${String(readBuf, 0, minOf(n, 200))}",
                )
            }

            for (i in 0 until n) {
                val b = readBuf[i]
                val c = b.toInt().toChar()

                if (!skippedOpening) {
                    if (c == '[') {
                        openingBracketsSeen++
                        if (openingBracketsSeen >= 2) skippedOpening = true
                        continue
                    }
                    if (c.isWhitespace()) continue
                    skippedOpening = true
                }

                if (depth == 0 && accumulator.isEmpty()) {
                    if (c == ',' || c.isWhitespace()) continue
                    if (c == ']') continue
                }

                accumulator.append(b)

                when {
                    escape -> escape = false
                    c == '\\' && inString -> escape = true
                    c == '"' -> inString = !inString
                    inString -> Unit
                    c == '[' || c == '{' -> depth++
                    c == ']' || c == '}' -> {
                        depth--
                        if (depth == 0) {
                            val snapshot = accumulator.toByteArray()
                            accumulator.reset()
                            val parsed = try {
                                PbLite.decode<LongPollingPayload>(
                                    String(snapshot, Charsets.UTF_8),
                                    LongPollingPayload.newBuilder(),
                                )
                            } catch (t: Throwable) {
                                Log.w(TAG, "pblite decode failed: ${t.message}; raw=${String(snapshot, Charsets.UTF_8).take(200)}")
                                null
                            }
                            if (parsed != null) {
                                dispatchPayload(parsed)
                                sawAnyEvent = true
                            }
                        }
                    }
                }
            }
        }
        Log.i(TAG, "long-poll closed (totalBytes=$totalBytesRead events=$sawAnyEvent)")
        return sawAnyEvent
    }

    private suspend fun dispatchPayload(payload: LongPollingPayload) {
        when {
            payload.hasData() -> {
                Log.d(TAG, "dispatch: data (bugleRoute=${payload.data.bugleRoute})")
                handleData(payload.data)
            }
            payload.hasHeartbeat() -> Log.d(TAG, "dispatch: heartbeat")
            payload.hasAck() -> {
                val count = payload.ack.count
                Log.d(TAG, "got startup ack count=$count")
                skipCount = count
            }
            payload.hasStartRead() -> Log.d(TAG, "got startRead marker")
            else -> Log.d(TAG, "long-poll unknown payload type")
        }
    }

    private suspend fun handleData(data: IncomingRPCMessage) {
        // Queue ack for every received message (port of Go's HandleRPCMsg)
        sessionHandler.queueMessageAck(data.responseID)

        when (data.bugleRoute) {
            BugleRoute.PairEvent -> handlePairEvent(data)
            BugleRoute.DataEvent -> handleDataEvent(data)
            BugleRoute.GaiaEvent -> handleGaiaEvent(data)
            else -> Log.d(TAG, "skipping bugle route ${data.bugleRoute}")
        }
    }

    private suspend fun handlePairEvent(data: IncomingRPCMessage) {
        val pair: RPCPairData = try {
            RPCPairData.parseFrom(data.messageData)
        } catch (e: InvalidProtocolBufferException) {
            Log.e(TAG, "failed to decode RPCPairData", e)
            return
        }
        when {
            pair.hasPaired() -> {
                val p: PairedData = pair.paired
                onEvent(
                    LongPollEvent.Paired(
                        mobileDeviceB64 = Base64.encodeToString(p.mobile.toByteArray(), Base64.NO_WRAP),
                        browserDeviceB64 = Base64.encodeToString(p.browser.toByteArray(), Base64.NO_WRAP),
                        tachyonTokenB64 = Base64.encodeToString(p.tokenData.tachyonAuthToken.toByteArray(), Base64.NO_WRAP),
                        tachyonTtlUs = p.tokenData.ttl,
                    )
                )
            }
            pair.hasRevoked() -> onEvent(LongPollEvent.Revoked)
            else -> Log.d(TAG, "unknown pair event")
        }
    }

    private suspend fun handleGaiaEvent(data: IncomingRPCMessage) {
        Log.d(TAG, "gaia event received (responseID=${data.responseID})")
        // Decrypt and deliver to waiter if applicable
        val msg: RPCMessageData = try {
            RPCMessageData.parseFrom(data.messageData)
        } catch (e: InvalidProtocolBufferException) {
            Log.e(TAG, "failed to decode RPCMessageData in gaia event", e)
            return
        }
        val incoming = IncomingRpc(
            responseId = data.responseID,
            requestId = msg.sessionID.takeIf { it.isNotEmpty() },
            action = msg.action,
            decryptedData = null,
            unencryptedData = if (msg.unencryptedData.size() > 0) msg.unencryptedData.toByteArray() else null,
        )
        val reqId = msg.sessionID
        if (reqId.isNotEmpty() && sessionHandler.deliverResponse(reqId, incoming)) return
        onEvent(LongPollEvent.Data(incoming))
    }

    private suspend fun handleDataEvent(data: IncomingRPCMessage) {
        val msg: RPCMessageData = try {
            RPCMessageData.parseFrom(data.messageData)
        } catch (e: InvalidProtocolBufferException) {
            Log.e(TAG, "failed to decode RPCMessageData", e)
            return
        }
        Log.d(
            TAG,
            "data event: action=${msg.action} sessionID=${msg.sessionID} encrypted=${msg.encryptedData.size()}B",
        )

        var decrypted: ByteArray? = null
        if (msg.encryptedData.size() > 0) {
            decrypted = try {
                authProvider().crypto().decrypt(msg.encryptedData.toByteArray())
            } catch (t: Throwable) {
                Log.w(TAG, "failed to decrypt data event payload: ${t.message}")
                null
            }
        } else if (msg.encryptedData2.size() > 0) {
            decrypted = try {
                authProvider().crypto().decrypt(msg.encryptedData2.toByteArray())
            } catch (t: Throwable) {
                Log.w(TAG, "failed to decrypt encryptedData2: ${t.message}")
                null
            }
        }

        val unencrypted = if (msg.unencryptedData.size() > 0) msg.unencryptedData.toByteArray() else null

        // Try to deliver to a waiter first (port of Go's receiveResponse
        // check in HandleRPCMsg). If consumed, return early — don't
        // process as an update event and don't decrement skipCount.
        val reqId = msg.sessionID
        if (reqId.isNotEmpty()) {
            val incoming = IncomingRpc(
                responseId = data.responseID,
                requestId = reqId,
                action = msg.action,
                decryptedData = decrypted,
                unencryptedData = unencrypted,
            )
            if (sessionHandler.deliverResponse(reqId, incoming)) return
        }

        // Only track old messages via skipCount for non-waiter messages
        // (matches Go where skipCount is checked after receiveResponse).
        val isOld = if (skipCount > 0) {
            skipCount--
            true
        } else false

        val incoming = IncomingRpc(
            responseId = data.responseID,
            requestId = reqId.takeIf { it.isNotEmpty() },
            action = msg.action,
            decryptedData = decrypted,
            unencryptedData = unencrypted,
            isOld = isOld,
        )
        onEvent(LongPollEvent.Data(incoming))
    }

    companion object {
        private const val TAG = "GMessages/LongPoll"
    }
}

/** Events the long-poll surfaces to the GMessagesClient. */
sealed interface LongPollEvent {
    data class Paired(
        val mobileDeviceB64: String,
        val browserDeviceB64: String,
        val tachyonTokenB64: String,
        val tachyonTtlUs: Long,
    ) : LongPollEvent

    data object Revoked : LongPollEvent

    data class Data(val msg: IncomingRpc) : LongPollEvent

    /** Fatal error — long-poll should NOT retry (e.g. HTTP 401/403). */
    data class FatalError(val reason: String) : LongPollEvent

    /** Long-poll cycle completed without any data events (2.7). */
    data object NoDataReceived : LongPollEvent

    /** Temporary long-poll error — will retry (2.1). */
    data class TemporaryError(val error: String) : LongPollEvent

    /** Recovered from a temporary error (2.1). */
    data object Recovered : LongPollEvent
}

/** Thrown when the long-poll encounters a non-retryable error (HTTP 401/403). */
private class FatalLongPollException(message: String) : Exception(message)

/** Minimal mutable byte buffer to avoid string-conversion overhead. */
private class ByteArrayBuilder(initial: Int = 4096) {
    private var buf = ByteArray(initial)
    private var len = 0
    fun isEmpty(): Boolean = len == 0
    fun append(b: Byte) {
        if (len == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[len++] = b
    }
    fun toByteArray(): ByteArray = buf.copyOf(len)
    fun reset() { len = 0 }
}
