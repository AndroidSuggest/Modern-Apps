package com.vayunmathur.messages.whatsapp.e2e

import android.util.Log
import com.vayunmathur.messages.whatsapp.WhatsAppAuthData
import com.vayunmathur.messages.whatsapp.WhatsAppDatabase
import com.vayunmathur.messages.whatsapp.WhatsAppE2EPreKey
import com.vayunmathur.messages.whatsapp.WhatsAppE2ESenderKey
import com.vayunmathur.messages.whatsapp.WhatsAppE2ESession
import com.vayunmathur.messages.whatsapp.WhatsAppProtocol
import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Rust-backed E2E crypto for WhatsApp (X3DH + Double Ratchet + Sender Keys).
 * Kotlin owns persistence (Room) and passes opaque session/sender-key blobs to Rust.
 *
 * Record format is Rust's own versioned encoding (RECORD_VERSION=1), not Java's
 * SignalRecord. Migration 6->7 clears the E2E tables, forcing a re-link.
 */
@OptIn(ExperimentalEncodingApi::class)
class WhatsAppE2E(
    private val db: WhatsAppDatabase,
    private val auth: WhatsAppAuthData,
) {

    /** Raw 32-byte identity public key for upload. */
    val ownIdentityPublicKey: ByteArray = b64(auth.identityPublicKey)

    private val ownIdentityPrivate: ByteArray = b64(auth.identityPrivateKey)
    private val ownSignedPreKeyPrivate: ByteArray = b64(auth.signedPreKeyPrivate)
    private val ownSignedPreKeyPublic: ByteArray = b64(auth.signedPreKeyPublic)

    private val ownUser: String = auth.wid.substringBefore("@").substringBefore(":").substringBefore(".")
    private val ownDeviceId: Int = auth.deviceId

    // -----------------------------------------------------------------------
    // JID parsing — matches whatsmeow JID.SignalAddress: name=user, deviceId=device
    // -----------------------------------------------------------------------

    private fun parseJid(jid: String): Pair<String, Int> {
        val local = jid.substringBefore("@")
        val user = local.substringBefore(":").substringBefore(".")
        val device = local.substringAfter(":", "0").toIntOrNull() ?: 0
        return user to device
    }

    fun signalAddress(jid: String): Pair<String, Int> = parseJid(jid)

    fun hasSession(jid: String): Boolean {
        val (name, dev) = parseJid(jid)
        return runBlocking { db.e2eSessionDao().exists(name, dev) }
    }

    fun deleteSession(jid: String) {
        val (name, dev) = parseJid(jid)
        runBlocking { db.e2eSessionDao().delete(name, dev) }
    }

    // -- 1:1 encrypt / decrypt ------------------------------------------------

    data class EncResult(val type: String, val data: ByteArray)

    fun encryptDM(jid: String, paddedPlaintext: ByteArray): EncResult {
        val (name, dev) = parseJid(jid)
        val entity = runBlocking { db.e2eSessionDao().get(name, dev) }
            ?: throw RuntimeException("No session for $jid")
        val result = RustWhatsAppCrypto.encryptSplit(entity.record, paddedPlaintext)
        runBlocking {
            db.e2eSessionDao().insert(WhatsAppE2ESession(name, dev, result.newSession))
        }
        return EncResult(if (result.isPreKey) "pkmsg" else "msg", result.body)
    }

    fun decryptDM(jid: String, isPreKey: Boolean, ciphertext: ByteArray): ByteArray {
        val (name, dev) = parseJid(jid)
        return if (isPreKey) {
            // Inbound pkmsg: X3DH bob side + first message.
            val preKeyId = parsePreKeyIdFromMessage(ciphertext)
            val oneTimePriv: ByteArray? = if (preKeyId != null) {
                val entity = runBlocking { db.e2ePreKeyDao().get(preKeyId) }
                entity?.let { rec ->
                    // record = private(32) || public(32)
                    if (rec.record.size >= 32) rec.record.copyOfRange(0, 32) else null
                }
            } else null

            val decrypted = RustWhatsAppCrypto.decryptPreKeySplit(
                localIdentityPrivate = ownIdentityPrivate,
                localIdentityPublic = ownIdentityPublicKey,
                signedPreKeyPrivate = ownSignedPreKeyPrivate,
                oneTimePrivate = oneTimePriv,
                preKeyMessageBytes = ciphertext,
            )
            runBlocking {
                db.e2eSessionDao().insert(WhatsAppE2ESession(name, dev, decrypted.newSession))
                if (preKeyId != null) {
                    try { db.e2ePreKeyDao().delete(preKeyId) } catch (_: Exception) {}
                }
            }
            decrypted.plaintext
        } else {
            val entity = runBlocking { db.e2eSessionDao().get(name, dev) }
                ?: throw RuntimeException("No session for $jid (msg)")
            val decrypted = RustWhatsAppCrypto.decryptMessageSplit(entity.record, ciphertext)
            runBlocking {
                db.e2eSessionDao().insert(WhatsAppE2ESession(name, dev, decrypted.newSession))
            }
            decrypted.plaintext
        }
    }

    // -- Parsed bundle --------------------------------------------------------

    data class ParsedPreKeyBundle(
        val registrationId: Int,
        val preKeyId: Int?, // null if absent
        val preKeyPublic: ByteArray?, // 32 bytes or null
        val signedPreKeyId: Int,
        val signedPreKeyPublic: ByteArray, // 32
        val signedPreKeySignature: ByteArray, // 64
        val identityKey: ByteArray, // 32
    )

    fun processPreKeyBundle(jid: String, bundle: ParsedPreKeyBundle) {
        val (name, dev) = parseJid(jid)
        val sessionBytes = RustWhatsAppCrypto.processPreKeyBundle(
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
        runBlocking {
            db.e2eSessionDao().insert(WhatsAppE2ESession(name, dev, sessionBytes))
        }
    }

    // -- Group (sender key) ---------------------------------------------------

    fun createSenderKeyDistribution(groupJid: String): ByteArray {
        // Always mint a new sender key (matches GroupSessionBuilder.create overwriting).
        val created = RustWhatsAppCrypto.createSenderKeySplit()
        runBlocking {
            db.e2eSenderKeyDao().insert(
                WhatsAppE2ESenderKey(ownUser, ownDeviceId, groupJid, created.state)
            )
        }
        return created.skdm
    }

    fun processSenderKeyDistribution(groupJid: String, senderJid: String, skdmBytes: ByteArray) {
        val (sName, sDev) = parseJid(senderJid)
        val stateBytes = RustWhatsAppCrypto.processSenderKey(skdmBytes)
            ?: throw RuntimeException("processSenderKey returned null")
        runBlocking {
            db.e2eSenderKeyDao().insert(
                WhatsAppE2ESenderKey(sName, sDev, groupJid, stateBytes)
            )
        }
    }

    fun encryptGroup(groupJid: String, paddedPlaintext: ByteArray): ByteArray {
        var stateEntity = runBlocking { db.e2eSenderKeyDao().get(ownUser, ownDeviceId, groupJid) }
        if (stateEntity == null) {
            // No sender key yet — create one (first group message).
            val created = RustWhatsAppCrypto.createSenderKeySplit()
            runBlocking {
                db.e2eSenderKeyDao().insert(
                    WhatsAppE2ESenderKey(ownUser, ownDeviceId, groupJid, created.state)
                )
            }
            stateEntity = runBlocking { db.e2eSenderKeyDao().get(ownUser, ownDeviceId, groupJid) }
                ?: throw RuntimeException("Failed to create sender key")
        }
        val encrypted = RustWhatsAppCrypto.encryptGroupSplit(stateEntity.record, paddedPlaintext)
        runBlocking {
            db.e2eSenderKeyDao().insert(
                WhatsAppE2ESenderKey(ownUser, ownDeviceId, groupJid, encrypted.newState)
            )
        }
        return encrypted.data
    }

    fun decryptGroup(groupJid: String, senderJid: String, ciphertext: ByteArray): ByteArray {
        val (sName, sDev) = parseJid(senderJid)
        val entity = runBlocking { db.e2eSenderKeyDao().get(sName, sDev, groupJid) }
            ?: throw RuntimeException("No sender key for $senderJid in $groupJid")
        val decrypted = RustWhatsAppCrypto.decryptGroupSplit(entity.record, ciphertext)
        runBlocking {
            db.e2eSenderKeyDao().insert(
                WhatsAppE2ESenderKey(sName, sDev, groupJid, decrypted.newState)
            )
        }
        return decrypted.data
    }

    // -- Prekey seeding & upload ---------------------------------------------

    /**
     * Previously seeded the signed prekey into the Java store for inbound pkmsg.
     * With Rust we use the raw keys from auth directly, so this is now a no-op
     * kept for call-site compatibility.
     */
    fun ensureSignedPreKeyStored() {
        // no-op
    }

    private data class LocalPreKey(val id: Int, val publicKey: ByteArray)

    private fun generatePreKeys(count: Int): List<LocalPreKey> {
        val maxId = runBlocking { db.e2ePreKeyDao().getMaxId() }
        val entities = ArrayList<WhatsAppE2EPreKey>(count)
        val locals = ArrayList<LocalPreKey>(count)
        for (i in 1..count) {
            val id = maxId + i
            val kp = RustWhatsAppCrypto.generateKeyPairSplit()
            // Store as private||public (64 bytes) — private needed for inbound pkmsg.
            val record = kp.privateKey + kp.publicKey
            entities.add(WhatsAppE2EPreKey(id, record, uploaded = false))
            locals.add(LocalPreKey(id, kp.publicKey))
        }
        runBlocking { db.e2ePreKeyDao().insertAll(entities) }
        return locals
    }

    fun buildPreKeyUploadContent(initialUpload: Boolean): List<WhatsAppProtocol.Node> {
        val wanted = if (initialUpload) 812 else 50
        val records = generatePreKeys(wanted)

        val regBytes = ByteArray(4)
        regBytes[0] = (auth.registrationId ushr 24).toByte()
        regBytes[1] = (auth.registrationId ushr 16).toByte()
        regBytes[2] = (auth.registrationId ushr 8).toByte()
        regBytes[3] = auth.registrationId.toByte()

        val listNode = WhatsAppProtocol.Node(
            tag = "list",
            content = records.map { preKeyToNode(it.id, it.publicKey, null) },
        )
        val signedNode = preKeyToNode(
            auth.signedPreKeyId,
            b64(auth.signedPreKeyPublic),
            b64(auth.signedPreKeySignature),
        )

        return listOf(
            WhatsAppProtocol.Node(tag = "registration", data = regBytes),
            WhatsAppProtocol.Node(tag = "type", data = byteArrayOf(0x05)),
            WhatsAppProtocol.Node(tag = "identity", data = ownIdentityPublicKey),
            listNode,
            signedNode,
        )
    }

    fun markPreKeysUploaded() {
        val maxId = runBlocking { db.e2ePreKeyDao().getMaxId() }
        runBlocking { db.e2ePreKeyDao().markUploadedUpTo(maxId) }
    }

    fun buildRetryReceiptKeysNode(accountDeviceIdentity: ByteArray?): WhatsAppProtocol.Node {
        val oneTime = generatePreKeys(1).first()
        val children = mutableListOf(
            WhatsAppProtocol.Node(tag = "type", data = byteArrayOf(0x05)),
            WhatsAppProtocol.Node(tag = "identity", data = ownIdentityPublicKey),
            preKeyToNode(oneTime.id, oneTime.publicKey, null),
            preKeyToNode(auth.signedPreKeyId, b64(auth.signedPreKeyPublic), b64(auth.signedPreKeySignature)),
        )
        if (accountDeviceIdentity != null) {
            children.add(WhatsAppProtocol.Node(tag = "device-identity", data = accountDeviceIdentity))
        }
        return WhatsAppProtocol.Node(tag = "keys", content = children)
    }

    private fun preKeyToNode(id: Int, pub32: ByteArray, signature: ByteArray?): WhatsAppProtocol.Node {
        val idBytes = byteArrayOf(
            (id ushr 16).toByte(),
            (id ushr 8).toByte(),
            id.toByte(),
        )
        val children = mutableListOf(
            WhatsAppProtocol.Node(tag = "id", data = idBytes),
            WhatsAppProtocol.Node(tag = "value", data = pub32),
        )
        return if (signature != null) {
            children.add(WhatsAppProtocol.Node(tag = "signature", data = signature))
            WhatsAppProtocol.Node(tag = "skey", content = children)
        } else {
            WhatsAppProtocol.Node(tag = "key", content = children)
        }
    }

    fun parsePreKeyBundleNode(deviceId: Int, userNode: WhatsAppProtocol.Node): ParsedPreKeyBundle? {
        return try {
            if (userNode.getChildByTag("error") != null) {
                Log.w(TAG, "prekey response error for device $deviceId")
                return null
            }
            val regBytes = userNode.getChildByTag("registration")?.data ?: return null
            if (regBytes.size != 4) return null
            val registrationId = ((regBytes[0].toInt() and 0xFF) shl 24) or
                ((regBytes[1].toInt() and 0xFF) shl 16) or
                ((regBytes[2].toInt() and 0xFF) shl 8) or
                (regBytes[3].toInt() and 0xFF)

            val keysNode = userNode.getChildByTag("keys") ?: userNode

            val identityRaw = keysNode.getChildByTag("identity")?.data ?: return null
            if (identityRaw.size != 32) return null

            var preKeyId: Int? = null
            var preKeyPublic: ByteArray? = null
            keysNode.getChildByTag("key")?.let { keyNode ->
                preKeyId = readKeyId(keyNode) ?: return null
                val pub = keyNode.getChildByTag("value")?.data ?: return null
                if (pub.size != 32) return null
                preKeyPublic = pub
            }

            val skey = keysNode.getChildByTag("skey") ?: return null
            val signedPreKeyId = readKeyId(skey) ?: return null
            val signedPub = skey.getChildByTag("value")?.data ?: return null
            if (signedPub.size != 32) return null
            val signedSig = skey.getChildByTag("signature")?.data ?: return null
            if (signedSig.size != 64) return null

            ParsedPreKeyBundle(
                registrationId = registrationId,
                preKeyId = preKeyId,
                preKeyPublic = preKeyPublic,
                signedPreKeyId = signedPreKeyId,
                signedPreKeyPublic = signedPub,
                signedPreKeySignature = signedSig,
                identityKey = identityRaw,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse prekey bundle", e)
            null
        }
    }

    private fun readKeyId(node: WhatsAppProtocol.Node): Int? {
        val idBytes = node.getChildByTag("id")?.data ?: return null
        if (idBytes.size != 3) return null
        return ((idBytes[0].toInt() and 0xFF) shl 16) or
            ((idBytes[1].toInt() and 0xFF) shl 8) or
            (idBytes[2].toInt() and 0xFF)
    }

    companion object {
        private const val TAG = "WhatsAppE2E"

        private fun b64(s: String): ByteArray = Base64.Default.decode(s)

        /**
         * Parse the optional preKeyId from a PreKeySignalMessage (version || protobuf).
         * Minimal protobuf scan for field 1 varint.
         */
        fun parsePreKeyIdFromMessage(data: ByteArray): Int? {
            if (data.isEmpty()) return null
            // Skip version byte (high nibble version)
            var pos = 1
            while (pos < data.size) {
                val key = data[pos].toInt() and 0xFF
                // Need varint key? In our wire format, field keys are small varints (<128) so single byte.
                // But to be safe, read varint.
                var fieldNum = 0
                var shift = 0
                var idx = pos
                var b: Int
                var keyVal = 0L
                do {
                    if (idx >= data.size) return null
                    b = data[idx].toInt() and 0xFF
                    keyVal = keyVal or ((b and 0x7F).toLong() shl shift)
                    shift += 7
                    idx++
                } while (b and 0x80 != 0)
                fieldNum = (keyVal shr 3).toInt()
                val wireType = (keyVal and 7).toInt()
                pos = idx
                if (wireType == 0) {
                    // varint
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
                    // length-delimited
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
                } else {
                    // Skip other wire types (not expected for preKeyId)
                    break
                }
            }
            return null
        }

        /**
         * XEdDSA signature over 0x05||signedPreKeyPub using raw 32-byte identity private.
         * Uses Rust for constant-time signing.
         */
        fun signSignedPreKey(identityPrivate32: ByteArray, signedPreKeyPublic32: ByteArray): ByteArray {
            val message = ByteArray(33)
            message[0] = 0x05
            System.arraycopy(signedPreKeyPublic32, 0, message, 1, 32)
            return RustWhatsAppCrypto.sign(identityPrivate32, message)
                ?: throw RuntimeException("Rust sign returned null")
        }
    }
}
