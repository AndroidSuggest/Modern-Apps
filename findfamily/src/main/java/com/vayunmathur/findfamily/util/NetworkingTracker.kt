package com.vayunmathur.findfamily.util

import com.vayunmathur.findfamily.tracker.PoweredOffProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

// Custom UWB tracker crowd-finding opcodes (DEV_BUILD). Mirrored on the server
// (src/handlers/findfamily.rs). Older servers ignore unknown opcodes, so these
// are backward-compatible: resolve/report-get simply time out to null/empty.
internal const val WS_OP_TRACKER_REGISTER: Byte = 0x06 // [0x06][u64 tracker_id][u16 secretLen][secret][bundle…]
internal const val WS_OP_RESOLVE_REQ: Byte = 0x07 //      [0x07][16B epochId]
internal const val WS_OP_RESOLVE_RESP: Byte = 0x08 //     [0x08][status][16B epochId][bundle…]
internal const val WS_OP_REPORT_PUT: Byte = 0x09 //       [0x09][16B epochId][ciphertext…]
internal const val WS_OP_REPORT_GET_REQ: Byte = 0x0A //   [0x0A][u16 n]([16B epochId]×n)
internal const val WS_OP_REPORT_GET_RESP: Byte = 0x0B //  [0x0B][u16 count]([u32 len][ct]×count)

// Powered-off finding. The server cannot derive these ids itself the way it derives tracker
// epoch-ids: the rotation period is fixed at 1024s by the Bluetooth HAL and the controller
// anchors its key schedule to shutdown time, which nothing else knows. So the device uploads
// the EIDs it armed with and the server just remembers them, after which the existing
// 0x07/0x09/0x0A path resolves and carries reports unchanged. Additive: a server that
// predates this ignores 0x0C, and the only symptom is that no sightings are ever resolved.
//
// The trailing recovery bundle is what finders seal to, in place of the owner's identity
// bundle — the change that makes a sighting readable by a family member rather than only by
// the dead phone. Additive in the other direction too: the server sizes the EID array from
// `count`, so a relay that predates the bundle never reads those trailing bytes.
internal const val WS_OP_POF_REGISTER: Byte = 0x0C // [0x0C][u64 userid][u16 count]([20B eid]×count)[u16 bundleLen][bundle]

// ----------------------------------------------------------------
// Custom UWB tracker crowd-finding (DEV_BUILD). All methods no-op / return
// empty when the socket is down or the server is an older build that doesn't
// implement these opcodes (resolve/report-get time out). See TrackerProtocol
// and the mirror implementation in the server's findfamily.rs.
// ----------------------------------------------------------------

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
        android.util.Log.w("FF-Networking", "registerTracker failed", e); false
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
        android.util.Log.w("FF-Networking", "resolveTrackerBundle failed", e); null
    } finally {
        pendingResolves.remove(hex)
    }
}

/** Finder: upload a sealed crowd report keyed by the beacon epoch-id. */
suspend fun Networking.uploadTrackerReport(epochId: ByteArray, ciphertext: ByteArray): Boolean {
    val session = wsSession ?: return false
    return try {
        val frame = ByteArray(1 + epochId.size + ciphertext.size)
        frame[0] = WS_OP_REPORT_PUT
        epochId.copyInto(frame, 1)
        ciphertext.copyInto(frame, 1 + epochId.size)
        session.send(frame)
        true
    } catch (e: Exception) {
        android.util.Log.w("FF-Networking", "uploadTrackerReport failed", e); false
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
        android.util.Log.w("FF-Networking", "fetchTrackerReports failed", e); emptyList()
    } finally {
        pendingReportGets.remove(deferred)
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/**
 * Beacon: register the EIDs this device armed its Bluetooth controller with before shutting
 * down, together with the public half of the **recovery** keypair finders should seal to.
 *
 * Must be sent *before* the device powers off — once it is off it cannot re-register, and
 * unlike a live tracker it will not self-heal on the next heartbeat. Registrations are held
 * in memory server-side, so a relay restart during the powered-off window silently ends
 * findability until the device boots again.
 *
 * ## [recoveryBundle] and what it depends on
 * The relay's `ff_pof_eids` maps an EID to a *userid* and then answers RESOLVE with that
 * user's ordinary identity bundle from `pqc_keys_db`. That is the reason the feature could
 * not be shared: the only key that opened a sighting was `ff_pqcKemPriv`, and handing that to
 * a family member hands them every live location this user will ever publish. Sightings are
 * therefore sealed to a dedicated recovery bundle instead — see
 * [com.vayunmathur.findfamily.tracker.PoweredOffRecovery].
 *
 * The bundle is appended after the EID array as `[u16 bundleLen][bundle]`, and is optional:
 * a relay that predates it sizes the array from `count` and never reads those trailing bytes.
 * When the relay has no bundle for an EID set it falls back to the owner's identity bundle
 * exactly as before, so an old client and an old server both keep working.
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
        android.util.Log.w("FF-Networking", "registerPoweredOffEids failed", e); false
    }
}
