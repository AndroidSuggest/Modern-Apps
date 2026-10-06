package com.vayunmathur.communicate.data.signal.e2e

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import com.vayunmathur.communicate.data.signal.e2e.SignalE2E.PreKeyUpload

/**
 * Identity/store queries for [SignalE2E] (split for file length).
 * Extension functions on [SignalE2E]; behavior identical, call sites unchanged.
 */

fun SignalE2E.callIdentityKey(aci: String): ByteArray? =
    storedIdentityKey(aci)?.let { rawPublicKeyBytes(it) }

fun SignalE2E.storedIdentityKey(aci: String): ByteArray? =
    try { protocolStore.getIdentity(signalAddress(aci))?.serialize() } catch (_: Exception) { null }

/**
 * Record [identityKey] as the trusted identity for [aci] and archive existing sessions, so the next
 * message builds a session against the accepted key. Only call this once the user has verified it.
 */
fun SignalE2E.acceptIdentity(aci: String, identityKey: ByteArray): Boolean = try {
    val address = signalAddress(aci)
    protocolStore.saveIdentity(address, IdentityKey(identityKey))
    // Every device's session was built against the old key.
    deviceIdsWithSessions(aci).forEach { archiveSession(aci, it) }
    true
} catch (ignored: Throwable) {
    false
}

/**
 * The registration id to address [aci]'s device with, from our session with them. A send whose
 * `destinationRegistrationId` does not match what the server holds is rejected as a stale device.
 */
fun SignalE2E.remoteRegistrationId(aci: String, deviceId: Int): Int? = try {
    protocolStore.loadSession(signalAddress(aci, deviceId)).remoteRegistrationId
} catch (_: Throwable) {
    null
}

/** The sender's registration id, carried on every pre-key message. */
fun SignalE2E.senderRegistrationId(ciphertext: ByteArray): Int? = try {
    PreKeySignalMessage(ciphertext).registrationId
} catch (_: Throwable) {
    null
}

/** Whether our store holds a signed pre-key [id] whose public half is exactly [publicKey]. */
fun SignalE2E.hasSignedPreKeyMatching(id: Int, publicKey: ByteArray?): Boolean {
    if (publicKey == null) return true
    return try {
        val record = protocolStore.loadSignedPreKey(id)
        record.keyPair.publicKey.serialize().contentEquals(publicKey)
    } catch (_: Throwable) {
        false
    }
}

/** Rotate the signed pre-key regardless of what is stored, for when the server's copy is unusable. */
fun SignalE2E.rotateSignedPreKeyNow(): PreKeyUpload.KeyEntity? = rotateSignedPreKey()

/** Devices of [aci] we already have a session with, always including device 1. */
fun SignalE2E.deviceIdsWithSessions(aci: String): List<Int> {
    val subDevices = try { protocolStore.getSubDeviceSessions(aci) } catch (_: Exception) { emptyList() }
    return (listOf(1) + subDevices).distinct().sorted()
}
