package com.vayunmathur.communicate.data.rcs

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import java.net.InetAddress
import java.net.Socket
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * IMS PDN access for RCS media (MSRP sockets).
 *
 * Mirrors TestRcsApp's `ImsPdnNetworkFetcher` (NET_CAPABILITY_IMS request →
 * cached `Network` → `network.socketFactory` sockets bound to the IMS PDN)
 * without Guava: one-shot `requestNetwork` calls wrapped in suspend functions
 * with a bounded wait, so a missing IMS PDN degrades to a plain socket instead
 * of hanging.
 *
 * Raw sockets are inherent here (MSRP is a TCP protocol, not HTTP — no HTTP
 * client can speak it); FT-over-HTTP stays on `NetworkClient` per the
 * networking rules.
 *
 * Every entry point early-returns null when the dev gate is off and never
 * throws; callers fall back to unbound sockets.
 */
object RcsImsNetwork {
    private const val TAG = "RcsImsNetwork"

    /** How long to wait for the IMS network before falling back. */
    private const val REQUEST_TIMEOUT_MS = 15_000L

    /** Cached IMS network; invalidated when a socket bind fails. */
    @Volatile
    private var cached: Network? = null

    /** Last IMS-PDN local address seen, for SDP offers. Null = unknown. */
    @Volatile
    var lastLocalIp: String? = null
        private set

    /**
     * The IMS PDN `Network`, requesting it when not cached. Null when
     * unavailable (no IMS PDN, timeout, or permission failure).
     */
    suspend fun imsNetwork(context: Context): Network? {
        if (!RcsFeature.enabled) return null
        cached?.let { return it }
        val cm = runCatching {
            context.applicationContext.getSystemService(ConnectivityManager::class.java)
        }.getOrNull() ?: return null
        val network = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_IMS)
                    .build()
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        runCatching { cm.unregisterNetworkCallback(this) }
                        if (cont.isActive) cont.resume(network)
                    }

                    override fun onUnavailable() {
                        runCatching { cm.unregisterNetworkCallback(this) }
                        if (cont.isActive) cont.resume(null)
                    }
                }
                cont.invokeOnCancellation {
                    runCatching { cm.unregisterNetworkCallback(callback) }
                }
                runCatching { cm.requestNetwork(request, callback) }
                    .onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
        if (network != null) {
            cached = network
            lastLocalIp = localIpFor(context, network)
        }
        return network
    }

    /**
     * Open a TCP socket to [host]:[port], preferring the IMS PDN's socket
     * factory (source-bound to the IMS local address like TestRcsApp's
     * `MsrpManager.createMsrpSession`). Falls back to a plain socket when the
     * IMS network is unavailable. Null only when both paths fail.
     */
    suspend fun createSocket(
        context: Context,
        host: String,
        port: Int,
        connectTimeoutMs: Int = 10_000,
    ): Socket? {
        if (!RcsFeature.enabled) return null
        // IMS path first.
        val ims = imsNetwork(context)
        if (ims != null) {
            val bound = runCatching {
                val factory = ims.getSocketFactory()
                val local = lastLocalIp?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
                val socket = if (local != null) {
                    factory.createSocket(host, port, local, 0)
                } else {
                    val s = factory.createSocket()
                    s.connect(java.net.InetSocketAddress(host, port), connectTimeoutMs)
                    s
                }
                socket.soTimeout = 0
                socket
            }.getOrNull()
            if (bound != null && bound.isConnected) return bound
            runCatching { bound?.close() }
            // IMS network went stale — drop it so the next call re-requests.
            cached = null
            Log.w(TAG, "IMS socket bind failed; falling back to plain socket")
        }
        // Plain fallback (v1 behavior).
        return runCatching {
            val s = Socket()
            s.connect(java.net.InetSocketAddress(host, port), connectTimeoutMs)
            s.soTimeout = 0
            s
        }.getOrNull()
    }

    /** Local IP addresses on [network] (link addresses); empty on failure. */
    fun localIps(context: Context, network: Network): List<String> {
        if (!RcsFeature.enabled) return emptyList()
        return runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()
            val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
                ?: return emptyList()
            val props = cm.getLinkProperties(network) ?: return emptyList()
            props.linkAddresses.mapNotNull { it.address?.hostAddress }
                .filter { !it.startsWith("127.") && it != "::1" }
        }.getOrDefault(emptyList())
    }

    private fun localIpFor(context: Context, network: Network): String? =
        localIps(context, network).firstOrNull { it.contains('.') }
            ?: localIps(context, network).firstOrNull()

    /** Drop the cached network (subId change / teardown). */
    fun reset() {
        cached = null
        lastLocalIp = null
    }
}
