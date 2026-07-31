package com.vayunmathur.messages.gmessages

import android.util.Log
import com.google.protobuf.Message
import com.vayunmathur.messages.gmessages.PairFlow.ConfigVersion
import authentication.Authentication.AuthMessage
import client.Client.AckMessageRequest
import rpc.Rpc.ActionType
import rpc.Rpc.BugleRoute
import rpc.Rpc.MessageType
import rpc.Rpc.OutgoingRPCData
import rpc.Rpc.OutgoingRPCMessage
import rpc.Rpc.OutgoingRPCResponse
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.google.protobuf.ByteString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Port of `pkg/libgm/session_handler.go`.
 *
 * Responsibilities:
 *  - Build outgoing RPC envelopes (sets the Auth fields, encrypts the
 *    payload with [AuthData.crypto], assembles the OutgoingRPCMessage).
 *  - Send via [RpcClient.postPbLite].
 *  - Match relay responses (delivered out-of-band via the long-poll)
 *    back to in-flight requests by request-ID.
 *  - Queue and periodically send message acks to the relay.
 */
class SessionHandler(
    private val rpc: RpcClient,
    private val authProvider: () -> AuthData,
) {
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<IncomingRpc>>()

    @Volatile
    private var currentSessionId: String = UUID.randomUUID().toString()

    fun currentSessionId(): String = currentSessionId

    // --- Ack interval (port of Go's ackMap + ackTicker) ---
    private val ackMapMutex = Mutex()
    private val ackMap = mutableListOf<String>()
    private var ackJob: Job? = null

    /**
     * Queue a message ID for acknowledgment. Called for every incoming
     * RPC message from the long-poll — matches Go's `queueMessageAck`.
     */
    suspend fun queueMessageAck(messageId: String) {
        ackMapMutex.withLock {
            if (messageId !in ackMap) {
                ackMap.add(messageId)
                Log.d(TAG, "queued ack for $messageId")
            }
        }
    }

    /**
     * Start the periodic ack sender (every 5 seconds).
     * Matches Go's `startAckInterval`.
     */
    fun startAckInterval(scope: CoroutineScope) {
        if (ackJob?.isActive == true) return
        ackJob = scope.launch {
            while (isActive) {
                delay(5_000)
                sendAckRequest()
            }
        }
    }

    /**
     * Send queued acks to the relay. Port of Go's `sendAckRequest`.
     */
    suspend fun sendAckRequest() {
        val toAck = ackMapMutex.withLock {
            if (ackMap.isEmpty()) return
            val copy = ackMap.toList()
            ackMap.clear()
            copy
        }
        val auth = authProvider()
        val browser = auth.browser()
        val ackMessages = toAck.map { reqId ->
            AckMessageRequest.Message.newBuilder()
                .setRequestID(reqId)
                .apply { if (browser != null) setDevice(browser) }
                .build()
        }
        val payload = AckMessageRequest.newBuilder()
            .setAuthData(
                AuthMessage.newBuilder()
                    .setRequestID(UUID.randomUUID().toString())
                    .setTachyonAuthToken(
                        com.google.protobuf.ByteString.copyFrom(
                            auth.tachyonToken() ?: return
                        )
                    )
                    .setNetwork(auth.authNetwork())
                    .setConfigVersion(PairFlow.ConfigVersion)
            )
            .setEmptyArr(util.Util.EmptyArr.getDefaultInstance())
            .addAllAcks(ackMessages)
            .build()
        val url = if (auth.hasCookies())
            Endpoints.AckMessagesUrlGoogle else Endpoints.AckMessagesUrl
        try {
            rpc.postPbLite(url, payload)
            Log.d(TAG, "sent ${toAck.size} acks")
        } catch (t: Throwable) {
            Log.e(TAG, "failed to send acks: ${t.message}")
        }
    }

    /**
     * Send an RPC and wait up to [timeoutMs] ms for the matching
     * response. Returns null on timeout.
     */
    suspend fun sendAndWait(
        action: ActionType,
        payload: Message?,
        timeoutMs: Long = 30_000,
        messageType: MessageType = MessageType.BUGLE_MESSAGE,
    ): IncomingRpc? {
        val params = SendMessageParams(
            action = action,
            data = payload,
            messageType = messageType,
        )
        val (requestId, envelope) = buildMessage(params)
        val deferred = CompletableDeferred<IncomingRpc>()
        waiters[requestId] = deferred
        Log.i(TAG, "sendAndWait $action requestID=$requestId")
        try {
            val url = sendMessageUrl()
            val resp = rpc.postPbLite(url, envelope)
            Log.i(TAG, "SendMessage HTTP ${resp.status}, response body: ${resp.text.take(500)}")
            if (resp.status !in 200..299) {
                Log.w(TAG, "sendAndWait $action: HTTP ${resp.status}")
                waiters.remove(requestId)
                return null
            }
            Log.d(TAG, "sendAndWait $action: HTTP 200, waiting for relay response…")
            return withTimeoutOrNull(timeoutMs) { deferred.await() }.also {
                waiters.remove(requestId)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "sendAndWait $action failed", t)
            waiters.remove(requestId)
            return null
        }
    }

    /** Fire-and-forget: don't register a waiter. Used for things like
     *  ack messages where we don't care about the response. */
    suspend fun sendNoWait(action: ActionType, payload: Message?): Boolean {
        val params = SendMessageParams(action = action, data = payload)
        val (_, envelope) = buildMessage(params)
        return try {
            val url = sendMessageUrl()
            val resp = rpc.postPbLite(url, envelope)
            resp.status in 200..299
        } catch (t: Throwable) {
            Log.e(TAG, "sendNoWait $action failed", t)
            false
        }
    }

    /**
     * Send with full params control (DontEncrypt, CustomTTL, etc.).
     * Used by GAIA pairing and advanced flows.
     */
    suspend fun sendMessageWithParams(params: SendMessageParams): IncomingRpc? {
        val (requestId, envelope) = buildMessage(params)
        val deferred = CompletableDeferred<IncomingRpc>()
        waiters[requestId] = deferred
        try {
            val url = sendMessageUrl()
            val resp = rpc.postPbLite(url, envelope)
            if (resp.status !in 200..299) {
                waiters.remove(requestId)
                return null
            }
            // Short-circuit timeout matching Go's 5s ping-trigger behavior
            return withTimeoutOrNull(30_000) { deferred.await() }.also {
                waiters.remove(requestId)
            }
        } catch (t: Throwable) {
            waiters.remove(requestId)
            return null
        }
    }

    /**
     * Send async — return the deferred so caller can wait with custom timeout.
     * Used by GAIA pairing.
     */
    suspend fun sendAsyncMessage(params: SendMessageParams): Pair<String, CompletableDeferred<IncomingRpc>>? {
        val (requestId, envelope) = buildMessage(params)
        val deferred = CompletableDeferred<IncomingRpc>()
        waiters[requestId] = deferred
        return try {
            val url = sendMessageUrl()
            val resp = rpc.postPbLite(url, envelope)
            if (resp.status !in 200..299) {
                waiters.remove(requestId)
                null
            } else {
                requestId to deferred
            }
        } catch (t: Throwable) {
            waiters.remove(requestId)
            null
        }
    }

    /** Fire-and-forget with full params. */
    suspend fun sendMessageNoResponse(params: SendMessageParams): Boolean {
        val (_, envelope) = buildMessage(params)
        return try {
            val url = sendMessageUrl()
            val resp = rpc.postPbLite(url, envelope)
            resp.status in 200..299
        } catch (t: Throwable) {
            Log.e(TAG, "sendMessageNoResponse ${params.action} failed", t)
            false
        }
    }

    /**
     * The "wake up" call libgm makes after each pair / reconnect.
     * Resets the internal session UUID (matching Go's ResetSessionID),
     * then sends GET_UPDATES with requestID == the new sessionID and
     * TTL = 0. Without this, the relay won't forward data events.
     */
    suspend fun setActiveSession(): Boolean {
        currentSessionId = UUID.randomUUID().toString()
        Log.i(TAG, "setActiveSession (GET_UPDATES, requestID=sessionID=$currentSessionId)")
        return sendMessageNoResponse(SendMessageParams(
            action = ActionType.GET_UPDATES,
            omitTtl = true,
            requestId = currentSessionId,
        ))
    }

    /** Called by the long-poll dispatcher when a response arrives.
     *  Returns true if the response was consumed by a waiter (matching
     *  Go's `receiveResponse` return semantics). */
    fun deliverResponse(requestId: String, msg: IncomingRpc): Boolean {
        val auth = authProvider()
        if (auth.hasCookies()) {
            when (msg.action) {
                ActionType.CREATE_GAIA_PAIRING_CLIENT_INIT,
                ActionType.CREATE_GAIA_PAIRING_CLIENT_FINISHED -> { /* allow */ }
                else -> {
                    if (msg.unencryptedData != null && msg.decryptedData == null) {
                        return false
                    }
                }
            }
        }
        val waiter = waiters.remove(requestId)
        if (waiter == null) {
            Log.d(TAG, "no waiter for requestID=$requestId (action=${msg.action})")
            return false
        }
        Log.i(TAG, "delivering response for requestID=$requestId action=${msg.action}")
        waiter.complete(msg)
        return true
    }

    /** Drop everything (called on stop / reset). */
    fun cancelAll() {
        waiters.values.forEach { it.cancel() }
        waiters.clear()
        ackJob?.cancel()
        ackJob = null
    }

    private fun sendMessageUrl(): String {
        val auth = authProvider()
        return if (auth.hasCookies())
            Endpoints.SendMessageUrlGoogle else Endpoints.SendMessageUrl
    }

    private fun buildMessage(params: SendMessageParams): Pair<String, OutgoingRPCMessage> {
        val auth = authProvider()
        val requestId = params.requestId ?: UUID.randomUUID().toString()
        val msgType = params.messageType

        val serializedPayload = params.data?.toByteArray() ?: ByteArray(0)
        val encryptedPayload: ByteArray
        val unencryptedPayload: ByteArray
        if (serializedPayload.isNotEmpty()) {
            if (params.dontEncrypt) {
                encryptedPayload = ByteArray(0)
                unencryptedPayload = serializedPayload
            } else {
                encryptedPayload = auth.crypto().encrypt(serializedPayload)
                unencryptedPayload = ByteArray(0)
            }
        } else {
            encryptedPayload = ByteArray(0)
            unencryptedPayload = ByteArray(0)
        }

        val rpcDataBuilder = OutgoingRPCData.newBuilder()
            .setRequestID(requestId)
            .setAction(params.action)
            .setSessionID(currentSessionId)
        if (encryptedPayload.isNotEmpty()) {
            rpcDataBuilder.setEncryptedProtoData(ByteString.copyFrom(encryptedPayload))
        }
        if (unencryptedPayload.isNotEmpty()) {
            rpcDataBuilder.setUnencryptedProtoData(ByteString.copyFrom(unencryptedPayload))
        }
        val rpcData = rpcDataBuilder.build()

        val builder = OutgoingRPCMessage.newBuilder()
            .setData(
                OutgoingRPCMessage.Data.newBuilder()
                    .setRequestID(requestId)
                    .setBugleRoute(BugleRoute.DataEvent)
                    .setMessageData(rpcData.toByteString())
                    .setMessageTypeData(
                        OutgoingRPCMessage.Data.Type.newBuilder()
                            .setEmptyArr(util.Util.EmptyArr.getDefaultInstance())
                            .setMessageType(msgType)
                    )
            )
            .setAuth(
                OutgoingRPCMessage.Auth.newBuilder()
                    .setRequestID(requestId)
                    .setTachyonAuthToken((auth.tachyonToken()
                        ?: error("tachyon token is null — not paired or token expired"))
                                                .let { ByteString.copyFrom(it) })
                    .setConfigVersion(PairFlow.ConfigVersion)
            )

        // DestRegistrationIDs for GAIA pairing
        if (!auth.destRegId.isNullOrBlank()) {
            builder.addDestRegistrationIDs(auth.destRegId)
        }

        if (params.customTtl != 0L) {
            builder.setTTL(params.customTtl)
        } else if (!params.omitTtl) {
            builder.setTTL(auth.tachyonTtlUs)
        }

        auth.mobile()?.let { builder.setMobile(it) }
        return requestId to builder.build()
    }

    companion object {
        private const val TAG = "GMessages/Session"
    }
}

/**
 * Parameters for sending an outgoing message.
 * Port of Go's `SendMessageParams`.
 */
data class SendMessageParams(
    val action: ActionType,
    val data: Message? = null,
    val requestId: String? = null,
    val omitTtl: Boolean = false,
    val customTtl: Long = 0L,
    val dontEncrypt: Boolean = false,
    val messageType: MessageType = MessageType.BUGLE_MESSAGE,
)

/**
 * Decoded incoming RPC message — either a paired event, a response to
 * one of our outbound RPCs, or a server-pushed update.
 *
 * Mirrors libgm's `IncomingRPCMessage` struct (event_handler.go) but
 * keeps just the v1-essential fields.
 */
data class IncomingRpc(
    val responseId: String,
    val requestId: String?,
    val action: ActionType?,
    /** Decrypted payload bytes, if any. */
    val decryptedData: ByteArray?,
    /** Unencrypted data from the RPC (used by GAIA pairing). */
    val unencryptedData: ByteArray? = null,
    /** Whether this message was already seen before the long-poll reconnected. */
    val isOld: Boolean = false,
    /** Raw decoded pair event (BugleRoute=PairEvent), if applicable. */
    val pairEvent: PairEventKind = PairEventKind.None,
    /** Paired data when [pairEvent] is [PairEventKind.Paired]. */
    val pairedDeviceMobileB64: String? = null,
    val pairedDeviceBrowserB64: String? = null,
    val pairedTachyonTokenB64: String? = null,
    val pairedTachyonTtlUs: Long = 0L,
)

enum class PairEventKind { None, Paired, Revoked }
