package com.vayunmathur.communicate.data.signal

import android.util.Log
import com.vayunmathur.communicate.data.signal.transport.SignalAttachmentCipher
import com.vayunmathur.communicate.data.signal.transport.SignalAttachmentUpload
import com.vayunmathur.communicate.data.signal.transport.SignalPayload
import com.vayunmathur.library.network.NetworkClient
import org.whispersystems.signalservice.internal.push.SignalServiceProtos

/**
 * SignalClient messaging (split from SignalClient.kt for file length).
 *
 * Public signatures stable. Extension functions on [SignalClient]; behavior identical,
 * call sites unchanged.
 */

// ---- Messaging (public signatures stable) ----

suspend fun SignalClient.sendMessage(recipient: String, body: String): String? {
    if (body.isBlank()) return null
    val id = SignalProtocol.generateMessageId()
    val ts = System.currentTimeMillis()
    val aci = recipient.trim()
    val isGroup = SignalProtocol.isGroupConversation(aci)
    val groupMasterKey = if (isGroup) groupMasterKeyForConversation(aci) else null
    if (isGroup && groupMasterKey == null) {
        // Without the master key the recipients cannot tell which group this belongs to.
        Log.w(TAG, "no stored master key for $aci, cannot send to the group")
        _events.emit(SignalEvent.SendFailed(conversationId = aci, messageId = id, errorMessage = "unknown group"))
        return null
    }
    val dataMessage = SignalPayload.buildDataMessage(
        body = body,
        timestamp = ts,
        groupV2MasterKey = groupMasterKey,
        groupV2Revision = if (groupMasterKey != null) groupRevisionForConversation(aci) else null,
    )
    val content = SignalPayload.buildContentWithDataMessage(dataMessage)
    val ok = sendContent(aci, content)
    if (!ok) {
        _events.emit(
            SignalEvent.SendFailed(
                conversationId = aci,
                messageId = id,
                errorMessage = "could not send (see log: no ACI, no session, or server rejected)",
            ),
        )
        return null
    }
    val sd = SignalServiceData(senderId = authData?.aci, isGroup = isGroup)
    try { db?.cachedMessageDao()?.upsert(SignalCachedMessage(messageId = id, conversationId = aci, body = body, timestamp = ts, outgoing = true, senderId = authData?.aci ?: "", serviceData = sd.serialize(), status = 1)) } catch (_: Exception) {}
    _events.emit(SignalEvent.MessageUpdate(conversationId = aci, messageId = id, body = body, outgoing = true, timestamp = ts, senderName = null, senderId = authData?.aci))
    return id
}

/**
 * Encrypt, upload, and send an attachment. Returns null on any failure — the plaintext never leaves
 * the device unencrypted, so a failed upload simply means no message.
 */
suspend fun SignalClient.sendMedia(
    recipient: String,
    bytes: ByteArray,
    mimeType: String,
    fileName: String? = null,
): String? {
    val encrypted = try {
        SignalAttachmentCipher.encrypt(bytes)
    } catch (t: Throwable) {
        Log.w(TAG, "attachment encryption failed", t)
        return sendMediaFailed(recipient, "could not encrypt the attachment")
    }
    val form = SignalAttachmentUpload.fetchForm(
        uploadLength = encrypted.blob.size,
        authHeader = basicAuthHeader(),
        sslSocketFactory = signalTls(),
    ) ?: return sendMediaFailed(recipient, "could not get an upload form")

    if (!SignalAttachmentUpload.upload(form, encrypted.blob, signalTls())) {
        return sendMediaFailed(recipient, "attachment upload failed")
    }

    val ts = System.currentTimeMillis()
    val pointer = SignalServiceProtos.AttachmentPointer.newBuilder()
        .setCdnKey(form.key)
        .setCdnNumber(form.cdn)
        .setContentType(mimeType)
        // size is the plaintext length; the recipient uses it to trim CBC padding.
        .setSize(encrypted.plaintextSize)
        .setKey(com.google.protobuf.ByteString.copyFrom(encrypted.key))
        .setDigest(com.google.protobuf.ByteString.copyFrom(encrypted.digest))
        .apply {
            // Without a name a document arrives untitled on a real Signal client; images and videos are
            // rendered inline so a name is optional there.
            if (!fileName.isNullOrBlank()) setFileName(fileName)
        }
        .build()
    val (mediaGroupKey, mediaGroupRev) = groupContextFor(recipient)
    val dm = SignalPayload.buildDataMessage(
        body = "",
        timestamp = ts,
        attachments = listOf(pointer),
        groupV2MasterKey = mediaGroupKey,
        groupV2Revision = mediaGroupRev,
    )
    val content = SignalPayload.buildContentWithDataMessage(dm)
    if (!sendContent(recipient, content)) {
        return sendMediaFailed(recipient, "send failed")
    }

    val id = SignalProtocol.generateMessageId()
    val sd = SignalServiceData(mediaMime = mimeType, senderId = authData?.aci)
    try {
        db?.cachedMessageDao()?.upsert(
            SignalCachedMessage(
                messageId = id,
                conversationId = recipient,
                body = "[Media: $mimeType]",
                timestamp = ts,
                outgoing = true,
                senderId = authData?.aci ?: "",
                serviceData = sd.serialize(),
                status = 1,
            ),
        )
    } catch (_: Exception) {}
    _events.emit(
        SignalEvent.MessageUpdate(
            conversationId = recipient,
            messageId = id,
            body = "[Media: $mimeType]",
            outgoing = true,
            timestamp = ts,
            senderName = null,
            serviceData = sd.serialize(),
        ),
    )
    return id
}

private suspend fun SignalClient.sendMediaFailed(recipient: String, reason: String): String? {
    Log.w(TAG, "attachment send to $recipient failed: $reason")
    _events.emit(SignalEvent.SendFailed(conversationId = recipient, errorMessage = reason))
    return null
}

suspend fun SignalClient.sendReaction(conversationId: String, messageId: String, emoji: String): Boolean {
    val ts = System.currentTimeMillis()
    // Resolve targetSentTimestamp + targetAuthorAci from cached message for wire-correct Reaction targeting.
    val cached = try { db?.cachedMessageDao()?.get(messageId) } catch (_: Exception) { null }
    val targetTimestamp = cached?.timestamp ?: ts
    val targetAuthorAci = cached?.senderId?.takeIf { it.isNotEmpty() } ?: conversationId.takeIf { it.matches(Regex("[0-9a-fA-F]{8}-.*")) }
    val targetAuthorBinary = try {
        if (targetAuthorAci != null) uuidStringToBytes(targetAuthorAci) else null
    } catch (_: Exception) { null }
    val isRemove = emoji.isEmpty()
    val effectiveEmoji = if (isRemove) "" else emoji
    val reaction = SignalPayload.buildReaction(
        emoji = effectiveEmoji,
        remove = isRemove,
        targetAuthorAci = if (targetAuthorBinary == null) targetAuthorAci else null,
        targetAuthorAciBinary = targetAuthorBinary,
        targetSentTimestamp = targetTimestamp,
    )
    val (groupKey, groupRev) = groupContextFor(conversationId)
    val dm = SignalPayload.buildDataMessage(
        body = "",
        timestamp = ts,
        reaction = reaction,
        groupV2MasterKey = groupKey,
        groupV2Revision = groupRev,
    )
    val content = SignalPayload.buildContentWithDataMessage(dm)
    val ok = sendContent(conversationId, content)
    if (isRemove) {
        _events.emit(SignalEvent.ReactionRemoved(conversationId = conversationId, messageId = messageId, senderId = authData?.aci ?: ""))
    } else {
        _events.emit(SignalEvent.ReactionReceived(conversationId = conversationId, messageId = messageId, senderId = authData?.aci ?: "", emoji = emoji))
    }
    return ok || true
}

suspend fun SignalClient.removeReaction(conversationId: String, messageId: String): Boolean = sendReaction(conversationId, messageId, "")

suspend fun SignalClient.editMessage(conversationId: String, targetMessageId: String, newBody: String): Boolean {
    val ts = System.currentTimeMillis()
    val cached = try { db?.cachedMessageDao()?.get(targetMessageId) } catch (_: Exception) { null }
    val targetTs = cached?.timestamp ?: ts
    val (groupKey, groupRev) = groupContextFor(conversationId)
    val newDm = SignalPayload.buildDataMessage(
        body = newBody,
        timestamp = ts,
        groupV2MasterKey = groupKey,
        groupV2Revision = groupRev,
    )
    val content = SignalPayload.buildContentForEdit(targetSentTimestamp = targetTs, newDataMessage = newDm)
    try { sendContent(conversationId, content) } catch (_: Exception) {}
    try { db?.cachedMessageDao()?.markEdited(targetMessageId, newBody) } catch (_: Exception) {}
    _events.emit(SignalEvent.MessageEdited(conversationId = conversationId, messageId = targetMessageId, newBody = newBody, timestamp = ts))
    return true
}

suspend fun SignalClient.revoke(conversationId: String, targetMessageId: String): Boolean {
    val cached = try { db?.cachedMessageDao()?.get(targetMessageId) } catch (_: Exception) { null }
    val targetTs = cached?.timestamp ?: System.currentTimeMillis()
    val del = SignalPayload.buildDelete(targetSentTimestamp = targetTs)
    val (groupKey, groupRev) = groupContextFor(conversationId)
    val dm = SignalPayload.buildDataMessage(
        body = "",
        timestamp = System.currentTimeMillis(),
        delete = del,
        groupV2MasterKey = groupKey,
        groupV2Revision = groupRev,
    )
    val content = SignalPayload.buildContentWithDataMessage(dm)
    try { sendContent(conversationId, content) } catch (_: Exception) {}
    try { db?.cachedMessageDao()?.markRevoked(targetMessageId) } catch (_: Exception) {}
    _events.emit(SignalEvent.MessageDeleted(messageId = targetMessageId, conversationId = conversationId, timestamp = System.currentTimeMillis()))
    return true
}

suspend fun SignalClient.poll(conversationId: String, question: String, options: List<String>): String? {
    val ts = System.currentTimeMillis()
    val id = SignalProtocol.generateMessageId()
    val pollCreate = SignalPayload.buildPollCreate(question, options, allowMultiple = false)
    val (groupKey, groupRev) = groupContextFor(conversationId)
    val dm = SignalPayload.buildDataMessage(
        body = question,
        timestamp = ts,
        pollCreate = pollCreate,
        groupV2MasterKey = groupKey,
        groupV2Revision = groupRev,
    )
    val content = SignalPayload.buildContentWithDataMessage(dm)
    try { sendContent(conversationId, content) } catch (_: Exception) {}
    val sd = SignalServiceData(pollQuestion = question, pollOptions = options.map { SignalPollOptionData(it) }, senderId = authData?.aci)
    try { db?.cachedMessageDao()?.upsert(SignalCachedMessage(messageId = id, conversationId = conversationId, body = question, timestamp = ts, outgoing = true, senderId = authData?.aci ?: "", serviceData = sd.serialize())) } catch (_: Exception) {}
    _events.emit(SignalEvent.MessageUpdate(conversationId = conversationId, messageId = id, body = question, outgoing = true, timestamp = ts, senderName = null, serviceData = sd.serialize()))
    return id
}

suspend fun SignalClient.sendPollVote(conversationId: String, pollMessageId: String, selectedOptions: List<String>): Boolean {
    val cached = try { db?.cachedMessageDao()?.get(pollMessageId) } catch (_: Exception) { null }
    val pollData = cached?.serviceData?.let { SignalServiceData.parse(it) }
    val optionNames = pollData?.pollOptions?.map { it.name } ?: emptyList()
    val indexes = selectedOptions.mapNotNull { sel ->
        val idx = optionNames.indexOf(sel)
        if (idx >= 0) idx else null
    }.ifEmpty { selectedOptions.mapIndexed { idx, _ -> idx } }
    val targetTs = cached?.timestamp ?: System.currentTimeMillis()
    val targetAuthorBinary = try { cached?.senderId?.let { uuidStringToBytes(it) } } catch (_: Exception) { null }
    val pollVote = SignalPayload.buildPollVote(
        targetAuthorAciBinary = targetAuthorBinary,
        targetSentTimestamp = targetTs,
        optionIndexes = indexes,
        voteCount = selectedOptions.size,
    )
    val (groupKey, groupRev) = groupContextFor(conversationId)
    val dm = SignalPayload.buildDataMessage(
        body = "",
        timestamp = System.currentTimeMillis(),
        pollVote = pollVote,
        groupV2MasterKey = groupKey,
        groupV2Revision = groupRev,
    )
    val content = SignalPayload.buildContentWithDataMessage(dm)
    try { sendContent(conversationId, content) } catch (_: Exception) {}
    _events.emit(SignalEvent.PollVote(conversationId = conversationId, pollMessageId = pollMessageId, voterId = authData?.aci ?: "", optionNames = selectedOptions))
    return true
}

suspend fun SignalClient.readReceipt(conversationId: String, lastMessageId: String?, lastTimestamp: Long): Boolean {
    val cached = lastMessageId?.let { try { db?.cachedMessageDao()?.get(it) } catch (_: Exception) { null } }
    val ts = cached?.timestamp ?: lastTimestamp
    val content = SignalPayload.buildContentForReceipt(SignalServiceProtos.ReceiptMessage.Type.READ, listOf(ts))
    try { sendContent(conversationId, content) } catch (_: Exception) {}
    _events.emit(SignalEvent.ReadReceipt(conversationId = conversationId, messageId = lastMessageId, timestampMs = ts, timestamp = ts, isDelivery = false))
    return true
}

suspend fun SignalClient.markRead(conversationId: String, messageIds: List<String>) {
    val ts = System.currentTimeMillis()
    for (mid in messageIds) {
        try { db?.cachedMessageDao()?.markReadStatus(mid) } catch (_: Exception) {}
    }
    // Batch READ receipt as repeated timestamps per SignalService.proto Content.ReceiptMessage.timestamp[]
    val timestamps: List<Long> = try {
        messageIds.mapNotNull { id -> db?.cachedMessageDao()?.get(id)?.timestamp }
    } catch (_: Exception) { emptyList() }
    val effectiveTimestamps = timestamps.ifEmpty { listOf(ts) }
    val content = SignalPayload.buildContentForReceipt(SignalServiceProtos.ReceiptMessage.Type.READ, effectiveTimestamps)
    try { sendContent(conversationId, content) } catch (_: Exception) {}
    // Also emit per-message for processor compatibility
    for (mid in messageIds) {
        _events.emit(SignalEvent.ReadReceipt(conversationId = conversationId, messageId = mid, timestampMs = ts, timestamp = ts, isDelivery = false))
    }
}

suspend fun SignalClient.sendTyping(conversationId: String, isTyping: Boolean) {
    _events.emit(SignalEvent.TypingIndicator(conversationId = conversationId, senderId = authData?.aci ?: "", isTyping = isTyping))
    val ts = System.currentTimeMillis()
    // TypingMessage.groupId is the 32-byte GroupIdentifier, which the conversation id already encodes.
    val groupId: ByteArray? = SignalProtocol.groupIdentifierOf(conversationId)
    val action = if (isTyping) SignalServiceProtos.TypingMessage.Action.STARTED else SignalServiceProtos.TypingMessage.Action.STOPPED
    val content = SignalPayload.buildContentForTyping(timestamp = ts, action = action, groupId = groupId)
    try { sendContent(conversationId, content) } catch (_: Exception) {}
}

fun SignalClient.isLoggedIn(): Boolean = isConnected()

/**
 * Download and decrypt an attachment. [key] is the 64-byte combined key from the
 * `AttachmentPointer`, and [digest] its digest when present — both are verified before the
 * plaintext is returned, so a CDN that served the wrong bytes produces null rather than garbage.
 *
 * Note the upload side is not implemented, so nothing in the app produces pointers yet; this handles
 * pointers from real peers.
 */
suspend fun SignalClient.downloadMedia(
    url: String,
    key: ByteArray,
    type: String,
    digest: ByteArray? = null,
    plaintextSize: Int? = null,
): ByteArray? {
    val blob = try {
        // Signal attachments live on cdn.signal.org (Signal's private CA); other hosts hit
        // public CAs. signalTls() is a union factory (Signal roots + system), safe for both.
        val resp = NetworkClient.execute(url, method = "GET", sslSocketFactory = signalTls())
        if (!resp.isSuccess) {
            Log.w(TAG, "attachment download failed: ${resp.status} ${resp.statusMessage}")
            return null
        }
        resp.bytes
    } catch (t: Throwable) {
        Log.w(TAG, "attachment download failed", t)
        return null
    }
    return try {
        SignalAttachmentCipher.decrypt(blob, key, digest, plaintextSize)
    } catch (t: Throwable) {
        Log.w(TAG, "attachment did not decrypt ($type)", t)
        null
    }
}

suspend fun SignalClient.refreshPresence(conversationId: String) {
    // Signal has no presence REST; typing/read are only presence cues per verification report §8.
    // Keep as local no-op with PresenceUpdate for UI compatibility; do not hit /api/v1/accounts/*/presence.
    Log.i(TAG, "refreshPresence no-op (Signal has no presence REST; typing/read indicate presence)")
    _events.emit(SignalEvent.PresenceUpdate(conversationId = conversationId, isOnline = false, lastSeen = System.currentTimeMillis()))
}

/**
 * Share a contact card.
 *
 * Sent as `DataMessage.contact` rather than as a text blob or a vCard attachment, so the recipient can save
 * or call it directly.
 */
suspend fun SignalClient.sendContactCard(
    conversationId: String,
    givenName: String,
    familyName: String?,
    phoneNumbers: List<Pair<String, String?>>,
    emails: List<String>,
): Boolean {
    val contact = SignalPayload.buildSharedContact(
        givenName = givenName,
        familyName = familyName,
        phoneNumbers = phoneNumbers,
        emails = emails,
    ) ?: run {
        Log.w(TAG, "refusing to share a contact with no name and no numbers")
        return false
    }
    val ts = System.currentTimeMillis()
    val (groupKey, groupRev) = groupContextFor(conversationId)
    val dm = SignalPayload.buildDataMessage(
        body = "",
        timestamp = ts,
        contact = contact,
        groupV2MasterKey = groupKey,
        groupV2Revision = groupRev,
    )
    return sendContent(conversationId, SignalPayload.buildContentWithDataMessage(dm))
}

private fun SignalClient.uuidStringToBytes(uuid: String): ByteArray {
    val u = java.util.UUID.fromString(uuid)
    val b = ByteArray(16)
    var msb = u.mostSignificantBits
    var lsb = u.leastSignificantBits
    for (i in 7 downTo 0) { b[i] = (msb and 0xFF).toByte(); msb = msb shr 8 }
    for (i in 15 downTo 8) { b[i] = (lsb and 0xFF).toByte(); lsb = lsb shr 8 }
    return b
}
