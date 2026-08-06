package com.vayunmathur.messages.signal.receiving

import android.util.Base64
import android.util.Log
import com.vayunmathur.messages.signal.proto.SignalServiceProtos
import com.vayunmathur.messages.signal.store.SignalProtocolStoreImpl
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PlaintextContent
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.metadata.protocol.UnidentifiedSenderMessageContent
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import org.signal.libsignal.protocol.groups.state.SenderKeyStore
import org.signal.libsignal.metadata.certificate.CertificateValidator

object EnvelopeDecryptor {

    private const val TAG = "SignalReceiver"
    private const val CONTENT_HINT_RESENDABLE = 1
    private val SIGNAL_SERVER_TRUST_ROOT = Base64.decode(
        "BXu4Sr3OzuDoeeFab3yc3LaWGPsyVDKzp12qlMQDa2B1hQ==", Base64.DEFAULT
    )

    data class DecryptionResult(
        val senderAci: String,
        val senderDeviceId: Int,
        val content: SignalServiceProtos.Content?,
        val timestamp: Long,
        val serverTimestamp: Long,
        val error: Throwable? = null,
        val senderE164: String? = null,
        val unidentified: Boolean = false,
        val retriable: Boolean = false,
        val unencrypted: Boolean = false,
        val contentHint: Int = 0,
        val groupId: ByteArray? = null,
        val ciphertext: ByteArray? = null,
        val ciphertextType: Int = 0,
    )

    fun decrypt(
        envelope: SignalServiceProtos.Envelope,
        sessionStore: SessionStore,
        identityKeyStore: IdentityKeyStore,
        preKeyStore: PreKeyStore,
        signedPreKeyStore: SignedPreKeyStore,
        kyberPreKeyStore: KyberPreKeyStore,
        senderKeyStore: SenderKeyStore,
        certificateValidator: CertificateValidator?,
        selfAci: String,
        selfDeviceId: Int,
        selfPni: String = "",
        pniPreKeyStore: PreKeyStore? = null,
        pniSignedPreKeyStore: SignedPreKeyStore? = null,
        pniKyberPreKeyStore: KyberPreKeyStore? = null,
    ): DecryptionResult {
        val senderAci = envelope.sourceServiceId ?: ""
        val senderDeviceId = envelope.sourceDeviceId
        val timestamp = envelope.clientTimestamp
        val serverTimestamp = envelope.serverTimestamp
        val destinationServiceId = envelope.destinationServiceId ?: ""

        if (destinationServiceId.isEmpty()) {
            return DecryptionResult(senderAci, senderDeviceId, null, timestamp, serverTimestamp,
                error = IllegalArgumentException("Envelope missing destination service ID"))
        }

        // Pick the pre-key store namespace matching the envelope's destination identity. PNI-addressed
        // pre-key messages reference the PNI signed/kyber pre-key ids, which live in the PNI-scoped
        // store; everything else uses the ACI store. Falls back to ACI when no PNI store is supplied.
        val isPniDestination = selfPni.isNotEmpty() && pniPreKeyStore != null &&
            (destinationServiceId == "PNI:$selfPni" || destinationServiceId == selfPni)
        val effectivePreKeyStore = if (isPniDestination) pniPreKeyStore else preKeyStore
        val effectiveSignedPreKeyStore = if (isPniDestination) pniSignedPreKeyStore!! else signedPreKeyStore
        val effectiveKyberPreKeyStore = if (isPniDestination) pniKyberPreKeyStore!! else kyberPreKeyStore

        val protocolStore = SignalProtocolStoreImpl(
            sessionStore, identityKeyStore, effectivePreKeyStore, effectiveSignedPreKeyStore, effectiveKyberPreKeyStore, senderKeyStore
        )

        return try {
            when (envelope.type) {
                SignalServiceProtos.Envelope.Type.DOUBLE_RATCHET -> {
                    val address = SignalProtocolAddress(senderAci, senderDeviceId)
                    val cipher = SessionCipher(protocolStore, address)
                    try {
                        val plaintext = cipher.decrypt(SignalMessage(envelope.content.toByteArray()))
                        val content = SignalServiceProtos.Content.parseFrom(stripPadding(plaintext))
                        DecryptionResult(senderAci, senderDeviceId, content, timestamp, serverTimestamp)
                    } catch (e: Exception) {
                        DecryptionResult(senderAci, senderDeviceId, null, timestamp, serverTimestamp,
                            error = e, retriable = true,
                            ciphertext = envelope.content.toByteArray(),
                            ciphertextType = CiphertextMessage.WHISPER_TYPE)
                    }
                }

                SignalServiceProtos.Envelope.Type.PREKEY_MESSAGE -> {
                    val address = SignalProtocolAddress(senderAci, senderDeviceId)
                    val cipher = SessionCipher(protocolStore, address)
                    try {
                        val plaintext = cipher.decrypt(PreKeySignalMessage(envelope.content.toByteArray()))
                        val content = SignalServiceProtos.Content.parseFrom(stripPadding(plaintext))
                        DecryptionResult(senderAci, senderDeviceId, content, timestamp, serverTimestamp)
                    } catch (e: Exception) {
                        DecryptionResult(senderAci, senderDeviceId, null, timestamp, serverTimestamp,
                            error = e, retriable = true,
                            ciphertext = envelope.content.toByteArray(),
                            ciphertextType = CiphertextMessage.PREKEY_TYPE)
                    }
                }

                SignalServiceProtos.Envelope.Type.UNIDENTIFIED_SENDER -> {
                    if (destinationServiceId != selfAci) {
                        Log.w(TAG, "Received UNIDENTIFIED_SENDER envelope for non-ACI destination: $destinationServiceId")
                        return DecryptionResult(senderAci, senderDeviceId, null, timestamp, serverTimestamp,
                            error = IllegalArgumentException("Received unidentified sender envelope for non-ACI destination"))
                    }
                    val trustRoot = ECPublicKey(SIGNAL_SERVER_TRUST_ROOT)
                    val validator = certificateValidator
                        ?: CertificateValidator(listOf(trustRoot))

                    // Unwrap the sealed-sender outer layer into a UnidentifiedSenderMessageContent
                    // so we can read the real contentHint and inner ciphertext type, then dispatch
                    // decryption ourselves. SealedSessionCipher.decrypt() hides both of these; the
                    // decryptToUsmc primitive it uses internally is reachable via the public Native
                    // binding in libsignal 0.86.5 (SealedSessionCipher_DecryptToUsmc).
                    val usmc = UnidentifiedSenderMessageContent(
                        SealedSenderUnwrap.decryptToUsmc(
                            envelope.content.toByteArray(), protocolStore
                        )
                    )
                    val cert = usmc.senderCertificate
                    validator.validate(cert, serverTimestamp)

                    val realSenderAci = cert.senderUuid
                    val realSenderDevice = cert.senderDeviceId
                    val contentHint = usmc.contentHint
                    val unsealedGroupId = usmc.groupId.orElse(null)
                    val innerType = usmc.type
                    val senderAddress = SignalProtocolAddress(realSenderAci, realSenderDevice)

                    val plaintext = try {
                        when (innerType) {
                            // Group (sender-key) message: route to GroupCipher, mirroring
                            // SenderKeyManager.decryptFromGroup but using the shared protocol store.
                            CiphertextMessage.SENDERKEY_TYPE ->
                                GroupCipher(protocolStore, senderAddress).decrypt(usmc.content)
                            CiphertextMessage.WHISPER_TYPE ->
                                SessionCipher(protocolStore, senderAddress)
                                    .decrypt(SignalMessage(usmc.content))
                            CiphertextMessage.PREKEY_TYPE ->
                                SessionCipher(protocolStore, senderAddress)
                                    .decrypt(PreKeySignalMessage(usmc.content))
                            CiphertextMessage.PLAINTEXT_CONTENT_TYPE ->
                                PlaintextContent(usmc.content).body
                            else -> throw IllegalArgumentException(
                                "Unknown sealed-sender inner type: $innerType"
                            )
                        }
                    } catch (e: Exception) {
                        // Inner decryption failed (e.g. missing sender key / session). Surface the
                        // real inner type + contentHint so the caller can request the right retry
                        // (sender-key distribution for groups, session reset for 1:1).
                        return DecryptionResult(
                            senderAci = realSenderAci,
                            senderDeviceId = realSenderDevice,
                            content = null,
                            timestamp = timestamp,
                            serverTimestamp = serverTimestamp,
                            error = e,
                            senderE164 = cert.senderE164.orElse(null),
                            unidentified = true,
                            retriable = true,
                            contentHint = contentHint,
                            groupId = unsealedGroupId,
                            ciphertext = usmc.content,
                            ciphertextType = innerType,
                        )
                    }
                    val content = SignalServiceProtos.Content.parseFrom(stripPadding(plaintext))
                    DecryptionResult(
                        senderAci = realSenderAci,
                        senderDeviceId = realSenderDevice,
                        content = content,
                        timestamp = timestamp,
                        serverTimestamp = serverTimestamp,
                        senderE164 = cert.senderE164.orElse(null),
                        unidentified = true,
                        contentHint = contentHint,
                        groupId = unsealedGroupId,
                    )
                }

                SignalServiceProtos.Envelope.Type.PLAINTEXT_CONTENT -> {
                    val plaintext = stripPadding(envelope.content.toByteArray())
                    val content = SignalServiceProtos.Content.newBuilder()
                        .setDecryptionErrorMessage(com.google.protobuf.ByteString.copyFrom(plaintext))
                        .build()
                    DecryptionResult(senderAci, senderDeviceId, content, timestamp, serverTimestamp,
                        unencrypted = true)
                }

                SignalServiceProtos.Envelope.Type.SERVER_DELIVERY_RECEIPT -> {
                    DecryptionResult(senderAci, senderDeviceId, null, timestamp, serverTimestamp,
                        error = IllegalArgumentException("Server delivery receipt envelopes are not yet supported"))
                }

                else -> {
                    Log.w(TAG, "Unknown envelope type: ${envelope.type}")
                    DecryptionResult(senderAci, senderDeviceId, null, timestamp, serverTimestamp,
                        error = IllegalArgumentException("Unknown envelope type: ${envelope.type}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decryption error for type ${envelope.type}", e)
            val resolvedSender = senderAci
            val resolvedDeviceId = senderDeviceId
            if (envelope.type == SignalServiceProtos.Envelope.Type.UNIDENTIFIED_SENDER) {
                return DecryptionResult(
                    senderAci = resolvedSender,
                    senderDeviceId = resolvedDeviceId,
                    content = null, timestamp = timestamp, serverTimestamp = serverTimestamp,
                    error = e,
                    // Sealed-sender failures must be retriable so a retry-receipt /
                    // session reset is requested; contentHint is unknown here since
                    // decryption failed before the inner message was recovered.
                    retriable = true,
                    ciphertext = envelope.content.toByteArray(),
                    ciphertextType = CiphertextMessage.SENDERKEY_TYPE,
                )
            }
            DecryptionResult(resolvedSender, resolvedDeviceId, null, timestamp, serverTimestamp, error = e)
        }
    }

    private fun stripPadding(padded: ByteArray): ByteArray {
        var i = padded.size - 1
        while (i >= 0 && padded[i] == 0.toByte()) i--
        if (i < 0) throw IllegalArgumentException("invalid ISO7816 padding (length ${padded.size})")
        if (padded[i] == 0x80.toByte()) return padded.copyOfRange(0, i)
        throw IllegalArgumentException("invalid ISO7816 padding")
    }
}
