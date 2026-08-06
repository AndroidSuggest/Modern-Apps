@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.vayunmathur.messages.signal.web

import kotlin.concurrent.atomics.*
import android.app.Application
import android.util.Log
import com.vayunmathur.messages.signal.proto.WebSocketProtos.WebSocketMessage
import com.vayunmathur.messages.signal.proto.WebSocketProtos.WebSocketRequestMessage
import com.vayunmathur.messages.signal.proto.WebSocketProtos.WebSocketResponseMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import com.vayunmathur.library.network.WebSocketClient
import com.vayunmathur.library.network.WebSocketHandshakeException
import java.util.concurrent.ConcurrentHashMap
import java.io.IOException

class SignalWebSocket(
    private val context: Application,
    private val basicAuth: String? = null,
) {
    sealed class ConnectionEvent {
        object Connecting : ConnectionEvent()
        object Connected : ConnectionEvent()
        data class Disconnected(val reason: String) : ConnectionEvent()
        object LoggedOut : ConnectionEvent()
        data class Error(val reason: String) : ConnectionEvent()
        data class FatalError(val reason: String) : ConnectionEvent()
        object CleanShutdown : ConnectionEvent()
    }

    companion object {
        const val TAG = "SignalWebSocket"
        const val WEBSOCKET_PATH = "/v1/websocket/"
        const val WEBSOCKET_PROVISIONING_PATH = "/v1/websocket/provisioning/"
        const val PING_INTERVAL_MS = 30_000L
        const val PING_TIMEOUT_MS = 20_000L
        const val PING_TIMEOUT_LIMIT = 5
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 150_000L
        const val MAX_REQUEST_RETRIES = 3
        const val ERROR_COUNT_LIMIT = 500

        fun createWsRequest(
            method: String,
            path: String,
            body: ByteArray? = null,
            username: String? = null,
            password: String? = null,
        ): WebSocketRequestMessage {
            val builder = WebSocketRequestMessage.newBuilder()
                .setVerb(method)
                .setPath(path)

            if (body != null) {
                builder.setBody(com.google.protobuf.ByteString.copyFrom(body))
            }

            builder.addHeaders("content-type:application/json; charset=utf-8")

            if (username != null && password != null) {
                val encoded = android.util.Base64.encodeToString(
                    "$username:$password".toByteArray(),
                    android.util.Base64.NO_WRAP
                )
                builder.addHeaders("authorization:Basic $encoded")
            }

            return builder.build()
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val requestId = AtomicLong(1)
    private val pendingRequests = ConcurrentHashMap<Long, CompletableDeferred<WebSocketResponseMessage>>()

    @Volatile private var socket: WebSocketClient? = null
    private var sessionJob: Job? = null
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var currentUrl: String? = null
    private var currentBackoff = INITIAL_BACKOFF_MS
    private var reconnectCount = 0
    private var shouldReconnect = false
    private var consecutivePingFailures = 0
    private var errorCount = 0
    @Volatile
    private var forceReconnectRequested = false

    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(replay = 1)
    val connectionEvents: SharedFlow<ConnectionEvent> = _connectionEvents.asSharedFlow()

    var isConnected: Boolean = false
        private set

    var incomingRequestHandler: ((WebSocketRequestMessage) -> Unit)? = null

    private val sslSocketFactory by lazy {
        CertPinning.createSslSocketFactory(context).first
    }

    private fun onOpen() {
        Log.d(TAG, "Connected")
        isConnected = true
        currentBackoff = INITIAL_BACKOFF_MS
        reconnectCount = 0
        resetPingState()
        scope.launch { _connectionEvents.emit(ConnectionEvent.Connected) }
    }

    private fun onBinaryFrame(bytes: ByteArray) {
        try {
            val message = WebSocketMessage.parseFrom(bytes)
            when (message.type) {
                WebSocketMessage.Type.RESPONSE -> handleResponse(message.response)
                WebSocketMessage.Type.REQUEST -> handleRequest(message.request)
                WebSocketMessage.Type.UNKNOWN ->
                    Log.e(TAG, "Received message with UNKNOWN type")
                else -> Log.w(TAG, "Unknown message type: ${message.type}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message", e)
        }
    }

    /**
     * Replaces okhttp's `onFailure(.., response)`. The handshake status that used
     * to arrive as `response.code` now comes from [WebSocketHandshakeException];
     * a post-handshake read error has no status, matching okhttp's null response.
     */
    private fun onFailure(t: Throwable) {
        Log.e(TAG, "Failure: ${t.message}")
        errorCount++
        if (errorCount > ERROR_COUNT_LIMIT) {
            Log.e(TAG, "Error count limit reached ($errorCount), fatal")
            shouldReconnect = false
            scope.launch { _connectionEvents.emit(ConnectionEvent.FatalError("Too many errors")) }
            onDisconnected("Too many errors")
            return
        }
        val status = (t as? WebSocketHandshakeException)?.statusCode ?: 0
        if (status == 403) {
            shouldReconnect = false
            onDisconnected("Logged out")
            scope.launch { _connectionEvents.emit(ConnectionEvent.LoggedOut) }
            return
        }
        if (status in 1..499) {
            shouldReconnect = false
            scope.launch { _connectionEvents.emit(ConnectionEvent.FatalError("Unexpected status: $status")) }
            onDisconnected("Unexpected status: $status")
            return
        }
        if (status in 500..599) {
            scope.launch { _connectionEvents.emit(ConnectionEvent.Disconnected("Server error: $status")) }
        } else if (currentBackoff < MAX_BACKOFF_MS) {
            scope.launch { _connectionEvents.emit(ConnectionEvent.Disconnected("Transient error: ${t.message ?: "Unknown error"}")) }
        } else {
            scope.launch { _connectionEvents.emit(ConnectionEvent.Error("Continuing error: ${t.message ?: "Unknown error"}")) }
        }
        onDisconnected(t.message ?: "Unknown error")
    }

    fun connect(url: String, autoReconnect: Boolean = true) {
        currentUrl = url
        shouldReconnect = autoReconnect
        openSocket(url)
    }

    fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        val open = socket
        socket = null
        // Close before cancelling: the read loop is parked in a blocking socket
        // read, and closing the socket is what unblocks it.
        scope.launch { runCatching { open?.close() } }
        isConnected = false
        failAllPending("Disconnected")
        scope.launch { _connectionEvents.emit(ConnectionEvent.CleanShutdown) }
    }

    fun forceReconnect() {
        forceReconnectRequested = true
        val open = socket
        socket = null
        scope.launch { runCatching { open?.close() } }
    }

    suspend fun sendRequest(
        method: String,
        path: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
    ): WebSocketResponseMessage {
        val isSelfDelete = method == "DELETE" && path.startsWith("/v1/devices/")

        var lastException: Exception? = null
        for (attempt in 0 until MAX_REQUEST_RETRIES) {
            try {
                return sendRequestOnce(method, path, body, headers)
            } catch (e: IOException) {
                lastException = e
                if (isSelfDelete) {
                    throw e
                }
                if (e.message?.contains("Took too long") == true) {
                    throw e
                }
                Log.w(TAG, "Received nil response, retrying (attempt ${attempt + 1}/$MAX_REQUEST_RETRIES): ${e.message}")
            }
        }
        throw lastException ?: IOException("Retried $MAX_REQUEST_RETRIES times, giving up")
    }

    private suspend fun sendRequestOnce(
        method: String,
        path: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
    ): WebSocketResponseMessage {
        val id = requestId.fetchAndIncrement()
        val deferred = CompletableDeferred<WebSocketResponseMessage>()
        pendingRequests[id] = deferred

        val request = WebSocketRequestMessage.newBuilder()
            .setId(id)
            .setVerb(method)
            .setPath(path)
            .apply {
                if (body != null) {
                    setBody(com.google.protobuf.ByteString.copyFrom(body))
                }
                var hasContentType = false
                headers.forEach { (k, v) ->
                    if (k.lowercase() == "content-type") hasContentType = true
                    addHeaders("${k.lowercase()}:$v")
                }
                if (!hasContentType && body != null) {
                    addHeaders("content-type:application/json")
                }
                if (basicAuth != null) {
                    addHeaders("authorization:Basic $basicAuth")
                }
            }
            .build()

        val message = WebSocketMessage.newBuilder()
            .setType(WebSocketMessage.Type.REQUEST)
            .setRequest(request)
            .build()

        val open = socket
        if (open == null) {
            pendingRequests.remove(id)
            throw IOException("WebSocket send failed")
        }
        try {
            open.send(message.toByteArray())
        } catch (e: Exception) {
            pendingRequests.remove(id)
            throw IOException("WebSocket send failed", e)
        }

        return deferred.await()
    }

    fun sendResponse(requestId: Long, status: Int) {
        if (status != 200 && status != 400) {
            throw IllegalArgumentException("Unsupported response status: $status")
        }
        val msg = if (status == 200) "OK" else "Unknown"

        val response = WebSocketResponseMessage.newBuilder()
            .setId(requestId)
            .setStatus(status)
            .setMessage(msg)
            .addAllHeaders(emptyList())
            .build()

        val wsMsg = WebSocketMessage.newBuilder()
            .setType(WebSocketMessage.Type.RESPONSE)
            .setResponse(response)
            .build()

        val open = socket ?: return
        scope.launch {
            runCatching { open.send(wsMsg.toByteArray()) }
                .onFailure { Log.w(TAG, "sendResponse failed: ${it.message}") }
        }
    }

    /**
     * Opens the socket and pumps its frames until it closes or errors. okhttp's
     * newWebSocket returned immediately and drove callbacks off its own reader
     * thread; here one coroutine owns connect + read, and a sibling drives the
     * keepalive pings that okhttp's `pingInterval` used to send.
     */
    private fun openSocket(url: String) {
        sessionJob?.cancel()
        sessionJob = scope.launch {
            val headers = buildMap {
                put("User-Agent", SignalHttpClient.USER_AGENT)
                put("X-Signal-Agent", SignalHttpClient.SIGNAL_AGENT)
                if (basicAuth != null) put("Authorization", "Basic $basicAuth")
            }

            val open = try {
                WebSocketClient.connect(url, headers, sslSocketFactory = sslSocketFactory)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailure(e)
                return@launch
            }

            socket = open
            onOpen()
            startPingLoop(open)

            var closeReason: String? = null
            try {
                open.incomingFlow().collect { frame ->
                    when (frame) {
                        is WebSocketClient.WsFrame.Binary -> onBinaryFrame(frame.bytes)
                        is WebSocketClient.WsFrame.Text ->
                            onBinaryFrame(frame.text.toByteArray(Charsets.UTF_8))
                        is WebSocketClient.WsFrame.Close -> {
                            Log.d(TAG, "Closed: ${frame.code} ${frame.reason}")
                            closeReason = frame.reason
                        }
                        else -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pingJob?.cancel()
                if (socket === open) socket = null
                onFailure(e)
                return@launch
            }

            // Flow completing means the peer closed or the read loop gave up.
            pingJob?.cancel()
            if (socket === open) socket = null
            onDisconnected(closeReason ?: "Closed")
        }
    }

    private fun startPingLoop(open: WebSocketClient) {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive && !open.isClosed) {
                delay(PING_INTERVAL_MS)
                if (open.isClosed) break
                try {
                    open.ping()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Ping failed: ${e.message}")
                    break
                }
            }
        }
    }

    private fun handleResponse(response: WebSocketResponseMessage) {
        val deferred = pendingRequests.remove(response.id)
        if (deferred != null) {
            deferred.complete(response)
        } else {
            Log.w(TAG, "No pending request for response id=${response.id}")
        }
    }

    private fun handleRequest(request: WebSocketRequestMessage) {
        val handler = incomingRequestHandler
            ?: throw IllegalStateException("Received request but no handler")
        handler(request)
    }

    private fun resetPingState() {
        consecutivePingFailures = 0
    }

    private fun onDisconnected(reason: String) {
        isConnected = false
        failAllPending(reason)
        scope.launch { _connectionEvents.emit(ConnectionEvent.Disconnected(reason)) }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        val url = currentUrl ?: return

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            if (forceReconnectRequested) {
                forceReconnectRequested = false
                currentBackoff = INITIAL_BACKOFF_MS
                reconnectCount = 0
            } else {
                // Exponential backoff matching Go: 2 << retryCount, max 150s
                currentBackoff = ((2L shl reconnectCount) * 1000L).coerceAtMost(MAX_BACKOFF_MS)
                reconnectCount++
                Log.d(TAG, "Reconnecting in ${currentBackoff}ms")
                delay(currentBackoff)
            }
            openSocket(url)
        }
    }

    private fun failAllPending(reason: String) {
        val error = IOException("WebSocket closed: $reason")
        pendingRequests.values.forEach { it.completeExceptionally(error) }
        pendingRequests.clear()
    }
}
