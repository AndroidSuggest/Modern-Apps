package com.vayunmathur.communicate.data.signal

import android.util.Base64 as AndroidBase64
import android.util.Log
import com.vayunmathur.communicate.data.signal.transport.SignalPayload
import org.signal.libsignal.protocol.message.DecryptionErrorMessage
import org.signal.libsignal.protocol.message.PlaintextContent
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketMessage

/**
 * SignalClient inbound envelope pipeline (split from SignalClient.kt for file length).
 *
 * Extension functions on [SignalClient]; behavior identical, call sites unchanged.
 */

// -- Inbound ----

internal suspend fun SignalClient.handleInboundFrame(raw: ByteArray) {
    val wsMessage = SignalProtocol.parseWebSocketMessage(raw)
    if (wsMessage == null) {
        Log.w(TAG, "unparseable ws frame len=${raw.size}")
        return
    }
    if (wsMessage.type != WebSocketMessage.Type.REQUEST || !wsMessage.hasRequest()) return
    val req = wsMessage.request
    val ackId = if (req.hasId()) req.id else null

    // Control frames carry nothing to persist, so they can be acked straight away.
    val isControlFrame = SignalProtocol.isQueueEmptySignal(raw) ||
        (req.hasPath() && req.path.contains("keepalive")) ||
        !req.hasBody()
    if (isControlFrame) {
        ackEnvelope(ackId)
        return
    }

    val handled = try {
        processEnvelope(wsMessage)
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
    } catch (t: Throwable) {
        // Redelivery cannot fix a deterministic failure, and there is no attempt counter, so
        // acking is the lesser evil: not acking would spin on the same envelope forever.
        Log.e(TAG, "failed to process envelope, acking anyway to avoid a redelivery loop", t)
        true
    }
    // An ack deletes the message from the server's queue, so it is deferred until the envelope has
    // been decrypted, validated and handed to the event pipeline. Note this is hand-off, not a
    // durable write: SignalEventProcessor persists asynchronously and swallows its own failures.
    if (handled) {
        ackEnvelope(ackId)
    } else {
        Log.w(TAG, "not acking envelope; leaving it queued for redelivery")
    }
}

private suspend fun SignalClient.ackEnvelope(requestId: Long?) {
    if (requestId == null) return
    val ack = SignalProtocol.buildWsResponseProto(requestId, 200)
    try { socket?.send(SignalProtocol.encodeWebSocketResponse(ack)) } catch (_: Exception) {}
}

/**
 * Returns whether the envelope may be acked. False means "leave it on the server queue", which is
 * only appropriate when we could not even attempt to handle it — a malformed or undecryptable
 * message returns true, since redelivering it would only spin.
 */
private suspend fun SignalClient.processEnvelope(wsMessage: WebSocketMessage): Boolean {
    val envelopeProto = SignalProtocol.parseEnvelopeFromWsMessage(wsMessage) ?: return true
    val env = SignalProtocol.toSignalEnvelope(envelopeProto)

    // Server delivery receipt (plaintext, no content) -> emit ReadReceipt as delivery
    if (env.type == SignalServiceProtos.Envelope.Type.SERVER_DELIVERY_RECEIPT) {
        val cid = env.sourceAci.ifEmpty { env.destinationAci ?: "unknown" }
        val ts = if (env.timestamp != 0L) env.timestamp else env.serverTimestamp
        _events.emit(SignalEvent.ReadReceipt(conversationId = cid, messageId = env.serverGuid, timestampMs = ts, timestamp = ts, isDelivery = true))
        return true
    }

    if (env.content.isEmpty()) return true

    // Decrypt. A failure must never fall back to the raw envelope bytes: those are attacker
    // controlled, so treating them as a Content would let anyone forge a message from any ACI.
    val e = e2e
    if (e == null) {
        // Defensive: start() refuses to connect without a store, so this should be unreachable.
        // Keep the envelope queued rather than acking something we never tried to decrypt.
        Log.w(TAG, "no protocol store, leaving envelope from ${env.sourceAci} queued")
        return false
    }
    val paddedPlaintext: ByteArray = try {
        // Which identity the sender addressed matters: a message to our PNI must be decrypted with the
        // PNI identity and its own pre-keys, not the ACI ones.
        Log.i(
            TAG,
            "envelope type=${env.type} from=${env.sourceAci}:${env.sourceDevice} " +
                "destination=${env.destinationAci} ourAci=${authData?.aci} ourPni=${authData?.pni}",
        )
        when (env.type) {
            SignalServiceProtos.Envelope.Type.UNIDENTIFIED_SENDER ->
                e.sealedSenderDecrypt(env.content, unidentifiedSenderTrustRoots(), env.serverTimestamp)
            SignalServiceProtos.Envelope.Type.PREKEY_MESSAGE ->
                e.decryptDM(env.sourceAci, env.sourceDevice, true, env.content)
            SignalServiceProtos.Envelope.Type.DOUBLE_RATCHET ->
                e.decryptDM(env.sourceAci, env.sourceDevice, false, env.content)
            // The one unencrypted envelope type, and it may only carry a DecryptionErrorMessage
            // (enforced after parsing, below).
            SignalServiceProtos.Envelope.Type.PLAINTEXT_CONTENT -> env.content
            else -> throw IllegalArgumentException("unknown envelope type ${env.type}")
        }
    } catch (t: Throwable) {
        Log.w(TAG, "decrypt failed for ${env.sourceAci}:${env.sourceDevice}", t)
        emitDecryptionError(env, t.message)
        // Ask the sender to rebuild the session; otherwise this fails identically forever.
        if (env.type != SignalServiceProtos.Envelope.Type.PLAINTEXT_CONTENT) sendRetryReceipt(env)
        // Redelivery cannot fix a decrypt failure, so ack rather than spin on it.
        return true
    }
    // Signal pads the plaintext before encrypting, for every envelope type.
    val plaintext = SignalProtocol.stripMessagePadding(paddedPlaintext)

    val content = SignalProtocol.parseContent(plaintext)
    if (content == null) {
        Log.w(TAG, "parseContent failed for ${env.sourceAci}")
        return true
    }
    if (env.type == SignalServiceProtos.Envelope.Type.PLAINTEXT_CONTENT &&
        !SignalProtocol.isValidPlaintextContent(content)
    ) {
        Log.w(TAG, "dropping PLAINTEXT_CONTENT carrying more than a DecryptionErrorMessage from ${env.sourceAci}")
        return true
    }
    val parsed = SignalProtocol.classifyContent(content)
    // A sender's profile key rides along with their messages and is what lets us send sealed to them
    // in return. It is independent of the message kind, so capture it before dispatching — a key on a
    // reaction or an edit counts just as much as one on a text message.
    profileKeyFrom(parsed)?.let { key ->
        db?.let { SignalSealedSender.rememberProfileKey(it, env.sourceAci, key) }
    }
    val masterKeyFromData: ByteArray? = when (parsed) {
        is SignalProtocol.ParsedContent.Data -> if (parsed.dataMessage.hasGroupV2() && parsed.dataMessage.groupV2.hasMasterKey()) parsed.dataMessage.groupV2.masterKey.toByteArray() else null
        is SignalProtocol.ParsedContent.Edit -> if (parsed.editMessage.hasDataMessage() && parsed.editMessage.dataMessage.hasGroupV2()) parsed.editMessage.dataMessage.groupV2.masterKey.toByteArray() else null
        else -> null
    }
    // A verified PNI signature merges the sender's ACI with the PNI we knew them by, which is what
    // keeps their reply in the conversation we sent to instead of opening a second one.
    if (content.hasPniSignatureMessage()) {
        linkPniToAci(env.sourceAci, env.sourceDevice, content.pniSignatureMessage)
    }
    val conversationId = if (masterKeyFromData != null) {
        SignalProtocol.toConversationId(env.sourceAci, masterKeyFromData)
    } else {
        // Anchored on the contact rather than the raw service id, so ACI- and PNI-addressed traffic
        // share one thread.
        conversationIdFor(env.sourceAci)
    }
    val senderDisplayName = displayNameFor(env.sourceAci)
    val senderAci = env.sourceAci
    // Remember the group's identity so a reply can be sent. Without this the conversation is created with
    // no master key or members and every reply is refused or silently dropped.
    if (masterKeyFromData != null) {
        rememberInboundGroup(conversationId, masterKeyFromData, env.sourceAci)
    }
    // A sender key can arrive on its own Content, with no DataMessage attached — official treats it as its
    // own case for exactly that reason. Storing it is what lets this sender's later group messages decrypt,
    // so it must happen before any early return in the content dispatch below.
    if (content.hasSenderKeyDistributionMessage()) {
        try {
            e2e?.processSenderKeyDistribution(
                env.sourceAci,
                env.sourceDevice,
                content.senderKeyDistributionMessage.toByteArray(),
            )
            Log.i(TAG, "stored a sender key from ${env.sourceAci}:${env.sourceDevice}")
        } catch (t: Throwable) {
            // Losing this means later group messages from this sender cannot be decrypted.
            Log.w(TAG, "failed to store sender key distribution from ${env.sourceAci}", t)
        }
    }
    val senderDevice = env.sourceDevice
    val serverGuid = env.serverGuid ?: SignalProtocol.generateMessageId()
    val timestamp = env.timestamp

    when (parsed) {
        is SignalProtocol.ParsedContent.Data -> {
            val dm = parsed.dataMessage
            when {
                dm.hasReaction() -> {
                    val r = dm.reaction
                    val targetTs = r.targetSentTimestamp
                    // Find original messageId by timestamp if possible
                    val targetId = try {
                        db?.cachedMessageDao()?.getForConversation(conversationId)?.firstOrNull { it.timestamp == targetTs }?.messageId ?: targetTs.toString()
                    } catch (_: Exception) { targetTs.toString() }
                    if (r.remove) {
                        _events.emit(SignalEvent.ReactionRemoved(conversationId = conversationId, messageId = targetId, senderId = senderAci))
                    } else {
                        _events.emit(SignalEvent.ReactionReceived(conversationId = conversationId, messageId = targetId, senderId = senderAci, emoji = r.emoji))
                    }
                }
                dm.hasDelete() -> {
                    val targetTs = dm.delete.targetSentTimestamp
                    val targetId = try {
                        db?.cachedMessageDao()?.getForConversation(conversationId)?.firstOrNull { it.timestamp == targetTs }?.messageId ?: targetTs.toString()
                    } catch (_: Exception) { targetTs.toString() }
                    _events.emit(SignalEvent.MessageDeleted(messageId = targetId, conversationId = conversationId, timestamp = timestamp))
                }
                dm.hasPollCreate() -> {
                    val pc = dm.pollCreate
                    val sd = SignalServiceData(pollQuestion = pc.question, pollOptions = pc.optionsList.map { SignalPollOptionData(it) }, senderId = senderAci)
                    _events.emit(SignalEvent.IncomingMessage(conversationId = conversationId, messageId = serverGuid, body = pc.question, peerName = senderDisplayName, peerPhone = null, timestamp = timestamp, senderId = senderAci, serviceData = sd.serialize(), pollQuestion = pc.question, pollOptions = pc.optionsList))
                }
                dm.hasPollVote() -> {
                    val pv = dm.pollVote
                    val targetTs = pv.targetSentTimestamp
                    val pollId = try {
                        db?.cachedMessageDao()?.getForConversation(conversationId)?.firstOrNull { it.timestamp == targetTs }?.messageId ?: targetTs.toString()
                    } catch (_: Exception) { targetTs.toString() }
                    val pollCached = try { db?.cachedMessageDao()?.get(pollId) } catch (_: Exception) { null }
                    val pollOptions = pollCached?.serviceData?.let { SignalServiceData.parse(it)?.pollOptions?.map { o -> o.name } } ?: emptyList()
                    val selected = pv.optionIndexesList.mapNotNull { idx -> pollOptions.getOrNull(idx) }.ifEmpty { pv.optionIndexesList.map { it.toString() } }
                    _events.emit(SignalEvent.PollVote(conversationId = conversationId, pollMessageId = pollId, voterId = senderAci, optionNames = selected))
                }
                dm.hasPollTerminate() -> {
                    // Treat as generic incoming for now; live will handle poll closure UI via serviceData flag.
                    _events.emit(SignalEvent.IncomingMessage(conversationId = conversationId, messageId = serverGuid, body = dm.body, peerName = senderDisplayName, peerPhone = null, timestamp = timestamp, senderId = senderAci, serviceData = SignalServiceData(senderId = senderAci).serialize()))
                }
                else -> {
                    val body = dm.body
                    if (body.isBlank() && dm.attachmentsCount == 0 && !dm.hasGroupV2()) return true
                    // senderKeyDistributionMessage is handled above, before content dispatch.
                    val sd = SignalServiceData(senderId = senderAci, senderName = senderDisplayName, isGroup = masterKeyFromData != null)
                    _events.emit(
                        SignalEvent.IncomingMessage(
                            conversationId = conversationId,
                            messageId = serverGuid,
                            body = body,
                            peerName = senderDisplayName,
                            peerPhone = null,
                            timestamp = timestamp,
                            senderId = senderAci,
                            attachments = attachmentsFrom(dm),
                            serviceData = sd.serialize(),
                        ),
                    )
                }
            }
        }
        is SignalProtocol.ParsedContent.Receipt -> {
            val rm = parsed.receiptMessage
            val isDelivery = rm.type == SignalServiceProtos.ReceiptMessage.Type.DELIVERY
            Log.i(
                TAG,
                "receipt from ${env.sourceAci}: type=${rm.type} timestamps=${rm.timestampList}",
            )
            for (tsVal in rm.timestampList) {
                // Resolved by timestamp rather than by conversation: the outgoing message may have been
                // filed under a different id (phone number vs service id) than this receipt resolves to,
                // and the timestamp is the message's protocol-level identity either way.
                val cached = try {
                    db?.cachedMessageDao()?.getOutgoingByTimestamp(tsVal)
                } catch (_: Exception) { null }
                if (cached == null) {
                    Log.w(TAG, "receipt for unknown outgoing message at $tsVal")
                }
                _events.emit(
                    SignalEvent.ReadReceipt(
                        conversationId = cached?.conversationId ?: conversationId,
                        messageId = cached?.messageId ?: tsVal.toString(),
                        timestampMs = tsVal,
                        timestamp = tsVal,
                        isDelivery = isDelivery,
                    ),
                )
            }
        }
        is SignalProtocol.ParsedContent.Typing -> {
            val tm = parsed.typingMessage
            val isTyping = tm.action == SignalServiceProtos.TypingMessage.Action.STARTED
            val cid = if (tm.hasGroupId()) {
                // groupId is the 32-byte GroupIdentifier, which is exactly what the conversation id
                // encodes, so it maps across directly.
                SignalProtocol.toConversationId("", SignalGroups.run { tm.groupId.toByteArray().toHex() })
            } else conversationId
            _events.emit(SignalEvent.TypingIndicator(conversationId = cid, senderId = senderAci, isTyping = isTyping))
        }
        is SignalProtocol.ParsedContent.Edit -> {
            val em = parsed.editMessage
            val targetTs = em.targetSentTimestamp
            val newBody = em.dataMessage.body
            val targetId = try {
                db?.cachedMessageDao()?.getForConversation(conversationId)?.firstOrNull { it.timestamp == targetTs }?.messageId ?: targetTs.toString()
            } catch (_: Exception) { targetTs.toString() }
            _events.emit(SignalEvent.MessageEdited(conversationId = conversationId, messageId = targetId, newBody = newBody, timestamp = timestamp))
        }
        is SignalProtocol.ParsedContent.Call -> {
            handleCallMessage(parsed.callMessage, senderAci, senderDevice, env, timestamp)
        }
        is SignalProtocol.ParsedContent.Sync -> {
            val sm = parsed.syncMessage
            // Read/viewed syncs from our own other devices refer to inbound messages by sent timestamp,
            // so resolve on that rather than scoping to a conversation id that may not match.
            for (r in sm.readList) {
                emitReadSync(r.timestamp, conversationId)
            }
            for (v in sm.viewedList) {
                emitReadSync(v.timestamp, conversationId)
            }
        }
        else -> {
            Log.i(TAG, "unhandled Content type ${parsed::class.simpleName} from $senderAci")
        }
    }
    return true
}

/**
 * Tell [env]'s sender that we could not decrypt, so they rebuild the session.
 *
 * This is the protocol's recovery path: a `DecryptionErrorMessage` in a plaintext envelope makes the
 * sender archive their session and fetch a fresh pre-key bundle. Without it a session that has drifted
 * — for instance because our Kyber pre-key was replaced — stays broken forever, with every retry
 * failing identically.
 *
 * Sent unencrypted by design; `PLAINTEXT_CONTENT` is the one envelope type that may be, and it may
 * carry nothing but this.
 */
private suspend fun SignalClient.sendRetryReceipt(env: SignalProtocol.SignalEnvelope) {
    val e = e2e ?: return
    val sender = env.sourceAci.takeIf { it.isNotEmpty() } ?: return
    if (env.content.isEmpty()) return
    try {
        val errorMessage = DecryptionErrorMessage.forOriginalMessage(
            env.content,
            env.type.number,
            env.timestamp,
            env.sourceDevice,
        )
        val body = PlaintextContent(errorMessage).serialize()
        // The server rejects a send whose destinationRegistrationId does not match the recipient's, so
        // this must be their real one. A pre-key message carries it; otherwise fall back to the session.
        // Deliberately read before anything touches the session, and the session is not archived here —
        // the receipt asks *them* to archive theirs.
        val registrationId = e.senderRegistrationId(env.content)
            ?: e.remoteRegistrationId(sender, env.sourceDevice)
        if (registrationId == null) {
            Log.w(TAG, "no registration id for $sender:${env.sourceDevice}, cannot send a retry receipt")
            return
        }
        val message = SignalPayload.OutgoingPushMessage(
            type = SignalServiceProtos.Envelope.Type.PLAINTEXT_CONTENT.number,
            destinationDeviceId = env.sourceDevice,
            destinationRegistrationId = registrationId,
            content = body,
        )
        val json = SignalPayload.buildPutMessagesBody(
            destinationAci = sender,
            messages = listOf(message),
            timestamp = System.currentTimeMillis(),
            urgent = false,
        )
    when (val outcome = putMessages(sender, json, accessKey = null)) {
            is SignalClient.SendOutcome.Success -> Log.i(TAG, "sent a retry receipt to $sender:${env.sourceDevice}")
            else -> Log.w(TAG, "could not send a retry receipt to $sender: $outcome")
        }
    } catch (t: Throwable) {
        Log.w(TAG, "could not build a retry receipt for $sender", t)
    }
}

/**
 * Inbound attachment pointers, previously dropped entirely. The CDN URL is derived from
 * `cdnKey`/`cdnNumber`; the key and digest travel with the pointer and are what
 * [SignalClient.downloadMedia] needs to decrypt and verify.
 */
private fun SignalClient.attachmentsFrom(dm: SignalServiceProtos.DataMessage): List<SignalAttachment> =
    dm.attachmentsList.mapNotNull { pointer ->
        val cdnKey = pointer.cdnKey?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val mime = pointer.contentType?.takeIf { it.isNotEmpty() } ?: "application/octet-stream"
        SignalAttachment(
            url = attachmentUrl(cdnKey, pointer.cdnNumber),
            mimeType = mime,
            attachmentType = when {
                mime.startsWith("image/") -> "image"
                mime.startsWith("video/") -> "video"
                mime.startsWith("audio/") -> "audio"
                else -> "file"
            },
            fileName = pointer.fileName?.takeIf { it.isNotEmpty() },
            width = pointer.width,
            height = pointer.height,
        )
    }

private fun SignalClient.attachmentUrl(cdnKey: String, cdnNumber: Int): String {
    val host = if (cdnNumber == 0) "cdn.signal.org" else "cdn$cdnNumber.signal.org"
    return "https://$host/attachments/$cdnKey"
}

private suspend fun SignalClient.emitReadSync(timestamp: Long, fallbackConversationId: String) {
    val cached = try { db?.cachedMessageDao()?.getByTimestamp(timestamp) } catch (_: Exception) { null }
    _events.emit(
        SignalEvent.ReadReceipt(
            conversationId = cached?.conversationId ?: fallbackConversationId,
            messageId = cached?.messageId ?: timestamp.toString(),
            timestampMs = timestamp,
            timestamp = timestamp,
            isDelivery = false,
        ),
    )
}

private suspend fun SignalClient.emitDecryptionError(env: SignalProtocol.SignalEnvelope, message: String?) {
    val cid = SignalProtocol.toConversationId(env.sourceAci, null as ByteArray?)
    _events.emit(
        SignalEvent.DecryptionError(
            conversationId = cid,
            senderAci = env.sourceAci,
            senderDeviceId = env.sourceDevice,
            timestamp = env.timestamp,
            errorMessage = message,
        ),
    )
}

/**
 * Sealed-sender trust roots. These are build constants in the official client
 * (`UNIDENTIFIED_SENDER_TRUST_ROOTS`), a list so the server can rotate; a certificate is accepted
 * if it validates against any of them.
 */
private fun SignalClient.unidentifiedSenderTrustRoots(): List<org.signal.libsignal.protocol.ecc.ECPublicKey> =
    TRUST_ROOTS_B64.map {
        org.signal.libsignal.protocol.ecc.ECPublicKey(AndroidBase64.decode(it, AndroidBase64.NO_WRAP))
    }

/** The group conversation id a DataMessage belongs to, or empty when it is not a group message. */
private fun SignalClient.groupIdFor(dm: SignalServiceProtos.DataMessage): String {
    return if (dm.hasGroupV2() && dm.groupV2.hasMasterKey()) {
        SignalProtocol.toConversationId("", dm.groupV2.masterKey.toByteArray())
    } else {
        ""
    }
}
