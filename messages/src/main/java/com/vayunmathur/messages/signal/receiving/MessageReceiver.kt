@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.messages.signal.receiving

import kotlin.uuid.Uuid
import android.util.Log
import com.vayunmathur.messages.signal.proto.WebSocketProtos
import com.vayunmathur.messages.signal.proto.SignalServiceProtos
import com.vayunmathur.messages.signal.sending.MessageSender
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.groups.state.SenderKeyStore
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import org.signal.libsignal.protocol.message.DecryptionErrorMessage
import org.signal.libsignal.protocol.message.CiphertextMessage
import kotlinx.coroutines.runBlocking
import org.signal.libsignal.protocol.SignalProtocolAddress
import java.util.concurrent.ConcurrentHashMap

class MessageReceiver(
    private val sessionStore: SessionStore,
    private val identityKeyStore: IdentityKeyStore,
    private val preKeyStore: PreKeyStore,
    private val signedPreKeyStore: SignedPreKeyStore,
    private val kyberPreKeyStore: KyberPreKeyStore,
    private val senderKeyStore: SenderKeyStore,
    private val selfAci: String,
    private val deviceId: Int,
    private val onDecrypted: (DecryptedMessage) -> Unit,
    private val recipientStore: com.vayunmathur.messages.signal.store.SignalRecipientStore? = null,
    private val messageSender: MessageSender? = null,
    private val sendWsResponse: ((Long, Int, String?) -> Unit)? = null,
    var onDecryptionError: ((senderAci: String, senderDeviceId: Int, timestamp: Long, errorMessage: String?) -> Unit)? = null,
) {
    private data class SendCacheKey(
        val recipientAci: String,
        val timestamp: Long,
    )

    private val sendCache = ConcurrentHashMap<SendCacheKey, SignalServiceProtos.Content>()
    private val maxCacheAge = 30L * 24 * 60 * 60 * 1000L // 30 days

    fun cacheSentMessage(recipientAci: String, timestamp: Long, content: SignalServiceProtos.Content) {
        sendCache[SendCacheKey(recipientAci, timestamp)] = content
    }
    fun handleRequest(request: WebSocketProtos.WebSocketRequestMessage) {
        if (request.verb == "PUT" && request.path == "/api/v1/queue/empty") {
            Log.d(TAG, "Received queue empty notice")
            sendWsResponse?.invoke(request.id, 200, "OK")
            return
        }
        if (request.verb != "PUT" || request.path != "/api/v1/message") {
            Log.w(TAG, "Unknown websocket request: ${request.verb} ${request.path}")
            sendWsResponse?.invoke(request.id, 200, "OK")
            return
        }

        val envelope = try {
            SignalServiceProtos.Envelope.parseFrom(request.body)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse envelope", e)
            return
        }

        val result = EnvelopeDecryptor.decrypt(
            envelope = envelope,
            sessionStore = sessionStore,
            identityKeyStore = identityKeyStore,
            preKeyStore = preKeyStore,
            signedPreKeyStore = signedPreKeyStore,
            kyberPreKeyStore = kyberPreKeyStore,
            senderKeyStore = senderKeyStore,
            certificateValidator = null,
            selfAci = selfAci,
            selfDeviceId = deviceId,
        )

        if (result.error != null) {
            Log.e(TAG, "Decryption failed from ${result.senderAci}:${result.senderDeviceId}", result.error)
            if (result.retriable && messageSender != null) {
                Log.d(TAG, "Decryption error is retriable for ${result.senderAci}, sending retry request")
                sendRetryRequest(result, envelope.clientTimestamp)
            }
            onDecryptionError?.invoke(result.senderAci, result.senderDeviceId, envelope.clientTimestamp, result.error.message)
            sendWsResponse?.invoke(request.id, 200, "OK")
            return
        }

        val content = result.content

        // Validate sender ACI type: drop messages from non-ACI senders
        if (result.senderAci.isEmpty() || !isValidAciUuid(result.senderAci)) {
            Log.w(TAG, "Dropping message from non-ACI sender: ${result.senderAci}")
            sendWsResponse?.invoke(request.id, 200, "OK")
            return
        }

        if (content != null && content.hasDecryptionErrorMessage()) {
            Log.d(TAG, "Received decryption error message from ${result.senderAci}:${result.senderDeviceId}")
            handleRetryRequest(result, content.decryptionErrorMessage.toByteArray())
            sendWsResponse?.invoke(request.id, 200, "OK")
            return
        }

        if (result.unencrypted && content != null && !content.hasDecryptionErrorMessage()) {
            Log.w(TAG, "Unexpected non-decryption-error content in unencrypted message")
            sendWsResponse?.invoke(request.id, 200, "OK")
            return
        }

        if (content != null && content.hasSenderKeyDistributionMessage()) {
            val skdmBytes = content.senderKeyDistributionMessage.toByteArray()
            val skdm = SenderKeyDistributionMessage(skdmBytes)
            val senderAddress = SignalProtocolAddress(result.senderAci, result.senderDeviceId)
            val groupBuilder = GroupSessionBuilder(senderKeyStore)
            groupBuilder.process(senderAddress, skdm)
            Log.d(TAG, "Processed SKDM from ${result.senderAci}:${result.senderDeviceId}")
        }

        if (content != null && content.hasDataMessage() && content.dataMessage.profileKey.size() == 32) {
            try {
                runBlocking {
                    recipientStore?.storeProfileKey(
                        result.senderAci,
                        content.dataMessage.profileKey.toByteArray()
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to store profile key from ${result.senderAci}", e)
            }
        }

        if (content == null) {
            Log.d(TAG, "No content from ${result.senderAci}")
            sendWsResponse?.invoke(request.id, 200, "OK")
            return
        }

        val isBlocked = try {
            runBlocking { recipientStore?.isBlocked(result.senderAci) ?: false }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check if ${result.senderAci} is blocked", e)
            false
        }

        val message = ContentDispatcher.dispatch(
            senderAci = result.senderAci,
            senderDeviceId = result.senderDeviceId,
            content = content,
            timestamp = result.timestamp,
            serverTimestamp = result.serverTimestamp,
            selfAci = selfAci,
        )

        if (message == null) {
            sendWsResponse?.invoke(request.id, 200, "OK")
            return
        }

        if (message.content is MessageContent.DeliveryReceipt && result.senderAci == selfAci) {
            sendWsResponse?.invoke(request.id, 200, "OK")
            return
        }

        if (isBlocked) {
            when (message.content) {
                is MessageContent.TextMessage,
                is MessageContent.Reaction,
                is MessageContent.Delete,
                is MessageContent.Edit,
                is MessageContent.Attachment,
                is MessageContent.Sticker -> {
                    if (message.content.let { c ->
                        (c is MessageContent.TextMessage && c.groupId == null) ||
                        (c is MessageContent.Reaction && c.groupId == null) ||
                        (c is MessageContent.Delete && c.groupId == null) ||
                        (c is MessageContent.Edit && c.groupId == null) ||
                        (c is MessageContent.Attachment && c.groupId == null) ||
                        (c is MessageContent.Sticker && c.groupId == null)
                    }) {
                        Log.d(TAG, "Dropping direct message from blocked user ${result.senderAci}")
                        sendWsResponse?.invoke(request.id, 200, "OK")
                        return
                    }
                }
                is MessageContent.Typing -> {
                    if (message.content.groupId == null) {
                        sendWsResponse?.invoke(request.id, 200, "OK")
                        return
                    }
                }
                is MessageContent.Call -> {
                    Log.d(TAG, "Dropping call from blocked user ${result.senderAci}")
                    sendWsResponse?.invoke(request.id, 200, "OK")
                    return
                }
                else -> {}
            }
        }

        onDecrypted(message)
        sendWsResponse?.invoke(request.id, 200, "OK")

        val shouldSendDeliveryReceipt = when (message.content) {
            is MessageContent.TextMessage,
            is MessageContent.Reaction,
            is MessageContent.Delete,
            is MessageContent.Attachment,
            is MessageContent.Sticker,
            is MessageContent.Edit,
            is MessageContent.GroupCallUpdate -> true
            else -> false
        }
        if (shouldSendDeliveryReceipt && messageSender != null) {
            try {
                val deliveryTs = when {
                    content.hasDataMessage() -> content.dataMessage.timestamp
                    content.hasEditMessage() && content.editMessage.hasDataMessage() -> content.editMessage.dataMessage.timestamp
                    else -> result.timestamp
                }
                runBlocking {
                    messageSender.sendDeliveryReceipt(result.senderAci, listOf(deliveryTs))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send delivery receipt to ${result.senderAci}", e)
            }
        }
    }

    companion object {
        private const val TAG = "SignalReceiver"
    }

    private fun isValidAciUuid(aci: String): Boolean {
        return try {
            Uuid.parse(aci)
            !aci.startsWith("PNI:")
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun sendRetryRequest(result: EnvelopeDecryptor.DecryptionResult, originalTimestamp: Long) {
        try {
            val ciphertext = result.ciphertext ?: return
            val dem = DecryptionErrorMessage.forOriginalMessage(
                ciphertext, result.ciphertextType, originalTimestamp, result.senderDeviceId
            )
            val demBytes = dem.serialize()
            val content = SignalServiceProtos.Content.newBuilder()
                .setDecryptionErrorMessage(com.google.protobuf.ByteString.copyFrom(demBytes))
                .build()
            runBlocking {
                messageSender?.sendMessage(result.senderAci, content, System.currentTimeMillis())
            }
            Log.d(TAG, "Sent retry receipt to ${result.senderAci}:${result.senderDeviceId}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send retry request to ${result.senderAci}", e)
        }
    }

    private fun handleRetryRequest(
        result: EnvelopeDecryptor.DecryptionResult,
        demBytes: ByteArray,
    ) {
        try {
            val dem = SignalServiceProtos.DecryptionErrorMessage.parseFrom(demBytes)
            val destDeviceId = dem.deviceId
            if (destDeviceId != deviceId) {
                Log.d(TAG, "Ignoring decryption error message for another device: $destDeviceId")
                return
            }
            val requestedTimestamp = dem.timestamp
            val age = System.currentTimeMillis() - requestedTimestamp
            var cachedContent: SignalServiceProtos.Content? = null
            if (age < maxCacheAge) {
                cachedContent = sendCache[SendCacheKey(result.senderAci, requestedTimestamp)]
            }

            if (dem.hasRatchetKey()) {
                val senderAddress = SignalProtocolAddress(result.senderAci, result.senderDeviceId)
                val session = sessionStore.loadSession(senderAddress)
                if (session != null) {
                    session.archiveCurrentState()
                    sessionStore.storeSession(senderAddress, session)
                    Log.d(TAG, "Archived session state for ${result.senderAci}:${result.senderDeviceId}")
                }
            }

            val retryContent = cachedContent ?: run {
                if (!dem.hasRatchetKey()) {
                    Log.d(TAG, "No cached message and no ratchet key, not responding to retry")
                    return
                }
                val rng = java.security.SecureRandom()
                val padding = ByteArray(rng.nextInt(511) + 1)
                rng.nextBytes(padding)
                SignalServiceProtos.Content.newBuilder()
                    .setNullMessage(
                        SignalServiceProtos.NullMessage.newBuilder()
                            .setPadding(com.google.protobuf.ByteString.copyFrom(padding))
                            .build()
                    ).build()
            }

            val responseTimestamp = if (cachedContent != null) requestedTimestamp else System.currentTimeMillis().toULong().toLong()
            Log.d(TAG, "Responding to decryption error message from ${result.senderAci}:${result.senderDeviceId}, " +
                "cached=${cachedContent != null}, ts=$requestedTimestamp")
            runBlocking {
                messageSender?.sendMessage(result.senderAci, retryContent, responseTimestamp)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle retry request from ${result.senderAci}", e)
        }
    }
}
