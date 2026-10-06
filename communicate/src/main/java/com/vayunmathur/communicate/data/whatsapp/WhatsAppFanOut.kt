package com.vayunmathur.communicate.data.whatsapp

import android.util.Log
import com.vayunmathur.communicate.data.whatsapp.e2e.WhatsAppE2E

/**
 * Device fan-out encryption for [WhatsAppClient] (split for file length).
 * Extension functions on [WhatsAppClient]; behavior identical, call sites unchanged.
 */

/**
 * Establish sessions and Signal-encrypt a (padded) plaintext for each device, skipping our own
 * current device. Own other devices get [dsmPlaintextPadded] (the DeviceSentMessage); everyone
 * else gets [msgPlaintextPadded]. Mirrors whatsmeow send.go encryptMessageForDevices.
 */
internal suspend fun WhatsAppClient.encryptForDevices(
    crypto: WhatsAppE2E,
    devices: List<String>,
    ownUser: String,
    ownDeviceJid: String,
    msgPlaintextPadded: ByteArray,
    dsmPlaintextPadded: ByteArray?,
): Pair<List<WhatsAppProtocol.ParticipantEnc>, Boolean> {
    val encs = mutableListOf<WhatsAppProtocol.ParticipantEnc>()
    var includeIdentity = false
    // Our own device number (from wid "user.agent:device@..."). usync returns devices as
    // "user:device@..." (no agent), so comparing against the raw wid never matches and we'd
    // fan out to OURSELVES — which the server rejects with <conflict type=device_removed>.
    val ownDeviceNum = ownDeviceJid.substringBefore("@").substringAfter(":", "0").toIntOrNull() ?: 0
    for (dev in devices.distinct()) {
        encryptForDevice(crypto, dev, ownUser, ownDeviceNum, msgPlaintextPadded, dsmPlaintextPadded)?.let {
            if (it.second == "pkmsg") includeIdentity = true
            encs.add(WhatsAppProtocol.ParticipantEnc(dev, it.second, it.first))
        }
    }
    return encs to includeIdentity
}

/** Encrypt for one device; null when skipped. Returns (data, type). */
private suspend fun WhatsAppClient.encryptForDevice(
    crypto: WhatsAppE2E,
    dev: String,
    ownUser: String,
    ownDeviceNum: Int,
    msgPlaintextPadded: ByteArray,
    dsmPlaintextPadded: ByteArray?,
): Pair<ByteArray, String>? {
    val devUser = dev.substringBefore("@").substringBefore(":").substringBefore(".")
    val devNum = dev.substringBefore("@").substringAfter(":", "0").toIntOrNull() ?: 0
    if (devUser == ownUser && devNum == ownDeviceNum) return null // skip our own device
    val plaintext =
        if (devUser == ownUser && dsmPlaintextPadded != null) dsmPlaintextPadded else msgPlaintextPadded
    if (!ensureSession(dev)) {
        Log.w(WhatsAppClient.TAG, "No session for device $dev; skipping in fan-out")
        return null
    }
    return try {
        val enc = crypto.encryptDM(dev, plaintext)
        enc.data to enc.type
    } catch (expected: Exception) {
        Log.w(WhatsAppClient.TAG, "Encrypt failed for device $dev", expected)
        null
    }
}
