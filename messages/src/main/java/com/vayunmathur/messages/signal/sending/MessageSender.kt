package com.vayunmathur.messages.signal.sending

import android.util.Base64
import android.util.Log
import com.vayunmathur.messages.signal.proto.SignalServiceProtos
import com.vayunmathur.messages.signal.groups.SenderKeyManager
import com.vayunmathur.messages.signal.store.SignalGroupStore
import com.vayunmathur.messages.signal.store.SignalProtocolStoreImpl
import com.vayunmathur.messages.signal.web.SignalWebSocket
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.signal.libsignal.metadata.SealedSessionCipher
import org.signal.libsignal.metadata.certificate.SenderCertificate
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import org.signal.libsignal.protocol.groups.state.SenderKeyStore
import java.util.UUID

class MessageSender(
    private val ws: SignalWebSocket,
    private val sessionStore: SessionStore,
    private val identityKeyStore: IdentityKeyStore,
    private val preKeyStore: PreKeyStore,
    private val signedPreKeyStore: SignedPreKeyStore,
    private val kyberPreKeyStore: KyberPreKeyStore,
    private val senderKeyStore: SenderKeyStore,
    private val selfAci: String,
    private val selfDeviceId: Int,
    private val deviceManager: DeviceManager,
    private val recipientStore: com.vayunmathur.messages.signal.store.SignalRecipientStore? = null,
    private var unauthedWs: SignalWebSocket? = null,
    private val groupStore: SignalGroupStore? = null,
    private val selfPni: String? = null,
    private val aciIdentityKeyPair: IdentityKeyPair? = null,
    private val pniIdentityKeyPair: IdentityKeyPair? = null,
    private val accountRecord: com.vayunmathur.messages.signal.proto.AccountRecord? = null,
    private val encryptionLock: kotlinx.coroutines.sync.Mutex? = null,
) {
    private val protocolStore = SignalProtocolStoreImpl(
        sessionStore, identityKeyStore, preKeyStore, signedPreKeyStore, kyberPreKeyStore, senderKeyStore
    )

    // Issue #14: Pending message dedup — track in-flight timestamps
    private val pendingTimestamps = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Long, Boolean>())

    val senderKeyManager = SenderKeyManager(
        senderKeyStore as com.vayunmathur.messages.signal.store.SignalSenderKeyStore,
        selfAci,
        selfDeviceId,
    )

    @Volatile
    private var cachedSenderCertificate: SenderCertificate? = null
    private var senderCertExpiry: Long = 0

    private val sendCache = LinkedHashMap<SendCacheKey, SignalServiceProtos.Content>(64, 0.75f, true)
    private val sendCacheLock = Any()

    private data class SendCacheKey(val recipientAci: String, val groupId: String?, val timestamp: Long)

    private fun addSendCache(recipientAci: String, groupId: String?, timestamp: Long, content: SignalServiceProtos.Content) {
        synchronized(sendCacheLock) {
            if (sendCache.size >= 128) {
                val iter = sendCache.entries.iterator()
                if (iter.hasNext()) { iter.next(); iter.remove() }
            }
            sendCache[SendCacheKey(recipientAci, groupId, timestamp)] = content
        }
    }

    fun getCachedContent(recipientAci: String, groupId: String?, timestamp: Long): SignalServiceProtos.Content? {
        synchronized(sendCacheLock) {
            return sendCache[SendCacheKey(recipientAci, groupId, timestamp)]
        }
    }

    private suspend fun fetchSenderCertificate(): SenderCertificate {
        val now = System.currentTimeMillis()
        cachedSenderCertificate?.let { cached ->
            if (senderCertExpiry - now > 24 * 60 * 60 * 1000L) return cached
        }
        val resp = ws.sendRequest("GET", "/v1/certificate/delivery?includeE164=false", null, emptyMap())
        val json = JSONObject(String(resp.body.toByteArray()))
        val certBytes = Base64.decode(json.getString("certificate"), Base64.DEFAULT)
        val cert = SenderCertificate(certBytes)
        cachedSenderCertificate = cert
        senderCertExpiry = cert.expiration
        return cert
    }

    class RecipientUnregisteredException(aci: String) : Exception("Recipient not registered (404): $aci")

    suspend fun sendMessage(
        recipientAci: String,
        content: SignalServiceProtos.Content,
        timestamp: Long,
    ): SendResult {
        // Issue #14: Dedup — skip if this timestamp is already in-flight
        if (!pendingTimestamps.add(timestamp)) {
            Log.w(TAG, "Duplicate send attempt for timestamp $timestamp, skipping")
            return SendResult(success = true)
        }

        // Issue #13: Set DataMessage timestamp and use as transaction ID
        val contentWithTimestamp = if (content.hasDataMessage() && content.dataMessage.timestamp == 0L) {
            val dm = content.dataMessage.toBuilder().setTimestamp(timestamp).build()
            content.toBuilder().setDataMessage(dm).build()
        } else content

        val contentWithProfileKey = if (contentWithTimestamp.hasDataMessage() && !contentWithTimestamp.dataMessage.hasProfileKey()) {
            val profileKey = recipientStore?.getRecipient(selfAci)?.profileKey
            if (profileKey != null) {
                val dm = contentWithTimestamp.dataMessage.toBuilder()
                    .setProfileKey(com.google.protobuf.ByteString.copyFrom(profileKey))
                    .build()
                contentWithTimestamp.toBuilder().setDataMessage(dm).build()
            } else contentWithTimestamp
        } else contentWithTimestamp

        val isTypingOrReceipt = contentWithProfileKey.hasTypingMessage() ||
            contentWithProfileKey.hasReceiptMessage()

        // Gate typing indicators on account settings (fix #20)
        if (contentWithProfileKey.hasTypingMessage() && accountRecord != null && !accountRecord.typingIndicators) {
            Log.d(TAG, "Not sending typing message as typing indicators are disabled")
            return SendResult(success = true)
        }

        // Gate read receipts on account settings (fix #20)
        if (contentWithProfileKey.hasReceiptMessage() &&
            contentWithProfileKey.receiptMessage.type == SignalServiceProtos.ReceiptMessage.Type.READ &&
            accountRecord != null && !accountRecord.readReceipts) {
            Log.d(TAG, "Not sending read receipt as read receipts are disabled")
            return SendResult(success = true)
        }

        // Attach PNI signature when needed
        val contentToSend = maybeAttachPniSignature(recipientAci, contentWithProfileKey, isTypingOrReceipt)

        val isDeliveryReceipt = contentToSend.hasReceiptMessage() &&
            contentToSend.receiptMessage.type == SignalServiceProtos.ReceiptMessage.Type.DELIVERY
        if (recipientAci == selfAci && !isDeliveryReceipt) {
            if (contentToSend.hasDataMessage() || contentToSend.hasEditMessage()) {
                sendSyncMessage(null, contentToSend, timestamp)
            }
            return SendResult(success = true)
        }

        addSendCache(recipientAci, null, timestamp, contentToSend)

        val paddedContent = padContent(contentToSend.toByteArray())
        return try {
            val sentUnidentified = sendToRecipient(recipientAci, paddedContent, timestamp, isUrgent(contentToSend))
            if (contentToSend.hasDataMessage() || contentToSend.hasEditMessage()) {
                sendSyncMessage(recipientAci, contentToSend, timestamp, unidentified = sentUnidentified)
            }
            SendResult(success = true, unidentified = sentUnidentified)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message to $recipientAci", e)
            SendResult(success = false, error = e.message)
        } finally {
            // Issue #14: Always remove from pending set
            pendingTimestamps.remove(timestamp)
        }
    }

    private suspend fun maybeAttachPniSignature(
        recipientAci: String,
        content: SignalServiceProtos.Content,
        isTypingOrReceipt: Boolean,
    ): SignalServiceProtos.Content {
        if (pniIdentityKeyPair == null || aciIdentityKeyPair == null || selfPni == null) return content
        if (isTypingOrReceipt) return content
        if (content.hasPniSignatureMessage()) return content

        val recipient = recipientStore?.getRecipient(recipientAci) ?: return content
        if (!recipient.needsPniSignature) return content

        Log.d(TAG, "Including PNI signature in message to $recipientAci")
        val sig = pniIdentityKeyPair.privateKey.calculateSignature(aciIdentityKeyPair.publicKey.serialize())
        val pniBytes = uuidToBytes(selfPni)

        recipientStore.storeRecipient(recipient.copy(needsPniSignature = false))

        return content.toBuilder()
            .setPniSignatureMessage(
                SignalServiceProtos.PniSignatureMessage.newBuilder()
                    .setPni(com.google.protobuf.ByteString.copyFrom(pniBytes))
                    .setSignature(com.google.protobuf.ByteString.copyFrom(sig))
                    .build()
            ).build()
    }

    suspend fun sendReadReceipt(recipientAci: String, timestamps: List<Long>) {
        try {
            val content = ContentBuilders.readReceipt(timestamps)
            sendMessage(recipientAci, content, System.currentTimeMillis())
            val syncContent = ContentBuilders.syncReadMessage(recipientAci, timestamps)
            val paddedSync = padContent(syncContent.toByteArray())
            val selfDevices = deviceManager.getDeviceIds(selfAci)
            for (deviceId in selfDevices) {
                if (deviceId == selfDeviceId) continue
                try {
                    deviceManager.ensureSession(selfAci, deviceId)
                    val address = SignalProtocolAddress(selfAci, deviceId)
                    val encrypted = encryptFor(address, paddedSync)
                    val messages = JSONArray().put(JSONObject().apply {
                        put("type", encrypted.first)
                        put("destinationDeviceId", deviceId)
                        put("destinationRegistrationId", encrypted.second)
                        put("content", encrypted.third)
                    })
                    val payload = JSONObject().apply {
                        put("timestamp", System.currentTimeMillis())
                        put("online", false)
                        put("urgent", false)
                        put("messages", messages)
                    }
                    ws.sendRequest(
                        "PUT",
                        "/v1/messages/$selfAci",
                        payload.toString().toByteArray(),
                        mapOf("Content-Type" to "application/json")
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send read receipt sync to device $deviceId", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send read receipt to $recipientAci", e)
        }
    }

    // Fix #1: Refactored to use SenderKeyManager for sender key distribution
    suspend fun sendGroupMessage(
        groupId: String,
        memberAcis: List<String>,
        content: SignalServiceProtos.Content,
        timestamp: Long,
    ): List<SendResult> {
        val decoratedContent = decorateWithGroupContext(groupId, content)

        // Step 1: Create sender key distribution message for this group
        val distributionMessage = senderKeyManager.createDistributionMessage(groupId)

        // Step 2: Distribute sender key to members who need it
        for (aci in memberAcis) {
            if (aci == selfAci) continue
            try {
                val deviceIds = deviceManager.getDeviceIds(aci)
                if (senderKeyManager.needsDistribution(groupId, aci, deviceIds)) {
                    val skdmContent = SignalServiceProtos.Content.newBuilder()
                        .setSenderKeyDistributionMessage(
                            com.google.protobuf.ByteString.copyFrom(distributionMessage.serialize())
                        ).build()
                    val paddedSkdm = padContent(skdmContent.toByteArray())
                    sendToRecipient(aci, paddedSkdm, timestamp, urgent = false)
                    senderKeyManager.markDistributed(groupId, aci, deviceIds)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to distribute sender key to $aci", e)
            }
        }

        // Step 3: Send the actual message to all members individually
        val paddedContent = padContent(decoratedContent.toByteArray())
        val results = memberAcis.map { aci ->
            if (aci == selfAci) return@map SendResult(success = true)
            addSendCache(aci, groupId, timestamp, decoratedContent)
            try {
                sendToRecipient(aci, paddedContent, timestamp, isUrgent(decoratedContent))
                SendResult(success = true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send group message to $aci", e)
                SendResult(success = false, error = e.message)
            }
        }
        if (decoratedContent.hasDataMessage() || decoratedContent.hasEditMessage()) {
            try {
                sendSyncMessage(null, decoratedContent, timestamp, groupId, memberAcis)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send group sync message", e)
            }
        }
        return results
    }

    private suspend fun decorateWithGroupContext(
        groupId: String,
        content: SignalServiceProtos.Content,
    ): SignalServiceProtos.Content {
        val group = groupStore?.getGroup(groupId) ?: return content
        val groupContext = SignalServiceProtos.GroupContextV2.newBuilder()
            .setMasterKey(com.google.protobuf.ByteString.copyFrom(group.masterKey))
            .setRevision(group.revision)
            .build()

        val builder = content.toBuilder()
        when {
            content.hasDataMessage() -> {
                builder.setDataMessage(content.dataMessage.toBuilder().setGroupV2(groupContext))
            }
            content.hasEditMessage() && content.editMessage.hasDataMessage() -> {
                val editDm = content.editMessage.dataMessage.toBuilder().setGroupV2(groupContext)
                builder.setEditMessage(content.editMessage.toBuilder().setDataMessage(editDm))
            }
            content.hasTypingMessage() -> {
                // Fix #10: Include GroupIdentifier in typing messages
                val groupIdBytes = Base64.decode(groupId, Base64.DEFAULT)
                builder.setTypingMessage(
                    content.typingMessage.toBuilder()
                        .setGroupId(com.google.protobuf.ByteString.copyFrom(groupIdBytes))
                )
            }
        }
        return builder.build()
    }

    private suspend fun sendToRecipient(
        recipientAci: String,
        paddedContent: ByteArray,
        timestamp: Long,
        urgent: Boolean = true,
        retryCount: Int = 0,
        useSealedSender: Boolean = true,
    ): Boolean {
        if (retryCount > 3) throw IllegalStateException("Too many retries sending to $recipientAci")

        val deviceIds = deviceManager.getDeviceIds(recipientAci)
        val messages = JSONArray()

        // Determine sealed sender eligibility: need unauthed WS, profile key, and not self
        val profileKey = recipientStore?.getRecipient(recipientAci)?.profileKey
        val useSealed = useSealedSender && unauthedWs != null && recipientAci != selfAci && profileKey != null

        for (deviceId in deviceIds) {
            deviceManager.ensureSession(recipientAci, deviceId)
            val address = SignalProtocolAddress(recipientAci, deviceId)
            val encrypted = encryptFor(address, paddedContent, sealedSender = useSealed)
            messages.put(JSONObject().apply {
                put("type", encrypted.first)
                put("destinationDeviceId", deviceId)
                put("destinationRegistrationId", encrypted.second)
                put("content", encrypted.third)
            })
        }

        val payload = JSONObject().apply {
            put("timestamp", timestamp)
            put("online", false)
            put("urgent", urgent)
            put("messages", messages)
        }

        var sentUnidentified = false
        val response = if (useSealed) {
            sentUnidentified = true
            val accessKey = deriveAccessKey(profileKey)
            unauthedWs!!.sendRequest(
                "PUT",
                "/v1/messages/$recipientAci",
                payload.toString().toByteArray(),
                mapOf(
                    "Content-Type" to "application/json",
                    "Unidentified-Access-Key" to Base64.encodeToString(accessKey, Base64.NO_WRAP),
                )
            )
        } else {
            ws.sendRequest(
                "PUT",
                "/v1/messages/$recipientAci",
                payload.toString().toByteArray(),
                mapOf("Content-Type" to "application/json")
            )
        }

        when (response.status) {
            200 -> return sentUnidentified
            409, 410 -> {
                Log.w(TAG, "Device mismatch (${response.status}) for $recipientAci")
                handleDeviceMismatch(recipientAci, response.body.toByteArray())
                return sendToRecipient(recipientAci, paddedContent, timestamp, urgent, retryCount + 1, useSealedSender)
            }
            428 -> {
                val retryAfter = try {
                    val body = String(response.body.toByteArray())
                    val json = JSONObject(body)
                    json.optLong("retry_after", 0)
                } catch (_: Exception) { 0L }
                Log.w(TAG, "Rate limited (428) for $recipientAci, retry after ${retryAfter}s")
                throw IllegalStateException("Got 428 rate limit error, retry after ${retryAfter}s")
            }
            401 -> {
                if (useSealedSender) {
                    Log.w(TAG, "Unauthorized (401) for $recipientAci, retrying without sealed sender")
                    return sendToRecipient(recipientAci, paddedContent, timestamp, urgent, retryCount + 1, useSealedSender = false)
                } else {
                    throw IllegalStateException("Send failed with status 401")
                }
            }
            404 -> {
                Log.w(TAG, "Recipient not found (404): $recipientAci, removing all sessions")
                val subDevices = sessionStore.getSubDeviceSessions(recipientAci)
                sessionStore.deleteSession(SignalProtocolAddress(recipientAci, 1))
                for (devId in subDevices) {
                    sessionStore.deleteSession(SignalProtocolAddress(recipientAci, devId))
                }
                recipientStore?.markUnregistered(recipientAci, true)
                throw RecipientUnregisteredException(recipientAci)
            }
            500, 503 -> {
                Log.w(TAG, "Server error (${response.status}) for $recipientAci, retrying")
                return sendToRecipient(recipientAci, paddedContent, timestamp, urgent, retryCount + 1, useSealedSender)
            }
            else -> throw IllegalStateException("Send failed with status ${response.status}")
        }
    }

    private suspend fun encryptFor(
        address: SignalProtocolAddress,
        paddedContent: ByteArray,
        sealedSender: Boolean = false,
    ): Triple<Int, Int, String> {
        // Serialize ratchet/session mutation with the decrypt path (same lock
        // instance is shared with SignalClient); libsignal sessions are not
        // safe under concurrent encrypt/decrypt for the same recipient.
        val lock = encryptionLock
        return if (lock != null) lock.withLock { encryptForLocked(address, paddedContent, sealedSender) }
        else encryptForLocked(address, paddedContent, sealedSender)
    }

    private suspend fun encryptForLocked(
        address: SignalProtocolAddress,
        paddedContent: ByteArray,
        sealedSender: Boolean,
    ): Triple<Int, Int, String> {
        val regId = protocolStore.loadSession(address).remoteRegistrationId

        if (sealedSender) {
            val cert = fetchSenderCertificate()
            val sealedCipher = SealedSessionCipher(
                protocolStore, UUID.fromString(selfAci), selfAci, selfDeviceId
            )
            val sealedPayload = sealedCipher.encrypt(address, cert, paddedContent)
            return Triple(6, regId, Base64.encodeToString(sealedPayload, Base64.NO_WRAP))
        }

        val cipher = SessionCipher(protocolStore, address)
        val ciphertext = cipher.encrypt(paddedContent)

        val type = when (ciphertext.type) {
            CiphertextMessage.PREKEY_TYPE -> 3
            CiphertextMessage.WHISPER_TYPE -> 1
            CiphertextMessage.PLAINTEXT_CONTENT_TYPE -> 8
            else -> 0
        }
        return Triple(type, regId, Base64.encodeToString(ciphertext.serialize(), Base64.NO_WRAP))
    }

    suspend fun sendSyncMessage(
        recipientAci: String?,
        content: SignalServiceProtos.Content,
        timestamp: Long,
        groupId: String? = null,
        memberAcis: List<String>? = null,
        unidentified: Boolean = false,
    ) {
        val sentBuilder = SignalServiceProtos.SyncMessage.Sent.newBuilder()
            .setTimestamp(timestamp)
            .setExpirationStartTimestamp(System.currentTimeMillis())

        if (content.hasDataMessage()) {
            sentBuilder.setMessage(content.dataMessage)
        }

        if (content.hasEditMessage()) {
            sentBuilder.setEditMessage(content.editMessage)
        }

        if (recipientAci != null) {
            sentBuilder.setDestinationServiceId(recipientAci)
            sentBuilder.setDestinationServiceIdBinary(
                com.google.protobuf.ByteString.copyFrom(uuidToBytes(recipientAci))
            )
            sentBuilder.addUnidentifiedStatus(
                SignalServiceProtos.SyncMessage.Sent.UnidentifiedDeliveryStatus.newBuilder()
                    .setDestinationServiceId(recipientAci)
                    .setDestinationServiceIdBinary(
                        com.google.protobuf.ByteString.copyFrom(uuidToBytes(recipientAci))
                    )
                    .setUnidentified(unidentified)
                    .build()
            )
        }

        val rng = java.security.SecureRandom()
        val syncPadding = ByteArray(rng.nextInt(511) + 1)
        rng.nextBytes(syncPadding)

        val syncContent = SignalServiceProtos.Content.newBuilder()
            .setSyncMessage(
                SignalServiceProtos.SyncMessage.newBuilder()
                    .setSent(sentBuilder.build())
                    .setPadding(com.google.protobuf.ByteString.copyFrom(syncPadding))
                    .build()
            ).build()

        val paddedSync = padContent(syncContent.toByteArray())
        val selfDevices = deviceManager.getDeviceIds(selfAci)
        for (deviceId in selfDevices) {
            if (deviceId == selfDeviceId) continue
            try {
                deviceManager.ensureSession(selfAci, deviceId)
                val address = SignalProtocolAddress(selfAci, deviceId)
                val encrypted = encryptFor(address, paddedSync)
                val messages = JSONArray().put(JSONObject().apply {
                    put("type", encrypted.first)
                    put("destinationDeviceId", deviceId)
                    put("destinationRegistrationId", encrypted.second)
                    put("content", encrypted.third)
                })
                val payload = JSONObject().apply {
                    put("timestamp", timestamp)
                    put("online", false)
                    put("urgent", isSyncMessageUrgent(syncContent))
                    put("messages", messages)
                }
                ws.sendRequest(
                    "PUT",
                    "/v1/messages/$selfAci",
                    payload.toString().toByteArray(),
                    mapOf("Content-Type" to "application/json")
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send sync to device $deviceId", e)
            }
        }
    }

    // Send a raw sync message to self (for DeleteForMe, MessageRequestResponse, etc.)
    suspend fun sendSelfSyncMessage(content: SignalServiceProtos.Content, timestamp: Long) {
        val paddedContent = padContent(content.toByteArray())
        val selfDevices = deviceManager.getDeviceIds(selfAci)
        for (deviceId in selfDevices) {
            if (deviceId == selfDeviceId) continue
            try {
                deviceManager.ensureSession(selfAci, deviceId)
                val address = SignalProtocolAddress(selfAci, deviceId)
                val encrypted = encryptFor(address, paddedContent)
                val messages = JSONArray().put(JSONObject().apply {
                    put("type", encrypted.first)
                    put("destinationDeviceId", deviceId)
                    put("destinationRegistrationId", encrypted.second)
                    put("content", encrypted.third)
                })
                val payload = JSONObject().apply {
                    put("timestamp", timestamp)
                    put("online", false)
                    put("urgent", false)
                    put("messages", messages)
                }
                ws.sendRequest(
                    "PUT",
                    "/v1/messages/$selfAci",
                    payload.toString().toByteArray(),
                    mapOf("Content-Type" to "application/json")
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send self sync to device $deviceId", e)
            }
        }
    }

    private suspend fun handleDeviceMismatch(recipientAci: String, responseBody: ByteArray?) {
        if (responseBody == null) {
            deviceManager.refreshDevices(recipientAci)
            return
        }
        try {
            val json = JSONObject(String(responseBody))
            val staleDevices = json.optJSONArray("staleDevices")
            val missingDevices = json.optJSONArray("missingDevices")
            val extraDevices = json.optJSONArray("extraDevices")
            if (staleDevices != null) {
                for (i in 0 until staleDevices.length()) {
                    val deviceId = staleDevices.getInt(i)
                    val address = SignalProtocolAddress(recipientAci, deviceId)
                    sessionStore.deleteSession(address)
                    deviceManager.ensureSession(recipientAci, deviceId)
                }
            }
            if (missingDevices != null) {
                for (i in 0 until missingDevices.length()) {
                    deviceManager.ensureSession(recipientAci, missingDevices.getInt(i))
                }
            }
            if (extraDevices != null) {
                for (i in 0 until extraDevices.length()) {
                    val address = SignalProtocolAddress(recipientAci, extraDevices.getInt(i))
                    sessionStore.deleteSession(address)
                }
            }
            deviceManager.refreshDevices(recipientAci)
        } catch (e: Exception) {
            Log.w(TAG, "Error handling device mismatch response", e)
            deviceManager.refreshDevices(recipientAci)
        }
    }

    private fun deriveAccessKey(profileKey: ByteArray): ByteArray {
        // Signal's unidentified-access-key derivation: AES-GCM-encrypt 16 zero
        // bytes under the profile key with a 12-byte zero nonce, then take the
        // first 16 bytes of the ciphertext. Must match
        // UnidentifiedAccess.deriveAccessKeyFrom — an HMAC here yields a key the
        // server rejects (401), so every sealed-sender send needlessly falls back
        // to an authed retry (and sealed sender never actually works).
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(profileKey, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, ByteArray(12)),
        )
        val ciphertext = cipher.doFinal(ByteArray(16))
        return ciphertext.copyOfRange(0, 16)
    }

    suspend fun sendDeliveryReceipt(recipientAci: String, timestamps: List<Long>) {
        try {
            val content = ContentBuilders.deliveryReceipt(timestamps)
            sendMessage(recipientAci, content, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send delivery receipt to $recipientAci", e)
        }
    }

    companion object {
        private const val TAG = "SignalSender"

        fun padContent(content: ByteArray): ByteArray {
            val messageLengthWithTerminator = content.size + 1
            val messagePartCount = (messageLengthWithTerminator + 159) / 160
            val paddedLength = messagePartCount * 160
            val padded = ByteArray(paddedLength)
            content.copyInto(padded)
            padded[content.size] = 0x80.toByte()
            return padded
        }

        private fun isUrgent(content: SignalServiceProtos.Content): Boolean {
            return when {
                content.hasDataMessage() -> true
                content.hasEditMessage() -> true
                content.hasCallMessage() -> true
                content.hasStoryMessage() -> true
                content.hasSyncMessage() -> isSyncMessageUrgent(content)
                else -> false
            }
        }

        private fun isSyncMessageUrgent(content: SignalServiceProtos.Content): Boolean {
            if (!content.hasSyncMessage()) return false
            val sync = content.syncMessage
            return sync.hasSent() || sync.hasRequest()
        }

        fun uuidToBytes(uuid: String): ByteArray {
            val parsed = UUID.fromString(uuid)
            val buf = java.nio.ByteBuffer.allocate(16)
            buf.putLong(parsed.mostSignificantBits)
            buf.putLong(parsed.leastSignificantBits)
            return buf.array()
        }
    }
}
    data class SendResult(val success: Boolean, val error: String? = null, val unidentified: Boolean = false)
