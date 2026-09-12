package com.vayunmathur.auto.network

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import com.vayunmathur.auto.platform.TransportState
import com.vayunmathur.auto.protocol.GalTransport
import com.vayunmathur.auto.protocol.StreamTransport
import com.vayunmathur.auto.protocol.TransportKind
import com.vayunmathur.auto.protocol.WirelessLinkParams
import com.vayunmathur.auto.protocol.WirelessSession
import com.vayunmathur.auto.protocol.WirelessSessionState
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The wireless bring-up: association consent → WiFi Direct → socket → intake.
 *
 * Order follows gearhead (`WirelessSetupSharedService` + `BT_START` → RFCOMM
 * handshake → TCP socket): the Bluetooth half is future work (companion-device
 * association driving the trigger per the FINDINGS.md phenotype checklist), and for
 * this first version the pairing screen's "Look for cars" tap stands in for the
 * association consent — the user pointing at a car is the disambiguation either way.
 * Everything from WiFi Direct group formation on is real: this owns group bring-up
 * through the connected socket. GAL over that socket is byte-identical to USB or
 * loopback — TLS is reused unchanged — so the socket becomes a [StreamTransport]
 * parked in [TransportIntake] for the same service loop.
 *
 * Bring-up state lives in a [WirelessSession] (pure, JVM-tested) mirrored to
 * [TransportState] for the pairing UI. No retry of its own: a dropped link surfaces
 * as end-of-stream in the session, owned by the reconnect logic.
 */
object WifiDirectConnector {

    private val session = WirelessSession()
    private var worker: Thread? = null
    @Volatile private var cancelled = false

    private var appContext: Context? = null
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null

    /**
     * Starts WiFi Direct discovery toward the associated head unit. Call after the
     * nearby-devices permission is granted; the tap is the association consent.
     */
    fun start(context: Context) {
        cancelled = false
        appContext = context.applicationContext
        session.onBluetoothAssociated()
        TransportState.publishWireless(session)
        discover()
    }

    /** Cancels an in-flight bring-up; safe to call when idle. */
    fun cancel() {
        cancelled = true
        worker?.interrupt()
        worker = null
        session.reset()
        TransportState.publishWireless(session)
    }

    /**
     * Handles `WIFI_P2P_CONNECTION_CHANGED_ACTION` from the pairing screen's
     * listener: on connect, resolves the group owner's address into the GAL socket.
     */
    @Suppress("MissingPermission")
    fun onConnectionChanged(intent: Intent) {
        if (cancelled) return
        @Suppress("DEPRECATION")
        val networkInfo: NetworkInfo? =
            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
        if (networkInfo?.isConnected != true) return
        val manager = manager
        val channel = channel
        if (manager == null || channel == null) return
        // Nearby-devices permission was granted to reach start(); the framework
        // enforces it again here, hence the suppression rather than a re-check.
        manager.requestConnectionInfo(channel) { info ->
            info?.groupOwnerAddress?.hostAddress?.let(::onGroupAddress)
        }
    }

    @SuppressLint("MissingPermission")
    private fun discover() {
        val context = appContext ?: run {
            fail("no application context for WiFi Direct")
            return
        }
        // Nearby-devices permission is granted before start() by the pairing
        // screen; the framework enforces it on every call below.
        val manager = context.getSystemService(WifiP2pManager::class.java) ?: run {
            fail("no WiFi Direct service")
            return
        }
        val channel = manager.initialize(context, Looper.getMainLooper(), null)
        this.manager = manager
        this.channel = channel
        manager.discoverPeers(
            channel,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() = requestGroup(manager, channel)
                override fun onFailure(reason: Int) = fail("peer discovery failed: $reason")
            },
        )
    }

    @SuppressLint("MissingPermission")
    private fun requestGroup(manager: WifiP2pManager, channel: WifiP2pManager.Channel) {
        if (cancelled) return
        manager.requestGroupInfo(
            channel,
        ) { group ->
            if (cancelled) return@requestGroupInfo
            val owner = group?.owner
            if (group == null || !group.isGroupOwner || owner == null) {
                connect(manager, channel)
            } else {
                onGroupAddress(owner.deviceAddress)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(manager: WifiP2pManager, channel: WifiP2pManager.Channel) {
        // Group owner unknown yet: connect to the associated peer and let the group form.
        // The head unit drives the persistent-group invitation on its side after the
        // Bluetooth association; an empty config joins whatever it offers.
        manager.connect(
            channel,
            WifiP2pConfig(),
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    // Connection info arrives via the framework broadcast, which the
                    // pairing screen forwards to onConnectionChanged above.
                }
                override fun onFailure(reason: Int) = fail("WiFi Direct connect failed: $reason")
            },
        )
    }

    /**
     * Opens the GAL socket to the group owner on a worker thread so no thread that
     * matters ever blocks on connect.
     */
    private fun onGroupAddress(hostAddress: String) {
        if (cancelled) return
        session.onWifiNegotiated(WirelessLinkParams(host = hostAddress, port = GAL_PORT))
        TransportState.publishWireless(session)
        worker = thread(name = "ma-auto-wireless") {
            openSocket(hostAddress)
        }
    }

    private fun openSocket(hostAddress: String) {
        val params = session.linkParams ?: return
        bindToWifiNetwork()
        // NOT `use {}`: the service loop takes ownership of these streams and closes
        // the transport at session end. Closing here would hand it dead streams.
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(params.host, params.port), CONNECT_TIMEOUT_MS)
        } catch (e: IOException) {
            runCatching { socket.close() }
            fail(e.message ?: "wireless socket failed")
            return
        }
        val transport = LeasedSocketTransport(
            StreamTransport(socket.getInputStream(), socket.getOutputStream()),
            socket,
        )
        TransportIntake.offer(
            TransportIntake.Pending(
                transport = transport,
                carName = CAR_NAME_FALLBACK,
                kind = TransportKind.WIRELESS,
            ),
        )
        if (!cancelled) {
            session.onSocketConnected()
            TransportState.publishWireless(session)
            Log.i(TAG, "wireless socket connected to $hostAddress, parked for the service loop")
        } else {
            runCatching { transport.close() }
        }
    }

    /**
     * Binds process traffic to the WiFi Direct network so the GAL socket does not go
     * out over mobile data. Best-effort: a missing network keeps the bring-up going on
     * the default route rather than failing it.
     */
    private fun bindToWifiNetwork() {
        runCatching {
            val context = appContext ?: return
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
                ?: return
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivity.requestNetwork(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        connectivity.bindProcessToNetwork(network)
                        connectivity.unregisterNetworkCallback(this)
                    }
                },
            )
        }.onFailure { Log.w(TAG, "could not bind to the WiFi network", it) }
    }

    private fun fail(reason: String) {
        Log.w(TAG, reason)
        if (session.state == WirelessSessionState.NEGOTIATING_WIFI) {
            session.onWifiFailed(reason)
        } else {
            session.onDisconnected(reason)
        }
        TransportState.publishWireless(session)
    }

    /**
     * A socket-backed transport whose close also releases the socket. The streams are
     * owned by the inner [StreamTransport]; closing both in order leaks nothing.
     */
    private class LeasedSocketTransport(
        private val inner: StreamTransport,
        private val socket: Socket,
    ) : GalTransport {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            inner.read(buffer, offset, length)

        override fun write(bytes: ByteArray, offset: Int, length: Int) =
            inner.write(bytes, offset, length)

        override fun flush() = inner.flush()

        override fun close() {
            try {
                inner.close()
            } finally {
                socket.close()
            }
        }
    }

    private const val TAG = "MaAuto.Wireless"

    /** GAL port on the head unit's WiFi link; same number as the loopback fallback. */
    private const val GAL_PORT = 5277

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val CAR_NAME_FALLBACK = "Car"
}
