package com.vayunmathur.auto.protocol

/** Where the wireless bring-up has got to. */
enum class WirelessSessionState {
    /** Bluetooth discovery / association has not produced a peer yet. */
    IDLE,

    /** Associated over Bluetooth, negotiating the WiFi link (group, band, credentials). */
    NEGOTIATING_WIFI,

    /** WiFi link parameters are agreed; the socket is not connected yet. */
    WIFI_READY,

    /** Socket connected: bytes may flow into [GalConnection] with TLS reused unchanged. */
    CONNECTED,

    /** The link dropped or setup failed after having started. */
    DISCONNECTED,
}

/**
 * The parameters the Bluetooth bootstrap hands over once WiFi is negotiated.
 *
 * Mirrors what gearhead's `WirelessSetupSharedService` + `BT_START` → RFCOMM
 * handshake produces before the stack falls through to a plain TCP socket: which
 * host to connect to, on which port, over which network. The GAL handshake itself
 * is untouched — TLS runs over this socket exactly as over USB or loopback.
 *
 * @param host the head unit's address on the WiFi link (group owner or AP).
 * @param port the GAL port the head unit listens on.
 * @param networkRequestId opaque handle the Android side uses to bind the socket
 *   to the WiFi network (so it does not go out over mobile data); interpreted
 *   only by the `WifiP2pConnector`, never parsed here.
 */
data class WirelessLinkParams(
    val host: String,
    val port: Int,
    val networkRequestId: Long = 0L,
)

/**
 * The wireless projection bring-up, as a pure state machine.
 *
 * Order follows gearhead: Bluetooth association first (driven by the companion-device
 * profile / `BT_START`), an RFCOMM handshake that yields WiFi credentials, then a
 * plain TCP socket to the head unit over WiFi. Everything at and above the socket —
 * GAL framing, TLS, the control session — is transport-agnostic and reused verbatim.
 *
 * No threads, no I/O, no retry: the Android side owns `BluetoothAdapter` /
 * `WifiP2pManager` calls and reports outcomes here; the reconnect owner
 * (`ProjectionService`) decides whether a [WirelessSessionState.DISCONNECTED] retries.
 */
class WirelessSession {

    var state: WirelessSessionState = WirelessSessionState.IDLE
        private set

    /** Agreed link parameters; null until [onWifiNegotiated]. */
    var linkParams: WirelessLinkParams? = null
        private set

    /**
     * Set when the session ends abnormally — association rejected, WiFi setup
     * failed, socket refused — for the trace log. Null on clean teardown.
     */
    var failure: String? = null
        private set

    /** Bluetooth association completed; WiFi negotiation starts. */
    fun onBluetoothAssociated() {
        if (state != WirelessSessionState.IDLE && state != WirelessSessionState.DISCONNECTED) return
        failure = null
        state = WirelessSessionState.NEGOTIATING_WIFI
    }

    /** WiFi credentials arrived over RFCOMM; the driver may now open the socket. */
    fun onWifiNegotiated(params: WirelessLinkParams) {
        if (state != WirelessSessionState.NEGOTIATING_WIFI) return
        linkParams = params
        failure = null
        state = WirelessSessionState.WIFI_READY
    }

    /** WiFi negotiation failed (timeout, rejected credentials, group formation lost). */
    fun onWifiFailed(reason: String) {
        if (state != WirelessSessionState.NEGOTIATING_WIFI) return
        failure = reason.ifBlank { "wireless WiFi negotiation failed" }
        linkParams = null
        state = WirelessSessionState.DISCONNECTED
    }

    /** The socket connected: GAL may start over it. */
    fun onSocketConnected() {
        if (state != WirelessSessionState.WIFI_READY) return
        failure = null
        state = WirelessSessionState.CONNECTED
    }

    /** The socket failed or the WiFi link dropped. */
    fun onDisconnected(reason: String) {
        failure = reason.ifBlank { "wireless link lost" }
        state = WirelessSessionState.DISCONNECTED
    }

    /** Clean teardown (user disabled wireless, car unpaired): forgets everything. */
    fun reset() {
        linkParams = null
        failure = null
        state = WirelessSessionState.IDLE
    }
}
