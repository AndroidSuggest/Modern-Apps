package com.vayunmathur.communicate.data.whatsapp

import android.util.Log
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol.MediaEncryptResult
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol.MediaKeys

// -- Cryptography helpers: app-state, media, message-ID, padding, poll votes --
// (Primitive helpers x25519 / hkdfSha256 / sha256 / hmacSha256 stay in
// WhatsAppProtocol.kt: NoiseHandshake depends on them as object members.)

/**
 * Expand a 32-byte app-state sync key into the 5 sub-keys via HKDF-SHA256 with info
 * "WhatsApp Mutation Keys" (160 bytes). Order: index, valueEncryption, valueMac,
 * snapshotMac, patchMac. Ref whatsmeow appstate/keys.go expandAppStateKeys.
 */
fun WhatsAppProtocol.expandAppStateKeys(keyData: ByteArray): Array<ByteArray> {
    val out = hkdfSha256(keyData, null, "WhatsApp Mutation Keys".toByteArray(Charsets.UTF_8), 160)
    return arrayOf(
        out.copyOfRange(0, 32),
        out.copyOfRange(32, 64),
        out.copyOfRange(64, 96),
        out.copyOfRange(96, 128),
        out.copyOfRange(128, 160),
    )
}

/**
 * Decrypt an app-state mutation/record value blob: [iv(16)][ciphertext][valueMac(32)] using
 * the valueEncryption sub-key (AES-256-CBC). MAC is not verified. Ref whatsmeow decodeMutation.
 */
fun WhatsAppProtocol.decryptAppStateValue(valueBlob: ByteArray, valueEncryptionKey: ByteArray): ByteArray? {
    if (valueBlob.size < 16 + 32) return null
    val content = valueBlob.copyOfRange(0, valueBlob.size - 32) // strip 32-byte valueMac
    val iv = content.copyOfRange(0, 16)
    val ciphertext = content.copyOfRange(16, content.size)
    return try {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(valueEncryptionKey, "AES"), IvParameterSpec(iv))
        cipher.doFinal(ciphertext)
    } catch (e: Exception) {
        null
    }
}

/**
 * Derive media encryption keys from a media key using HKDF.
 * Returns (iv, cipherKey, macKey, refKey) — each used in media encrypt/decrypt.
 * From whatsmeow/download.go getMediaKeys()
 */
fun WhatsAppProtocol.getMediaKeys(mediaKey: ByteArray, mediaType: String): MediaKeys {
    val expanded = hkdfSha256(mediaKey, null, mediaType.toByteArray(Charsets.UTF_8), 112)
    return MediaKeys(
        iv = expanded.copyOfRange(0, 16),
        cipherKey = expanded.copyOfRange(16, 48),
        macKey = expanded.copyOfRange(48, 80),
        refKey = expanded.copyOfRange(80, 112)
    )
}

/**
 * Encrypt media using AES-256-CBC + HMAC-SHA256.
 * From whatsmeow/upload.go Upload()
 */
fun WhatsAppProtocol.encryptMedia(plaintext: ByteArray, mediaType: String): MediaEncryptResult {
    val random = SecureRandom()
    val mediaKey = ByteArray(32).also { random.nextBytes(it) }
    val keys = getMediaKeys(mediaKey, mediaType)

    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    val keySpec = SecretKeySpec(keys.cipherKey, "AES")
    val ivSpec = IvParameterSpec(keys.iv)
    cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
    val ciphertext = cipher.doFinal(plaintext)

    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(keys.macKey, "HmacSHA256"))
    mac.update(keys.iv)
    mac.update(ciphertext)
    val macValue = mac.doFinal()

    val dataToUpload = ciphertext + macValue.copyOfRange(0, 10)
    val fileSha256 = sha256(plaintext)
    val fileEncSha256 = sha256(dataToUpload)

    return MediaEncryptResult(
        mediaKey = mediaKey,
        encryptedData = dataToUpload,
        fileSha256 = fileSha256,
        fileEncSha256 = fileEncSha256,
        fileLength = plaintext.size.toLong()
    )
}

/**
 * Decrypt downloaded media.
 * From whatsmeow/download.go
 */
fun WhatsAppProtocol.decryptMedia(ciphertextWithMac: ByteArray, mediaKey: ByteArray, mediaType: String): ByteArray {
    val keys = getMediaKeys(mediaKey, mediaType)

    val macOffset = ciphertextWithMac.size - 10
    val ciphertext = ciphertextWithMac.copyOfRange(0, macOffset)

    // Verify HMAC
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(keys.macKey, "HmacSHA256"))
    mac.update(keys.iv)
    mac.update(ciphertext)
    val expectedMac = mac.doFinal().copyOfRange(0, 10)
    val actualMac = ciphertextWithMac.copyOfRange(macOffset, ciphertextWithMac.size)
    if (!expectedMac.contentEquals(actualMac)) {
        throw SecurityException("Media HMAC verification failed")
    }

    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    val keySpec = SecretKeySpec(keys.cipherKey, "AES")
    val ivSpec = IvParameterSpec(keys.iv)
    cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
    return cipher.doFinal(ciphertext)
}

// -- Message ID generation --

/**
 * Generate a message ID: "3EB0" + uppercase hex of sha256(unixSeconds ++ ownUser@c.us ++ random16)[:9].
 * From whatsmeow/send.go Client.GenerateMessageID().
 */
fun WhatsAppProtocol.generateMessageId(ownJid: String?): String {
    val buf = java.io.ByteArrayOutputStream()
    val ts = ByteArray(8)
    ByteBuffer.wrap(ts).putLong(System.currentTimeMillis() / 1000L)
    buf.write(ts)
    if (!ownJid.isNullOrEmpty()) {
        val user = ownJid.substringBefore('@').substringBefore(':')
        buf.write(user.toByteArray(Charsets.UTF_8))
        buf.write("@c.us".toByteArray(Charsets.UTF_8))
    }
    val rand = ByteArray(16)
    SecureRandom().nextBytes(rand)
    buf.write(rand)
    val hash = sha256(buf.toByteArray())
    val hex = hash.copyOfRange(0, 9).joinToString("") { "%02X".format(it) }
    return WEB_MESSAGE_ID_PREFIX + hex
}

// -- Message padding (Signal Protocol requirement) --

/**
 * Pad message with random 1-15 bytes where each pad byte equals the pad count.
 * From whatsmeow/message.go padMessage(): random byte masked to 0x0f, 0 -> 15.
 */
fun WhatsAppProtocol.padMessage(plaintext: ByteArray): ByteArray {
    var padSize = SecureRandom().nextInt(16)
    if (padSize == 0) padSize = 15
    val padded = ByteArray(plaintext.size + padSize)
    System.arraycopy(plaintext, 0, padded, 0, plaintext.size)
    for (i in plaintext.size until padded.size) {
        padded[i] = padSize.toByte()
    }
    return padded
}

fun WhatsAppProtocol.unpadMessage(padded: ByteArray): ByteArray {
    if (padded.isEmpty()) return padded
    val padSize = padded.last().toInt() and 0xFF
    if (padSize == 0 || padSize > padded.size) return padded
    return padded.copyOfRange(0, padded.size - padSize)
}

/**
 * Build an encrypted poll vote (PollUpdateMessage) as a Message proto, routed through the
 * normal send path. Selected options are SHA-256 hashes of the chosen option names; the vote
 * payload is AES-256-GCM encrypted with a key derived from the poll's messageSecret.
 * Ref whatsmeow msgsecret.go BuildPollVote / EncryptPollVote.
 */
fun WhatsAppProtocol.buildPollVoteMessage(
    chatJid: String,
    pollMessageId: String,
    pollCreatorJid: String,
    pollFromMe: Boolean,
    voterJid: String,
    optionHashes: List<ByteArray>,
    pollSecret: ByteArray,
): com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.Message {
    val vote = com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.PollVoteMessage.newBuilder()
    optionHashes.forEach { vote.addSelectedOptions(com.google.protobuf.ByteString.copyFrom(it)) }
    val plaintext = vote.build().toByteArray()

    val (key, aad) = pollVoteKeyAndAad(pollSecret, pollMessageId, pollCreatorJid, voterJid)
    val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
    val ciphertext = aesGcm(Cipher.ENCRYPT_MODE, key, iv, plaintext, aad)

    val msgKey = com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.MessageKey.newBuilder()
        .setFromMe(pollFromMe)
        .setId(pollMessageId)
        .setRemoteJid(chatJid)
    if (!pollFromMe && chatJid.contains("@g.us") && pollCreatorJid.isNotEmpty()) {
        msgKey.setParticipant(pollCreatorJid)
    }
    val update = com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.PollUpdateMessage.newBuilder()
        .setPollCreationMessageKey(msgKey.build())
        .setVote(
            com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.PollEncValue.newBuilder()
                .setEncPayload(com.google.protobuf.ByteString.copyFrom(ciphertext))
                .setEncIV(com.google.protobuf.ByteString.copyFrom(iv))
        )
        .setSenderTimestampMS(System.currentTimeMillis())
    return com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()
        .setPollUpdateMessage(update.build())
        .build()
}

/**
 * Decrypt an incoming poll vote, returning the SHA-256 option hashes the voter selected (or
 * null on failure). The caller maps hashes back to option names via the stored poll options.
 */
fun WhatsAppProtocol.decryptPollVote(
    update: com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.PollUpdateMessage,
    pollMessageId: String,
    pollCreatorJid: String,
    voterJid: String,
    pollSecret: ByteArray,
): List<ByteArray>? {
    return try {
        val (key, aad) = pollVoteKeyAndAad(pollSecret, pollMessageId, pollCreatorJid, voterJid)
        val plaintext = aesGcm(
            Cipher.DECRYPT_MODE, key,
            update.vote.encIV.toByteArray(),
            update.vote.encPayload.toByteArray(),
            aad,
        )
        com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.PollVoteMessage
            .parseFrom(plaintext)
            .selectedOptionsList
            .map { it.toByteArray() }
    } catch (e: Exception) {
        Log.w(TAG, "Poll vote decrypt failed", e)
        null
    }
}

/**
 * Derive the poll-vote key + GCM additional-data, matching whatsmeow generateMsgSecretKey for
 * the "Poll Vote" use case:
 *   key = HKDF-SHA256(pollSecret, salt=nil, info = pollMsgId + creatorJid + voterJid + "Poll Vote")
 *   aad = pollMsgId + 0x00 + voterJid
 * JIDs are normalized to bare user@server (no device/agent) to match the peer.
 */
private fun WhatsAppProtocol.pollVoteKeyAndAad(
    pollSecret: ByteArray,
    pollMessageId: String,
    pollCreatorJid: String,
    voterJid: String,
): Pair<ByteArray, ByteArray> {
    val creator = normalizeJidForSecret(pollCreatorJid)
    val voter = normalizeJidForSecret(voterJid)
    val info = pollMessageId.toByteArray(Charsets.UTF_8) +
        creator.toByteArray(Charsets.UTF_8) +
        voter.toByteArray(Charsets.UTF_8) +
        "Poll Vote".toByteArray(Charsets.UTF_8)
    val key = hkdfSha256(pollSecret, null, info, 32)
    val aad = pollMessageId.toByteArray(Charsets.UTF_8) +
        byteArrayOf(0) +
        voter.toByteArray(Charsets.UTF_8)
    return key to aad
}

private fun WhatsAppProtocol.normalizeJidForSecret(jid: String): String {
    val user = jid.substringBefore("@").substringBefore(":").substringBefore(".")
    val server = jid.substringAfter("@", "s.whatsapp.net")
    return "$user@$server"
}

private fun WhatsAppProtocol.aesGcm(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray, aad: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
    cipher.updateAAD(aad)
    return cipher.doFinal(data)
}
