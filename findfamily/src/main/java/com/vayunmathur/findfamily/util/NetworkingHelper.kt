package com.vayunmathur.findfamily.util

import android.util.Log
import com.vayunmathur.findfamily.tracker.PoweredOffProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull


/** Owner: register/refresh a tracker's secret + ML-KEM public bundle on the server. */
suspend fun Networking.registerTracker(trackerUserId: Long, secret: ByteArray, publicBundle: ByteArray): Boolean {
    val session = wsSession ?: return false
    return try {
        val frame = ByteArray(11 + secret.size + publicBundle.size)
        frame[0] = WS_OP_TRACKER_REGISTER
        putU64Be(frame, 1, trackerUserId.toULong())
        frame[9] = (secret.size ushr 8).toByte()
        frame[10] = secret.size.toByte()
        secret.copyInto(frame, 11)
        publicBundle.copyInto(frame, 11 + secret.size)
        session.send(frame)
        true
    } catch (e: Exception) {
        Log.w("FF-Networking", "registerTracker failed", e); false
    }
}
/** Finder: resolve a beacon epoch-id to the owning tracker's ML-KEM public bundle. */
suspend fun Networking.resolveTrackerBundle(epochId: ByteArray): ByteArray? {
    val session = wsSession ?: return null
    val hex = epochId.toHex()
    val deferred = CompletableDeferred<ByteArray?>()
    pendingResolves[hex] = deferred
    return try {
        val req = ByteArray(1 + epochId.size)
        req[0] = WS_OP_RESOLVE_REQ
        epochId.copyInto(req, 1)
        session.send(req)
        withTimeoutOrNull(GETKEY_TIMEOUT_MS) { deferred.await() }
    } catch (e: Exception) {
        Log.w("FF-Networking", "resolveTrackerBundle failed", e); null
    } finally {
        pendingResolves.remove(hex)
    }
}
/** Finder: upload a sealed crowd report keyed by the beacon epoch-id. */
suspend fun Networking.uploadTrackerReport(epochId: ByteArray, ciphertext: ByteArray): Boolean {
    val session = wsSession ?: return false
    return try {
        val frame = ByteArray(1 + epochId.size + ciphertext.size)
        epochId.copyInto(frame, 1)
        frame[0] = WS_OP_REPORT_PUT
        ciphertext.copyInto(frame, 1 + epochId.size)
        session.send(frame)
        true
    } catch (e: Exception) {
        Log.w("FF-Networking", "uploadTrackerReport failed", e); false
    }
}
/** Owner: fetch (and drain) sealed reports for a batch of recent epoch-ids. */
suspend fun Networking.fetchTrackerReports(epochIds: List<ByteArray>): List<ByteArray> {
    val session = wsSession ?: return emptyList()
    if (epochIds.isEmpty()) return emptyList()
    val deferred = CompletableDeferred<List<ByteArray>>()
    pendingReportGets.add(deferred)
    return try {
        val n = epochIds.size.coerceAtMost(0xFFFF)
        val frame = ByteArray(3 + n * 16)
        frame[0] = WS_OP_REPORT_GET_REQ
        frame[1] = (n ushr 8).toByte()
        frame[2] = n.toByte()
        var off = 3
        for (i in 0 until n) {
            epochIds[i].copyInto(frame, off, 0, 16.coerceAtMost(epochIds[i].size))
            off += 16
        }
        session.send(frame)
        withTimeoutOrNull(GETKEY_TIMEOUT_MS) { deferred.await() } ?: emptyList()
    } catch (e: Exception) {
        Log.w("FF-Networking", "fetchTrackerReports failed", e); emptyList()
    } finally {
        pendingReportGets.remove(deferred)
    }
}
/**
 * Beacon: register the EIDs this device armed its Bluetooth controller with before shutting
 * down, together with the public half of the **recovery** keypair finders should seal to.
 *
 * Must be sent *before* the device powers off - once it is off it cannot re-register, and
 * unlike a live tracker it will not self-heal on the next heartbeat. Registrations are held
 * in memory server-side, so a relay restart during the powered-off window silently ends
 * findability until the device boots again.
 */
suspend fun Networking.registerPoweredOffEids(
    userId: Long,
    eids: List<ByteArray>,
    recoveryBundle: ByteArray,
): Boolean {
    val session = wsSession ?: return false
    if (eids.isEmpty()) return false
    val n = eids.size.coerceAtMost(PoweredOffProtocol.ARMED_SLOTS)
    return try {
        // u16 count, not u8: the controller addresses 256 keys (index 0..255) and 256 does
        // not fit in a byte. Getting this wrong would silently drop the last EID, costing the
        // final 17 minutes of the window.
        val frame = ByteArray(11 + n * PoweredOffProtocol.EID_LEN + 2 + recoveryBundle.size)
        frame[0] = WS_OP_POF_REGISTER
        putU64Be(frame, 1, userId.toULong())
        frame[9] = (n ushr 8).toByte()
        frame[10] = n.toByte()
        var off = 11
        for (i in 0 until n) {
            eids[i].copyInto(frame, off, 0, PoweredOffProtocol.EID_LEN)
            off += PoweredOffProtocol.EID_LEN
        }
        frame[off] = (recoveryBundle.size ushr 8).toByte()
        frame[off + 1] = recoveryBundle.size.toByte()
        recoveryBundle.copyInto(frame, off + 2)
        session.send(frame)
        true
    } catch (e: Exception) {
        Log.w("FF-Networking", "registerPoweredOffEids failed", e); false
    }
}
