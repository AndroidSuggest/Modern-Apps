package com.vayunmathur.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vayunmathur.vpn.R
import com.vayunmathur.vpn.data.VpnConfig
import com.vayunmathur.vpn.data.endpointHost
import com.vayunmathur.vpn.data.endpointPort
import com.vayunmathur.vpn.util.VpnNative
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * Foreground VpnService that bridges Android TUN fd <-> UDP socket.
 *
 * Crypto is all in Rust/gotatun via VpnNative (Mullvad's BoringTun fork):
 * X25519 + Noise IK + ChaCha20-Poly1305 + BLAKE2s, plus rekey/keepalive timers.
 *
 * Kotlin only handles TUN↔UDP plumbing.
 */
class VpnTunnelService : VpnService() {

    companion object {
        private const val TAG = "VpnTunnelService"
        const val ACTION_CONNECT = "com.vayunmathur.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.vayunmathur.vpn.DISCONNECT"
        const val EXTRA_CONFIG_JSON = "config_json"
        const val NOTIFICATION_ID = 42
        const val CHANNEL_ID = "vpn_tunnel"
        @Volatile var isRunning: Boolean = false
        @Volatile var runningConfigName: String = ""
    }

    private var tunPfd: ParcelFileDescriptor? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val stopFlag = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        try { VpnNative.init() } catch (_: Throwable) {}
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN Tunnel", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_DISCONNECT, null -> { stopVpn(); START_NOT_STICKY }
            ACTION_CONNECT -> {
                val j = intent.getStringExtra(EXTRA_CONFIG_JSON) ?: return START_NOT_STICKY
                runCatching { Json.decodeFromString<VpnConfig>(j) }.onSuccess { startVpn(it) }
                START_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    override fun onDestroy() { stopVpn(); super.onDestroy() }
    override fun onRevoke() { Log.i(TAG, "onRevoke"); stopVpn() }

    private fun notification(title: String, text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher).setOngoing(true).build()
    }

    private fun startVpn(config: VpnConfig) {
        if (isRunning) stopVpn()
        stopFlag.set(false)
        runningConfigName = config.name
        val notif = notification("VPN (WireGuard/gotatun) — ${config.name}", config.peerEndpoint)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else startForeground(NOTIFICATION_ID, notif)
        } catch (e: Exception) { Log.e(TAG, "foreground", e) }

        job = scope.launch { runBlocking { runTunnel(config) } }
    }

    private fun stopVpn() {
        stopFlag.set(true)
        job?.cancel(); job = null
        isRunning = false
        runningConfigName = ""
        try { tunPfd?.close() } catch (_: Exception) {}
        tunPfd = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun runTunnel(config: VpnConfig) {
        isRunning = true

        val handle = VpnNative.newTunnel(
            config.privateKey, config.peerPublicKey, config.peerPresharedKey, config.peerKeepalive
        )
        if (handle <= 0) { Log.e(TAG, "newTunnel failed $handle"); stopVpn(); return }

        fun parseCsvCidrs(csv: String): List<Pair<String, Int>> =
            csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { cidr ->
                val parts = cidr.split('/')
                val ip = parts[0].trim()
                val mask = parts.getOrNull(1)?.trim()?.toIntOrNull()
                    ?: if (ip.contains(':')) 128 else 32
                ip to mask
            }

        val localAddrs = parseCsvCidrs(config.address.ifBlank { "10.0.0.2/32" })
        val allowed = parseCsvCidrs(config.peerAllowedIPs.ifBlank { "0.0.0.0/0" })

        val b = Builder().setSession(config.name.ifBlank { "WireGuard" })
            .setMtu(config.mtu.coerceIn(1280, 1500)).setBlocking(false)

        for ((ip, mask) in localAddrs) { try { b.addAddress(ip, mask) } catch (e: Exception) { Log.w(TAG, "addr $ip/$mask", e) } }
        for ((ip, mask) in allowed) { try { b.addRoute(ip, mask) } catch (e: Exception) { Log.w(TAG, "route $ip/$mask", e) } }
        if (config.dns.isNotBlank()) {
            for (d in config.dns.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                try { b.addDnsServer(d) } catch (_: Exception) {}
            }
        }

        val pfd = try { b.establish() } catch (e: Exception) {
            Log.e(TAG, "establish", e); VpnNative.freeTunnel(handle); stopVpn(); return
        }
        if (pfd == null) { Log.e(TAG, "establish null"); VpnNative.freeTunnel(handle); stopVpn(); return }
        tunPfd = pfd

        val host = config.endpointHost()
        val port = config.endpointPort()
        if (host.isEmpty()) { Log.e(TAG, "no endpoint"); pfd.close(); VpnNative.freeTunnel(handle); stopVpn(); return }

        val channel = try {
            val ch = DatagramChannel.open()
            ch.configureBlocking(false)
            try { protect(ch.socket()) } catch (_: Exception) {}
            try { protect(DatagramSocket()) } catch (_: Exception) {}
            ch.connect(java.net.InetSocketAddress(host, port))
            ch
        } catch (e: Exception) {
            Log.e(TAG, "UDP connect $host:$port", e)
            pfd.close(); VpnNative.freeTunnel(handle); stopVpn(); return
        }

        val tunIn = FileInputStream(pfd.fileDescriptor).channel
        val tunOut = FileOutputStream(pfd.fileDescriptor).channel

        try {
            VpnNative.formatHandshakeInit(handle)?.let { hs ->
                try { channel.write(ByteBuffer.wrap(hs)) } catch (_: Exception) {}
                Log.i(TAG, "Sent HandshakeInit ${hs.size} to $host:$port (gotatun)")
            }

            val udpBuf = ByteBuffer.allocate(65535)
            val tunBuf = ByteBuffer.allocate(65535)
            var lastTimer = System.currentTimeMillis()

            while (!stopFlag.get() && scope.isActive) {
                try {
                    while (tunIn.read(tunBuf) > 0) {
                        tunBuf.flip()
                        val ip = ByteArray(tunBuf.remaining()); tunBuf.get(ip); tunBuf.clear()
                        val enc = VpnNative.encapsulate(handle, ip)
                        if (enc != null && enc.isNotEmpty()) {
                            try { channel.write(ByteBuffer.wrap(enc)) } catch (e: Exception) { Log.w(TAG, "udp write", e) }
                        } else {
                            VpnNative.formatHandshakeInit(handle)?.let { h ->
                                try { channel.write(ByteBuffer.wrap(h)) } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (e: Exception) { if (!stopFlag.get()) Log.w(TAG, "tun read", e) }

                try {
                    udpBuf.clear()
                    while (channel.read(udpBuf) > 0) {
                        udpBuf.flip()
                        val wg = ByteArray(udpBuf.remaining()); udpBuf.get(wg); udpBuf.clear()
                        val tagged = VpnNative.consumeIncomingPacketDetailed(handle, wg) ?: continue
                        if (tagged.isEmpty()) continue
                        val tag = tagged[0].toInt()
                        val payload = tagged.copyOfRange(1, tagged.size)
                        when (tag) {
                            1 -> if (payload.isNotEmpty()) { try { channel.write(ByteBuffer.wrap(payload)) } catch (_: Exception) {} }
                            2 -> if (payload.isNotEmpty()) { try { tunOut.write(ByteBuffer.wrap(payload)) } catch (e: Exception) { if (!stopFlag.get()) Log.w(TAG, "tun write", e) } }
                            3 -> { /* keepalive absorbed */ }
                        }
                    }
                } catch (e: Exception) { if (!stopFlag.get()) Log.w(TAG, "udp read", e) }

                val now = System.currentTimeMillis()
                if (now - lastTimer >= 100) {
                    lastTimer = now
                    try {
                        val t = VpnNative.tickTimersDetailed(handle)
                        if (t != null && t.isNotEmpty() && t[0].toInt() == 1) {
                            val p = t.copyOfRange(1, t.size)
                            if (p.isNotEmpty()) try { channel.write(ByteBuffer.wrap(p)) } catch (_: Exception) {}
                        }
                    } catch (e: Exception) { Log.w(TAG, "timer", e) }
                }
                delay(10)
            }
        } finally {
            try { tunIn.close() } catch (_: Exception) {}
            try { tunOut.close() } catch (_: Exception) {}
            try { channel.close() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
            VpnNative.freeTunnel(handle)
            isRunning = false
            try { getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) } catch (_: Exception) {}
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
}
