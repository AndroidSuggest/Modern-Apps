package com.vayunmathur.vpn.service

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Resolves UID and package info from a parsed packet using ConnectivityManager.getConnectionOwnerUid().
 * Available on API 29+; minSdk 31 satisfied.
 *
 * getConnectionOwnerUid is a binder call, so it must not run per-packet. Results are cached per flow;
 * flows that fail to resolve (the socket may not be in the kernel's inet_diag table yet, or it is an
 * unconnected UDP socket) are retried on a cooldown so attribution can still land a few packets in.
 */
class AppResolver(private val context: Context) {

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val packageManager: PackageManager = context.packageManager

    data class ResolvedApp(
        val uid: Int,
        val packageName: String?,
        val appLabel: String,
    )

    private data class FlowKey(
        val protocol: Int,
        val localPort: Int,
        val remoteIp: String,
        val remotePort: Int,
    )

    private val uidCache = mutableMapOf<Int, ResolvedApp>()
    private val flowCache = lru<FlowKey, ResolvedApp>(MAX_FLOWS)
    private val retryAfter = lru<FlowKey, Long>(MAX_FLOWS)

    @Synchronized
    fun resolve(
        parsed: PacketInspector.ParsedPacket,
        direction: ConnectionTracker.Direction,
    ): ResolvedApp {
        if (parsed.protocolNumber != 6 && parsed.protocolNumber != 17) return UNKNOWN

        val local = if (direction == ConnectionTracker.Direction.TX) parsed.srcIp else parsed.dstIp
        val localPort = if (direction == ConnectionTracker.Direction.TX) parsed.srcPort else parsed.dstPort
        val remote = if (direction == ConnectionTracker.Direction.TX) parsed.dstIp else parsed.srcIp
        val remotePort = if (direction == ConnectionTracker.Direction.TX) parsed.dstPort else parsed.srcPort

        val key = FlowKey(parsed.protocolNumber, localPort, remote, remotePort)
        flowCache[key]?.let { return it }

        val now = SystemClock.elapsedRealtime()
        if (now < (retryAfter[key] ?: 0L)) return UNKNOWN

        val uid = queryUid(parsed.protocolNumber, local, localPort, remote, remotePort)
        if (uid == null) {
            retryAfter[key] = now + RETRY_COOLDOWN_MS
            return UNKNOWN
        }

        val resolved = resolveUid(uid)
        flowCache[key] = resolved
        retryAfter.remove(key)
        return resolved
    }

    @Synchronized
    fun resolveUid(uid: Int): ResolvedApp = uidCache.getOrPut(uid) { describe(uid) }

    private fun queryUid(
        protocol: Int,
        localIp: String,
        localPort: Int,
        remoteIp: String,
        remotePort: Int,
    ): Int? {
        val cm = connectivityManager ?: return null
        return try {
            val local = socketAddress(localIp, localPort) ?: return null
            val remote = socketAddress(remoteIp, remotePort) ?: return null

            var uid = cm.getConnectionOwnerUid(protocol, local, remote)

            // An unconnected UDP socket (the common case for DNS and some QUIC stacks) has no peer
            // recorded in the kernel, so it only matches against a wildcard remote.
            if (uid == Process.INVALID_UID && protocol == 17) {
                val wildcard = socketAddress(if (localIp.contains(':')) "::" else "0.0.0.0", 0)
                if (wildcard != null) uid = cm.getConnectionOwnerUid(protocol, local, wildcard)
            }

            // Some stacks report the flow with the endpoints the other way round.
            if (uid == Process.INVALID_UID) {
                uid = cm.getConnectionOwnerUid(protocol, remote, local)
            }

            if (uid == Process.INVALID_UID || uid < 0) null else uid
        } catch (se: SecurityException) {
            Log.w(TAG, "SecurityException getConnectionOwnerUid", se)
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Builds an address from a numeric literal only — never let this hit the resolver. */
    private fun socketAddress(ip: String, port: Int): InetSocketAddress? = try {
        InetSocketAddress(InetAddress.getByName(ip), port)
    } catch (_: Exception) {
        null
    }

    private fun describe(uid: Int): ResolvedApp {
        if (uid == 0) return ResolvedApp(0, null, "root")

        val pkg = try { packageManager.getPackagesForUid(uid)?.let(::pickPackage) } catch (_: Exception) { null }
        if (pkg != null) {
            val label = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                ""
            }
            return ResolvedApp(uid, pkg, label.ifBlank { pkg })
        }

        systemLabel(uid)?.let { return ResolvedApp(uid, null, it) }

        // Shared UIDs report as "shared:android.uid.foo"; better than showing a bare number.
        val name = try { packageManager.getNameForUid(uid) } catch (_: Exception) { null }
        if (!name.isNullOrBlank()) {
            return ResolvedApp(uid, null, name.removePrefix("shared:"))
        }
        return ResolvedApp(uid, null, "Unknown app ($uid)")
    }

    /** Several packages can share a UID — prefer one the user would recognise. */
    private fun pickPackage(pkgs: Array<String>): String? = when {
        pkgs.isEmpty() -> null
        pkgs.size == 1 -> pkgs[0]
        else -> pkgs.firstOrNull {
            runCatching { packageManager.getLaunchIntentForPackage(it) }.getOrNull() != null
        } ?: pkgs[0]
    }

    private fun systemLabel(uid: Int): String? = when (uid) {
        Process.SYSTEM_UID -> "Android System"
        Process.PHONE_UID -> "Telephony"
        DNS_UID -> "DNS resolver"
        else -> null
    }

    @Synchronized
    fun clear() {
        uidCache.clear()
        flowCache.clear()
        retryAfter.clear()
    }

    private fun <K, V> lru(max: Int): MutableMap<K, V> =
        object : LinkedHashMap<K, V>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>) = size > max
        }

    companion object {
        private const val TAG = "AppResolver"
        private const val MAX_FLOWS = 4096
        private const val RETRY_COOLDOWN_MS = 300L
        private const val DNS_UID = 1051

        /** uid -1 marks "not attributed yet" so a later packet on the same flow can still upgrade it. */
        val UNKNOWN = ResolvedApp(uid = -1, packageName = null, appLabel = "Unknown")
    }
}
