package com.vayunmathur.auto.network

import android.util.Log
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Listens for a head unit on the port the Desktop Head Unit uses.
 *
 * The DHU runs on a workstation and reaches the phone through
 * `adb forward tcp:5277 tcp:5277`, so the phone is the one listening. That is the same
 * direction as the rest of GAL, where the phone is the TLS server and the head unit
 * connects to it.
 *
 * Bound to loopback: the only thing that should reach this is an adb forward, and binding
 * the wildcard address would expose the projection socket to the local network.
 */
class HeadUnitServer(private val port: Int = DHU_PORT) : Closeable {

    private var server: ServerSocket? = null

    /** Blocks until a head unit connects. */
    fun accept(): Socket {
        // `getLoopbackAddress()` returns the IPv6 `::1` on dual-stack ART, which
        // leaves the socket unreachable from an `adb forward` that dials IPv4
        // 127.0.0.1 on-device. Bind IPv4 explicitly and fall back to dual-stack
        // `::` only if the bind fails: adb can reach either, and the wildcard
        // would expose the socket only to the phone itself either way.
        val socket = server ?: tryIpv4Loopback() ?: ServerSocket(port, BACKLOG)
            .also {
                Log.w(TAG, "IPv4 loopback bind failed; listening dual-stack instead")
            }
            .also {
                server = it
                Log.i(TAG, "listening for a head unit on ${it.inetAddress.hostAddress}:$port")
            }
        return socket.accept().apply {
            // Projection is latency-sensitive and its writes are small and frequent; Nagle
            // would coalesce frames and add a visible stutter.
            tcpNoDelay = true
            Log.i(TAG, "head unit connected from $remoteSocketAddress")
        }
    }

    override fun close() {
        server?.close()
        server = null
    }

    private fun tryIpv4Loopback(): ServerSocket? = runCatching {
        ServerSocket(port, BACKLOG, InetAddress.getByName(IPV4_LOOPBACK))
    }.getOrNull()

    companion object {
        /** The port the Desktop Head Unit expects to be forwarded to. */
        const val DHU_PORT = 5277

        private const val TAG = "MaAuto.Server"
        private const val BACKLOG = 1

        /** Explicit IPv4 loopback: `getLoopbackAddress()` yields IPv6 `::1` on ART. */
        private const val IPV4_LOOPBACK = "127.0.0.1"
    }
}
