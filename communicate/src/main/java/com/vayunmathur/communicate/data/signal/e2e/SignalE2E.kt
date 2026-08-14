package com.vayunmathur.communicate.data.signal.e2e

import android.util.Log
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.signal.SignalE2EPreKey
import com.vayunmathur.communicate.data.signal.SignalE2ESenderKey
import com.vayunmathur.communicate.data.signal.SignalE2ESession
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Rust-backed E2E crypto for Signal (X3DH + Double Ratchet + Sender Keys + Sealed Sender).
 * Kotlin owns persistence (Room) and passes opaque blobs to Rust.
 *
 * Mirrors [com.vayunmathur.communicate.data.whatsapp.e2e.WhatsAppE2E] for Signal.
 * Record format is Rust's own versioned encoding (RECORD_VERSION=1).
 */
@OptIn(ExperimentalEncodingApi::class)
class SignalE2E(
    private val db: SignalDatabase,
    private val auth: SignalAuthData,
) {
    val ownIdentityPublicKey: ByteArray = b64(auth.identityPublicKey)
    private val ownIdentityPrivate: ByteArray = b64(auth.identityPrivateKey)
    private val ownSignedPreKeyPrivate: ByteArray = b64(auth.signedPreKeyPrivate)
    private val ownAci: String = auth.aci.ifEmpty { auth.phoneNumber }
    private val ownDeviceId: Int = auth.deviceId

    private fun parseAddress(address: String): Pair<String, Int> {
        val aci = address.substringBefore(":").substringBefore(".")
        val dev = address.substringAfter(":", "1").substringBefore("@").toIntOrNull() ?: 1
        // For bare ACI strings, device defaults to 1 (primary)
        return if (address.contains(":")) aci to dev else address to 1
    }

    fun signalAddress(aci: String, deviceId: Int = 1): Pair<String, Int> = aci to deviceId

    fun hasSession(aci: String, deviceId: Int = 1): Boolean =
        runBlocking { db.e2eSessionDao().exists(aci, deviceId) }

    fun deleteSession(aci: String, deviceId: Int = 1) {
        runBlocking { db.e2eSessionDao().delete(aci, deviceId) }
    }

    data class EncResult(val type: String, val data: ByteArray)

    fun encryptDM(aci: String, deviceId: Int, paddedPlaintext: ByteArray): EncResult {
        val entity = runBlocking { db.e2eSessionDao().get(aci, deviceId) }
            ?: throw RuntimeException("No session for $aci:$deviceId")
        val result = RustSignalCrypto.encryptSplit(entity.record, paddedPlaintext)
        runBlocking { db.e2eSessionDao().insert(SignalE2ESession(aci, deviceId, result.newSession)) }
        return EncResult(if (result.isPreKey) "prekey" else "whisper", result.body)
    }

    fun decryptDM(aci: String, deviceId: Int, isPreKey: Boolean, ciphertext: ByteArray): ByteArray {
        return if (isPreKey) {
            val preKeyId = parsePreKeyIdFromMessage(ciphertext)
            val oneTimePriv: ByteArray? = if (preKeyId != null) {
                val entity = runBlocking { db.e2ePreKeyDao().get(preKeyId) }
                entity?.let { rec -> if (rec.record.size >= 32) rec.record.copyOfRange(0, 32) else null }
            } else null
            val decrypted = RustSignalCrypto.decryptPreKeySplit(
                localIdentityPrivate = ownIdentityPrivate,
                localIdentityPublic = ownIdentityPublicKey,
                signedPreKeyPrivate = ownSignedPreKeyPrivate,
                oneTimePrivate = oneTimePriv,
                preKeyMessageBytes = ciphertext,
            )
            runBlocking {
                db.e2eSessionDao().insert(SignalE2ESession(aci, deviceId, decrypted.newSession))
                if (preKeyId != null) try { db.e2ePreKeyDao().delete(preKeyId) } catch (_: Exception) {}
            }
            decrypted.plaintext
        } else {
            val entity = runBlocking { db.e2eSessionDao().get(aci, deviceId) }
                ?: throw RuntimeException("No session for $aci:$deviceId (msg)")
            val decrypted = RustSignalCrypto.decryptMessageSplit(entity.record, ciphertext)
            runBlocking { db.e2eSessionDao().insert(SignalE2ESession(aci, deviceId, decrypted.newSession)) }
            decrypted.plaintext
        }
    }

    data class ParsedPreKeyBundle(
        val registrationId: Int,
        val preKeyId: Int?,
        val preKeyPublic: ByteArray?,
        val signedPreKeyId: Int,
        val signedPreKeyPublic: ByteArray,
        val signedPreKeySignature: ByteArray,
        val identityKey: ByteArray,
    )

    fun processPreKeyBundle(aci: String, deviceId: Int, bundle: ParsedPreKeyBundle) {
        val sessionBytes = RustSignalCrypto.processPreKeyBundle(
            localIdentityPrivate = ownIdentityPrivate,
            localIdentityPublic = ownIdentityPublicKey,
            localRegistrationId = auth.registrationId,
            registrationId = bundle.registrationId,
            preKeyId = bundle.preKeyId ?: -1,
            preKeyPublic = bundle.preKeyPublic,
            signedPreKeyId = bundle.signedPreKeyId,
            signedPreKeyPublic = bundle.signedPreKeyPublic,
            signedPreKeySignature = bundle.signedPreKeySignature,
            identityKey = bundle.identityKey,
        ) ?: throw RuntimeException("Rust processPreKeyBundle returned null")
        runBlocking { db.e2eSessionDao().insert(SignalE2ESession(aci, deviceId, sessionBytes)) }
    }

    // -- Group (sender key, for Signal GroupsV2 sender-key distribution) --

    fun createSenderKeyDistribution(groupId: String): ByteArray {
        val created = RustSignalCrypto.createSenderKeySplit()
        runBlocking { db.e2eSenderKeyDao().insert(SignalE2ESenderKey(ownAci, ownDeviceId, groupId, created.state)) }
        return created.skdm
    }

    fun processSenderKeyDistribution(groupId: String, senderAci: String, senderDeviceId: Int, skdmBytes: ByteArray) {
        val stateBytes = RustSignalCrypto.processSenderKey(skdmBytes)
            ?: throw RuntimeException("processSenderKey returned null")
        runBlocking { db.e2eSenderKeyDao().insert(SignalE2ESenderKey(senderAci, senderDeviceId, groupId, stateBytes)) }
    }

    fun encryptGroup(groupId: String, paddedPlaintext: ByteArray): ByteArray {
        var entity = runBlocking { db.e2eSenderKeyDao().get(ownAci, ownDeviceId, groupId) }
        if (entity == null) {
            val created = RustSignalCrypto.createSenderKeySplit()
            runBlocking { db.e2eSenderKeyDao().insert(SignalE2ESenderKey(ownAci, ownDeviceId, groupId, created.state)) }
            entity = runBlocking { db.e2eSenderKeyDao().get(ownAci, ownDeviceId, groupId) }
                ?: throw RuntimeException("Failed to create sender key")
        }
        val encrypted = RustSignalCrypto.encryptGroupSplit(entity.record, paddedPlaintext)
        runBlocking { db.e2eSenderKeyDao().insert(SignalE2ESenderKey(ownAci, ownDeviceId, groupId, encrypted.newState)) }
        return encrypted.data
    }

    fun decryptGroup(groupId: String, senderAci: String, senderDeviceId: Int, ciphertext: ByteArray): ByteArray {
        val entity = runBlocking { db.e2eSenderKeyDao().get(senderAci, senderDeviceId, groupId) }
            ?: throw RuntimeException("No sender key for $senderAci:$senderDeviceId in $groupId")
        val decrypted = RustSignalCrypto.decryptGroupSplit(entity.record, ciphertext)
        runBlocking { db.e2eSenderKeyDao().insert(SignalE2ESenderKey(senderAci, senderDeviceId, groupId, decrypted.newState)) }
        return decrypted.data
    }

    // -- Sealed sender (Signal-specific) --

    fun sealedSenderEncrypt(recipientAci: String, recipientDeviceId: Int, plaintext: ByteArray): ByteArray {
        return RustSignalCrypto.sealedSenderEncrypt(plaintext, recipientAci, recipientDeviceId)
            ?: throw RuntimeException("sealedSenderEncrypt returned null")
    }

    fun sealedSenderDecrypt(ciphertext: ByteArray): ByteArray {
        return RustSignalCrypto.sealedSenderDecrypt(ciphertext)
            ?: throw RuntimeException("sealedSenderDecrypt returned null")
    }

    // -- Prekey seeding --

    private data class LocalPreKey(val id: Int, val publicKey: ByteArray)

    private fun generatePreKeys(count: Int): List<LocalPreKey> {
        val maxId = runBlocking { db.e2ePreKeyDao().getMaxId() }
        val entities = ArrayList<SignalE2EPreKey>(count)
        val locals = ArrayList<LocalPreKey>(count)
        for (i in 1..count) {
            val id = maxId + i
            val kp = RustSignalCrypto.generateKeyPairSplit()
            val record = kp.privateKey + kp.publicKey
            entities.add(SignalE2EPreKey(id, record, uploaded = false))
            locals.add(LocalPreKey(id, kp.publicKey))
        }
        runBlocking { db.e2ePreKeyDao().insertAll(entities) }
        return locals
    }

    fun ensureSignedPreKeyStored() { /* no-op, keys in auth */ }

    fun markPreKeysUploaded() {
        val maxId = runBlocking { db.e2ePreKeyDao().getMaxId() }
        runBlocking { db.e2ePreKeyDao().markUploadedUpTo(maxId) }
    }

    companion object {
        private const val TAG = "SignalE2E"
        private fun b64(s: String): ByteArray = if (s.isEmpty()) ByteArray(0) else Base64.Default.decode(s)

        fun parsePreKeyIdFromMessage(data: ByteArray): Int? {
            if (data.isEmpty()) return null
            var pos = 1
            while (pos < data.size) {
                var keyVal = 0L
                var shift = 0
                var idx = pos
                var b: Int
                do {
                    if (idx >= data.size) return null
                    b = data[idx].toInt() and 0xFF
                    keyVal = keyVal or ((b and 0x7F).toLong() shl shift)
                    shift += 7
                    idx++
                } while (b and 0x80 != 0)
                val fieldNum = (keyVal shr 3).toInt()
                val wireType = (keyVal and 7).toInt()
                pos = idx
                if (wireType == 0) {
                    var v = 0L
                    shift = 0
                    while (pos < data.size) {
                        b = data[pos].toInt() and 0xFF
                        v = v or ((b and 0x7F).toLong() shl shift)
                        pos++
                        if (b and 0x80 == 0) break
                        shift += 7
                    }
                    if (fieldNum == 1) return v.toInt()
                } else if (wireType == 2) {
                    var len = 0L
                    shift = 0
                    while (pos < data.size) {
                        b = data[pos].toInt() and 0xFF
                        len = len or ((b and 0x7F).toLong() shl shift)
                        pos++
                        if (b and 0x80 == 0) break
                        shift += 7
                    }
                    if (pos + len > data.size) return null
                    pos += len.toInt()
                } else break
            }
            return null
        }

        fun signSignedPreKey(identityPrivate32: ByteArray, signedPreKeyPublic32: ByteArray): ByteArray {
            val message = ByteArray(33)
            message[0] = 0x05
            System.arraycopy(signedPreKeyPublic32, 0, message, 1, 32)
            return RustSignalCrypto.sign(identityPrivate32, message)
                ?: throw RuntimeException("Rust sign returned null")
        }
    }
}
