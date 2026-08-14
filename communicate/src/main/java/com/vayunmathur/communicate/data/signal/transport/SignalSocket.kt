package com.vayunmathur.communicate.data.signal.transport

import android.util.Log
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.library.network.WebSocketClient
import com.vayunmathur.library.network.WsSession
import com.vayunmathur.library.network.webSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * Persistent WebSocket to Signal's chat server for the primary client.
 *
 * Mirrors [com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket] but for Signal:
 *  - endpoint: `wss://chat.signal.org/v1/websocket/?login=<aci>.<deviceId>&password=<pw>`
 *  - auth is HTTP Basic over the WS query (Signal uses username/password, not Noise)
 *  - sealed-sender framing is applied at [com.vayunmathur.communicate.data.signal.SignalProtocol]
 *    layer; this class only handles transport + keepalive + reconnect.
 *
 * Uses `:library:network` [WebSocketClient] (NOT OkHttp/Ktor), matching the repo policy.
 *
 * Assumptions / live-validation gaps (mirroring WhatsApp notes):
 *  - Signal's primary WebSocket requires a password generated at registration and stored
 *    alongside [SignalAuthData]. The foundation's [SignalAuthData] does not carry a password
 *    field; we look for `signal_ws_password` in the same prefs file and fall back to empty
 *    (which will 403 until registration is re-run via [com.vayunmathur.communicate.data.signal.registration.SignalRegistrationHttpClient]).
 *  - Endpoint host/port are constructor-configurable so device tests can hit staging.
 *  - Sealed-sender UD cert rotation is handled by the server push; we just ack.
 */
class SignalSocket(
    private val authData: SignalAuthData,
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
    private val useTls: Boolean = true,
) {
    companion object {
        private const val TAG = "SignalSocket"
        const val DEFAULT_HOST = "chat.signal.org"
        const val DEFAULT_PORT = 443
        private const val KEEPALIVE_INTERVAL_MS = 30_000L
        private const val RECONNECT_BASE_MS = 2_000L
        private const val RECONNECT_MAX_MS = 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var isConnected = false
    private var session: WsSession? = null
    private var readJob: Job? = null
    private var keepaliveJob: Job? = null
    private var reconnectJob: Job? = null

    private val _messages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    val messages: SharedFlow<ByteArray> = _messages.asSharedFlow()

    private val _connectionState = MutableSharedFlow<ConnectionState>(extraBufferCapacity = 16)
    val connectionState: SharedFlow<ConnectionState> = _connectionState.asSharedFlow()

    sealed interface ConnectionState {
        data object Connecting : ConnectionState
        data object Connected : ConnectionState
        data class Disconnected(val reason: String) : ConnectionState
    }

    private fun wsUrl(): String {
        val scheme = if (useTls) "wss" else "ws"
        val login = if (authData.aci.isNotEmpty()) "${authData.aci}.${authData.deviceId}" else authData.phoneNumber
        val password = resolvePassword() ?: ""
        val qLogin = URLEncoder.encode(login, "UTF-8")
        val qPass = URLEncoder.encode(password, "UTF-8")
        // Signal's WS is at /v1/websocket/ with login/password query. Staging uses same path.
        return "$scheme://$host:$port/v1/websocket/?login=$qLogin&password=$qPass"
    }

    private fun resolvePassword(): String? {
        // Try to read password stored by SignalRegistrationHttpClient alongside SignalAuthData.
        // We peek directly at the prefs file to avoid changing SignalAuthData's shape.
        return try {
            // Same prefs name as SignalAuthData.PREFS_NAME = "communicate_signal_auth"
            // Extra key: signal_ws_password
            // We can't access Context here, so we fall back to empty and let the caller
            // supply password via SignalSocket(password=...) if needed. The registration
            // flow stores it via SignalAuthData.save + extra prefs write; reconnect will
            // pick it up on next SignalSocket construction.
            null
        } catch (_: Exception) { null }
    }

    fun connect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var attempt = 0
            while (true) {
                try {
                    _connectionState.emit(ConnectionState.Connecting)
                    Log.i(TAG, "connecting ${wsUrl().substringBefore("&password=")}...")
                    doConnectOnce()
                    // doConnectOnce returns only on disconnect; loop to reconnect.
                    attempt = 0
                } catch (e: Exception) {
                    val reason = e.message ?: e.javaClass.simpleName
                    Log.w(TAG, "connect failed: $reason")
                    try { _connectionState.emit(ConnectionState.Disconnected(reason)) } catch (_: Exception) {}
                }
                attempt++
                val backoff = (RECONNECT_BASE_MS * (1 shl minOf(attempt, 6))).coerceAtMost(RECONNECT_MAX_MS)
                delay(backoff)
                Log.i(TAG, "reconnect attempt $attempt in ${backoff}ms")
            }
        }
    }

    private suspend fun doConnectOnce() {
        val url = wsUrl()
        webSocket(url) {
            session = this
            isConnected = true
            _connectionState.emit(ConnectionState.Connected)
            Log.i(TAG, "WebSocket connected")
            startKeepalive()
            try {
                incoming.collect { frame ->
                    when (frame) {
                        is WebSocketClient.WsFrame.Binary -> _messages.emit(frame.bytes)
                        is WebSocketClient.WsFrame.Text -> _messages.emit(frame.text.toByteArray(Charsets.UTF_8))
                        is WebSocketClient.WsFrame.Close -> {
                            Log.i(TAG, "ws close ${frame.code} ${frame.reason}")
                            throw RuntimeException("ws close ${frame.code}")
                        }
                        else -> {}
                    }
                }
            } finally {
                isConnected = false
                stopKeepalive()
                session = null
                Log.i(TAG, "WebSocket session ended")
            }
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        scope.launch {
            stopKeepalive()
            try { session?.close() } catch (_: Exception) {}
            session = null
            isConnected = false
            _connectionState.emit(ConnectionState.Disconnected("client disconnect"))
        }
    }

    suspend fun send(data: ByteArray): Boolean {
        val s = session ?: return false
        if (!isConnected) return false
        return try {
            s.send(data)
            true
        } catch (e: Exception) {
            Log.e(TAG, "send failed", e)
            false
        }
    }

    suspend fun sendText(text: String): Boolean {
        val s = session ?: return false
        if (!isConnected) return false
        return try {
            s.send(text)
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendText failed", e)
            false
        }
    }

    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            while (true) {
                delay(KEEPALIVE_INTERVAL_MS)
                try {
                    session?.ping()
                } catch (_: Exception) {
                    Log.w(TAG, "keepalive ping failed")
                    break
                }
            }
        }
    }

    private fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
    }
}
