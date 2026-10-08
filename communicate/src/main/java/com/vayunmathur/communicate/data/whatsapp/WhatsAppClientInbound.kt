package com.vayunmathur.communicate.data.whatsapp

import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient.State
import com.vayunmathur.communicate.data.whatsapp.buildAck
import com.vayunmathur.communicate.data.whatsapp.decodeNode
import com.vayunmathur.communicate.data.whatsapp.decryptPollVote
import com.vayunmathur.communicate.data.whatsapp.encodeNode
import com.vayunmathur.communicate.data.whatsapp.parseMessage
import com.vayunmathur.communicate.data.whatsapp.pollCreation
import com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.launch

private const val MS_PER_SECOND = 1000L

/** Stanza tags that require an ack (messages + calls during offline flush). */
private val ACKED_TAGS = setOf("message", "notification", "receipt", "call")

internal fun WhatsAppClient.handleIncomingMessage(data: ByteArray) {
    scope.launch {
        try {
            val node = WhatsAppProtocol.decodeNode(data)
            WhatsAppDiag.log(TAG, "← login stanza ${nodeSummary(node)}")

            handleAckStanza(node)
            if (handleIqResponse(node)) return@launch
            if (handleControlStanza(node)) return@launch
            if (node.tag != "message") return@launch
            handleChatMessage(node)
        } catch (expected: Exception) {
            Log.error(TAG, "Failed to handle incoming message", expected)
        }
    }
}

/** Log server acks for outbound stanzas and ack inbound ones. */
private fun WhatsAppClient.handleAckStanza(node: WhatsAppProtocol.Node) {
    // Server ack for an outbound stanza. An ack with an `error` attr means the server
    // REJECTED the message (it will never be delivered) — surface it prominently so a
    // "sent but not delivered" report can be diagnosed from logs.
    if (node.tag == "ack") {
        val ackErr = node.attrs["error"]
        if (ackErr != null) {
            WhatsAppDiag.log(
                TAG,
                "← ack REJECTED class=${node.attrs["class"]} id=${node.attrs["id"]} error=$ackErr")
        }
    }

    // Ack messages, notifications, receipts AND calls (whatsmeow/receipt.go +
    // call.go). Calls MUST be acked too: during the post-login offline flush the server
    // is flow-controlled and head-of-line — if the offline <call> offers aren't acked it
    // stops delivering, so queued <message> stanzas never arrive ("stuck syncing").
    if (node.tag in ACKED_TAGS) {
        val ack = WhatsAppProtocol.buildAck(
            nodeClass = node.tag,
            nodeId = node.attrs["id"] ?: "",
            from = node.attrs["from"] ?: "",
            participant = node.attrs["participant"],
            recipient = node.attrs["recipient"],
            type = if (node.tag != "message") node.attrs["type"] else null,
        )
        webSocket?.send(WhatsAppProtocol.encodeNode(ack))
    }
}

/** Complete a pending IQ request; true when the stanza was consumed. */
private fun WhatsAppClient.handleIqResponse(node: WhatsAppProtocol.Node): Boolean {
    // Complete any pending IQ request awaiting this response (prekey fetch/upload).
    if (node.tag == "iq") {
        val iqId = node.attrs["id"]
        val pending = if (iqId != null) pendingIqs.remove(iqId) else null
        if (pending != null) {
            pending.complete(node)
            return true
        }
    }
    return false
}

/** Connection/control stanzas; true when the stanza was consumed. */
private suspend fun WhatsAppClient.handleControlStanza(node: WhatsAppProtocol.Node): Boolean {
    // Handle connection failure events (Go handleWALogout, ClientOutdated, TemporaryBan)
    if (node.tag == "failure") {
        handleFailureStanza(node)
        return true
    }

    if (node.tag == "stream:error") {
        handleStreamError(node)
        return true
    }

    // Handle the server <success> stanza — the real authentication signal
    // (Go connectionevents.go handleConnectSuccess). Until this arrives the Noise
    // transport is up but the login is not yet authenticated.
    if (node.tag == "success") {
        handleConnectSuccess(node)
        return true
    }

    // Handle receipts with per-sender batching (Go handleWAReceipt)
    if (node.tag == "receipt") {
        handleReceipt(node)
        return true
    }

    // Handle chat presence with media type differentiation (Go handleWAChatPresence)
    if (node.tag == "chatstate") {
        handleChatPresence(node)
        return true
    }

    return handleNotificationStanza(node)
}

/** Login <failure> stanza → disconnected state. */
private fun WhatsAppClient.handleFailureStanza(node: WhatsAppProtocol.Node) {
    val reason = node.attrs["reason"] ?: "unknown"
    WhatsAppDiag.log(TAG, "login <failure> reason=$reason full=${nodeSummary(node)}")
    when (reason) {
        "401" -> {
            stateMutable.value = State.Disconnected("Logged out from WhatsApp")
            scope.launch { WhatsAppAuthData.clear(appContext) }
            authData = null
        }
        "405" -> stateMutable.value = State.Disconnected("Client outdated — update required")
        "503" -> stateMutable.value = State.Disconnected("Temporarily banned")
        else -> stateMutable.value = State.Disconnected("Connection failed: $reason")
    }
}

/** stream:error → logout/replace/restart handling. */
private suspend fun WhatsAppClient.handleStreamError(node: WhatsAppProtocol.Node) {
    val errorNode = node.content.firstOrNull()
    val code = node.attrs["code"]
    val conflict = node.getChildByTag("conflict")
    val conflictType = conflict?.attrs?.get("type")
    WhatsAppDiag.log(
        TAG,
        "login stream:error code=$code child=${errorNode?.tag} conflictType=$conflictType")
    when {
        // Device removed from the account on the phone, or another session took over.
        conflict != null || code == "401" -> {
            suppressReconnect = true
            if (conflictType == "device_removed" || code == "401") {
                WhatsAppDiag.log(TAG, "device removed/logged out — clearing credentials")
                scope.launch { WhatsAppAuthData.clear(appContext) }
                authData = null
                stateMutable.value = State.NeedsSetup
                scope.launch { eventsMutable.emit(WhatsAppEvent.SourceLoggedOut(source)) }
            } else {
                // "replaced" or other conflict: another WhatsApp session is active.
                stateMutable.value = State.Disconnected("Session replaced by another device")
            }
        }
        // 515 = restart required (normal after first pair-login); allow reconnect.
        code == "515" -> stateMutable.value = State.Disconnected("Restart required")
        else -> stateMutable.value = State.Disconnected("Stream error${if (code != null) " $code" else ""}")
    }
}

/** Notification + ib stanzas; true when consumed. */
private suspend fun WhatsAppClient.handleNotificationStanza(node: WhatsAppProtocol.Node): Boolean {
    // Handle notifications (group changes, mute/pin/archive)
    if (node.tag == "notification") {
        handleNotification(node)
        return true
    }

    // <ib> info blocks: respond to <dirty> with <clean> so the phone finishes "syncing".
    if (node.tag == "ib") {
        node.getChildByTag("dirty")?.let { handleDirty(it) }
        node.getChildByTag("offline_preview")?.let {
            val count = it.attrs["count"]
            val msg = it.attrs["message"]
            val notif = it.attrs["notification"]
            val receipt = it.attrs["receipt"]
            WhatsAppDiag.log(TAG, "offline preview: count=$count msg=$msg notif=$notif receipt=$receipt")
        }
        node.getChildByTag("offline")?.let {
            WhatsAppDiag.log(TAG, "offline sync completed: count=${it.attrs["count"]}")
        }
        return true
    }
    return false
}

/** Message stanzas: decrypt, route poll/history/key-share, emit. */
private suspend fun WhatsAppClient.handleChatMessage(node: WhatsAppProtocol.Node) {

    if (node.tag != "message") return
    val message = decryptChatMessage(node) ?: return
    if (handleProtocolMessage(node, message)) return
    emitChatMessage(node, message)
}

/** Decrypt the stanza; null when undecryptable or undecrypted. */
private suspend fun WhatsAppClient.decryptChatMessage(node: WhatsAppProtocol.Node): WhatsAppMessage? {
    // Check for undecryptable messages (Go handleWAUndecryptableMessage)
    val encNode = node.getChildByTag("enc")
    if (encNode?.data == null && node.attrs["type"] == "text") {
        // No ciphertext at all (a decrypt-fail placeholder). Track it AND ask the
        // sender to re-encrypt, otherwise this first-of-session message is lost for
        // good. sendRetryReceipt is self-capped at 5 attempts.
        trackUndecryptable(node)
        sendRetryReceipt(node)
        return null
    }

    val crypto = authData?.let { ensureE2E(it) }
    var decryptFailed = false
    val message = WhatsAppProtocol.parseMessage(node) { senderJid, encType, data ->
        if (crypto == null) {
            decryptFailed = true
            return@parseMessage null
        }
        val result = try {
            when (encType) {
                "pkmsg" -> crypto.decryptDM(senderJid, isPreKey = true, ciphertext = data)
                "msg" -> crypto.decryptDM(senderJid, isPreKey = false, ciphertext = data)
                "skmsg" -> crypto.decryptGroup(node.attrs["from"] ?: "", senderJid, data)
                else -> null
            }
        } catch (expected: Exception) {
            Log.error(TAG, "E2E decrypt failed ($encType) from $senderJid", expected)
            null
        }
        if (result == null) decryptFailed = true
        result
    } ?: return null

    // Decrypt failure: ask the sender to re-encrypt (Go decryptMessages -> sendRetryReceipt).
    if (decryptFailed) {
        val encTypes = node.getChildren()
            .filter { it.tag == "enc" }
            .mapNotNull { it.attrs["type"] }
        WhatsAppDiag.log(TAG, "recv: DECRYPT FAILED id=${node.attrs["id"]}" +
            "type=${node.attrs["type"]} from=${node.attrs["from"]} encs=$encTypes")
        sendRetryReceipt(node)
        return null
    }
    return message
}

/**
 * Protocol-level routing (poll log, history sync, key share, SKDM, status, dedup).
 * True when the message was consumed.
 */
private suspend fun WhatsAppClient.handleProtocolMessage(
    node: WhatsAppProtocol.Node,
    message: WhatsAppMessage,
): Boolean {
    if (node.attrs["type"] == "poll" || WhatsAppProtocol.pollCreation(message.e2eMessage) != null ||
        message.e2eMessage?.hasPollUpdateMessage() == true
    ) {
        WhatsAppDiag.log(
            TAG,
            "poll recv: id=${message.id} parsedType=${message.messageType} " +
                "create=${WhatsAppProtocol.pollCreation(message.e2eMessage) != null} " +
                "vote=${message.e2eMessage?.hasPollUpdateMessage()} " +
                "q=${message.pollData?.question} opts=${message.pollData?.options?.size} " +
                "fromMe=${message.isFromMe}",
        )
    }

    // History sync: the phone pushes message history as a protocolMessage notification
    // (inline for the initial bootstrap, or a downloadable encrypted blob otherwise).
    val histNotif = message.e2eMessage
        ?.takeIf { it.hasProtocolMessage() && it.protocolMessage.hasHistorySyncNotification() }
        ?.protocolMessage?.historySyncNotification
    if (histNotif != null) {
        scope.launch { handleHistorySync(histNotif, message.id) }
        return true
    }

    // App-state sync keys, shared by our primary as a peer protocolMessage.
    val keyShare = message.e2eMessage
        ?.takeIf { it.hasProtocolMessage() && it.protocolMessage.hasAppStateSyncKeyShare() }
        ?.protocolMessage?.appStateSyncKeyShare
    if (keyShare != null) {
        scope.launch { handleAppStateKeyShare(keyShare) }
        return true
    }

    // Process inbound sender-key distribution so future group skmsg decrypt.
    // UNVERIFIED: group sender-key wire mapping not runtime-tested.
    val skdm = message.e2eMessage?.takeIf { it.hasSenderKeyDistributionMessage() }
        ?.senderKeyDistributionMessage
    if (skdm != null) {
        processSkdm(message, skdm)
        // A SKDM-only message carries no user-visible content (types as "ignore"); once
        // the key is stored there's nothing to display, so stop before emitting a blank
        // bubble. Messages that bundle the SKDM with real content fall through to render.
        if (message.messageType == "ignore") return true
    }

    // Skip status broadcasts (Go handleWAMessage status@broadcast check)
    if (message.from.startsWith("status@broadcast")) {
        Log.debug(TAG, "Skipping status broadcast from ${message.participant}")
        return true
    }

    // Pending message dedup (Go handleWAMessage pendingMessages check)
    if (pendingMessageIDs.remove(message.id)) {
        Log.debug(TAG, "Ignoring pending message ${message.id}")
        return true
    }
    return false
}

/** Process an inbound sender-key distribution message. */
private suspend fun WhatsAppClient.processSkdm(
    message: WhatsAppMessage,
    skdm: WhatsAppE2EProto.SenderKeyDistributionMessage,
) {
    val crypto = authData?.let { ensureE2E(it) } ?: return
    try {
        crypto.processSenderKeyDistribution(
            message.from,
            message.participant ?: message.from,
            skdm.axolotlSenderKeyDistributionMessage.toByteArray(),
        )
    } catch (expected: Exception) {
        Log.status(TAG, "Failed to process SKDM", expected)
    }
}

/** Emit the user-visible message (revoke/edit/timer/poll/reaction/regular). */
private suspend fun WhatsAppClient.emitChatMessage(
    node: WhatsAppProtocol.Node,
    message: WhatsAppMessage,
) {
    val (sender, fromMe) = resolveChatSender(message)
    cacheNotifyName(node, sender)

    // Revoke/edit/ephemeral consume the message outright.
    if (emitSpecialMessage(message, sender)) return

    rememberInboundPoll(message)
    val displayBody = resolveDisplayBody(message)

    // Poll votes + reactions consume the message outright.
    if (emitPollOrReaction(message, sender, fromMe)) return

    // For group chats, make sure the conversation exists (named + flagged as a
    // group) before we store the message — a freshly joined group isn't in history
    // sync, so otherwise it would only ever be a nameless bare row. Deduped +
    // awaited so the metadata lands before the message's unread bump.
    if (message.from.contains("@g.us")) {
        fetchAndEmitGroupInfo(message.from)
    }

    // Download + decrypt any inline media so it renders in-thread instead of as a
    // bare "[Image]"/"[Video]" placeholder. Null for non-media or on failure.
    val mediaAttachment = message.e2eMessage?.let {
        buildIncomingMediaAttachment(it, message.id)
    }
    val mediaAttachments = listOfNotNull(mediaAttachment)

    // Messages the user sent from another linked device (fromMe) are synced
    // to us as outgoing, not incoming — emit a MessageUpdate so they render on
    // the sent side instead of as an incoming (previously blank) bubble.
    if (fromMe) {
        emitOwnDeviceMessage(message, displayBody, mediaAttachments)
        return
    }

    emitIncomingChat(node, message, sender, displayBody, mediaAttachments)
}

/** Echo of our own message from another device — renders on the sent side. */
private suspend fun WhatsAppClient.emitOwnDeviceMessage(
    message: WhatsAppMessage,
    displayBody: String,
    mediaAttachments: List<com.vayunmathur.communicate.data.whatsapp.MessageAttachment>,
) {
    eventsMutable.emit(WhatsAppEvent.MessageUpdate(
        source = MessageSource.WHATSAPP,
        conversationId = "wa:${message.from}",
        messageId = message.id,
        body = displayBody,
        outgoing = true,
        timestamp = message.timestamp * MS_PER_SECOND,
        senderName = null,
        attachments = mediaAttachments,
    ))
}

/** Poll votes + reactions; true when consumed. */
private suspend fun WhatsAppClient.emitPollOrReaction(
    message: WhatsAppMessage,
    sender: String,
    fromMe: Boolean,
): Boolean {
    // Handle incoming poll votes (PollUpdateMessage): decrypt the selected option
    // hashes, map them back to names, and emit a tally update. Ref whatsmeow
    // DecryptPollVote.
    if (message.e2eMessage?.hasPollUpdateMessage() == true) {
        handlePollVoteMessage(message, sender, fromMe)
        return true
    }

    // Handle reactions separately (Go handleWAMessage reaction case)
    if (message.e2eMessage?.hasReactionMessage() == true) {
        handleReactionMessage(message, sender, fromMe)
        return true
    }
    return false
}

/** Display body with forwarded/view-once/HD markers + mention resolution. */
private suspend fun WhatsAppClient.resolveDisplayBody(message: WhatsAppMessage): String {
    // Prepend forwarded indicator (Go from-whatsapp.go addForwardedFlag)
    return buildString {
        if (message.isViewOnce) append("\u26A0\uFE0F View once: ")
        if (message.isHD) append("[HD] ")
        if (message.isForwarded) {
            append("↷ Forwarded\n")
        }
        append(message.body)
    }.let { resolveMentionsInBody(it, message.mentionedJids) }
}

/** Emit an incoming (not from-me) chat message + delivery receipt. */
private suspend fun WhatsAppClient.emitIncomingChat(
    node: WhatsAppProtocol.Node,
    message: WhatsAppMessage,
    sender: String,
    displayBody: String,
    mediaAttachments: List<com.vayunmathur.communicate.data.whatsapp.MessageAttachment>,
) {
    // In group chats, attribute the message to the actual participant so
    // the UI can show who sent each bubble. `sender` is the participant JID
    // (resolveJID(participant ?: from)); for 1:1 chats it's the peer, so we
    // leave sender fields null and fall back to the conversation peer.
    val isGroupChat = message.from.contains("@g.us")
    eventsMutable.emit(WhatsAppEvent.IncomingMessage(
        source = MessageSource.WHATSAPP,
        conversationId = "wa:${message.from}",
        messageId = message.id,
        body = displayBody,
        peerName = resolveName(sender),
        peerPhone = null,
        timestamp = message.timestamp * MS_PER_SECOND,
        senderName = if (isGroupChat) resolveName(sender) else null,
        senderId = if (isGroupChat) sender else null,
        attachments = mediaAttachments,
        pollQuestion = message.pollData?.takeIf { !it.isPollVote }?.question,
        pollOptions = message.pollData?.takeIf { !it.isPollVote }?.options ?: emptyList(),
    ))

    // Send delivery receipt
    val receiptAttrs = mutableMapOf(
        "id" to message.id,
        "to" to message.from
    )
    node.attrs["participant"]?.let { receiptAttrs["participant"] = it }
    node.attrs["recipient"]?.let { receiptAttrs["recipient"] = it }
    val receipt = WhatsAppProtocol.Node(tag = "receipt", attrs = receiptAttrs)
    webSocket?.send(WhatsAppProtocol.encodeNode(receipt))
}

/** Revoke/edit/ephemeral messages; true when consumed. */
private suspend fun WhatsAppClient.emitSpecialMessage(message: WhatsAppMessage, sender: String): Boolean {
    // Handle revoke (message deletion) from Go handleWAMessage/revoke case
    if (message.isRevoke && message.revokeTargetId != null) {
        eventsMutable.emit(WhatsAppEvent.MessageDeleted(
            source = MessageSource.WHATSAPP,
            conversationId = "wa:${message.from}",
            messageId = message.revokeTargetId,
            timestamp = message.timestamp * MS_PER_SECOND,
        ))
        return true
    }

    // Handle edit from Go handleWAMessage/edit case
    if (message.isEdit && message.editTargetId != null) {
        emitEditMessage(message)
        return true
    }

    // Handle disappearing timer change (Go handleWAMessage/ephemeral case)
    if (message.disappearingTimer != null) {
        eventsMutable.emit(WhatsAppEvent.IncomingMessage(
            source = MessageSource.WHATSAPP,
            conversationId = "wa:${message.from}",
            messageId = message.id,
            body = message.body,
            peerName = resolveName(sender),
            peerPhone = null,
            timestamp = message.timestamp * MS_PER_SECOND,
        ))
        return true
    }
    return false
}

/** Edit message with dedup (Go events.go ConvertEdit meta.Edits check). */
private suspend fun WhatsAppClient.emitEditMessage(message: WhatsAppMessage) {
    // Edit dedup (Go events.go ConvertEdit meta.Edits check)
    if (!processedEditIDs.add(message.id)) {
        Log.debug(TAG, "Ignoring duplicate edit ${message.id}")
        return
    }
    val targetId = message.editTargetId ?: return
    eventsMutable.emit(WhatsAppEvent.MessageEdited(
        source = MessageSource.WHATSAPP,
        conversationId = "wa:${message.from}",
        messageId = targetId,
        newBody = message.body,
        timestamp = message.timestamp * MS_PER_SECOND,
    ))
}

/** Poll vote: decrypt selected hashes, map to names, emit tally. */
private suspend fun WhatsAppClient.handlePollVoteMessage(
    message: WhatsAppMessage,
    sender: String,
    fromMe: Boolean,
) {
    val update = message.e2eMessage?.pollUpdateMessage ?: return
    val key = update.pollCreationMessageKey
    val pollId = key.id
    val voter = if (fromMe) (authData?.wid ?: "") else sender
    val creator = when {
        key.fromMe -> voter
        key.participant.isNotEmpty() -> key.participant
        else -> key.remoteJid
    }
    val secret = loadPollSecret(pollId) ?: return
    val hashes = WhatsAppProtocol.decryptPollVote(update, pollId, creator, voter, secret) ?: return
    val dao = db?.pollOptionDao()
    val names = hashes.mapNotNull { h ->
        val hex = h.joinToString("") { "%02x".format(it) }
        dao?.getByHash(pollId, hex)?.optionName
    }
    eventsMutable.emit(WhatsAppEvent.PollVote(
        source = MessageSource.WHATSAPP,
        conversationId = "wa:${message.from}",
        pollMessageId = pollId,
        voterId = if (fromMe) "self" else sender,
        optionNames = names,
    ))
}

/** Reaction add/remove. */
private suspend fun WhatsAppClient.handleReactionMessage(
    message: WhatsAppMessage,
    sender: String,
    fromMe: Boolean,
) {
    val reaction = message.e2eMessage?.reactionMessage ?: return
    val emoji = reaction.text?.replace("\uFE0F", "") ?: ""
    val targetId = reaction.key?.id ?: ""
    // A reaction the user made on another device echoes back as fromMe;
    // tag its sender "self" so it isn't misattributed to the chat peer.
    val reactorId = if (fromMe) "self" else sender
    if (emoji.isEmpty()) {
        eventsMutable.emit(WhatsAppEvent.ReactionRemoved(
            source = MessageSource.WHATSAPP,
            conversationId = "wa:${message.from}",
            messageId = targetId,
            senderId = reactorId,
        ))
    } else {
        eventsMutable.emit(WhatsAppEvent.ReactionReceived(
            source = MessageSource.WHATSAPP,
            conversationId = "wa:${message.from}",
            messageId = targetId,
            senderId = reactorId,
            emoji = emoji,
        ))
    }
}

/**
 * Handle identity change notification (security code change).
 * From Go handleWAIdentityChange.
 */
internal suspend fun WhatsAppClient.handleIdentityChange(node: WhatsAppProtocol.Node, from: String) {
    val identityNode = node.getChildByTag("identity")
    if (identityNode != null) {
        val jid = node.attrs["participant"] ?: from
        val ts = node.attrs["t"]?.toLongOrNull() ?: (System.currentTimeMillis() / 1000)
        Log.status(TAG, "Identity/security code changed for $jid")
        eventsMutable.emit(WhatsAppEvent.IncomingMessage(
            source = MessageSource.WHATSAPP,
            conversationId = "wa:$from",
            messageId = "idchange-$from-$jid-$ts",
            body = "\uD83D\uDD12 Security code changed for ${resolveName(jid)}",
            peerName = resolveName(jid),
            peerPhone = null,
            timestamp = ts * MS_PER_SECOND,
        ))
    }
}

/**
 * Handle picture update notification (avatar changes).
 * From Go handleWAPictureUpdate.
 */
internal suspend fun WhatsAppClient.handlePictureUpdate(node: WhatsAppProtocol.Node, from: String) {
    val pictureNode = node.getChildByTag("set") ?: node.getChildByTag("delete")
    if (pictureNode != null) {
        val isRemoved = pictureNode.tag == "delete"
        Log.debug(TAG, "Picture ${if (isRemoved) "removed" else "updated"} for $from")
    }
}

/**
 * Handle account sync notification (push name updates).
 * From Go PushNameSetting / PushName events.
 */
internal suspend fun WhatsAppClient.handleAccountSync(node: WhatsAppProtocol.Node) {
    node.content.filterIsInstance<WhatsAppProtocol.Node>().forEach { child ->
        when (child.tag) {
            "push" -> {
                val pushName = child.attrs["name"]
                val jid = child.attrs["jid"] ?: node.attrs["from"]
                if (pushName != null && jid != null) {
                    nameCache[jid] = pushName
                    Log.debug(TAG, "Push name updated: $jid -> $pushName")
                }
            }
            "contact" -> {
                val contactName = child.attrs["name"]
                val jid = child.attrs["jid"] ?: node.attrs["from"]
                if (contactName != null && jid != null) {
                    nameCache[jid] = contactName
                }
            }
        }
    }
}

/**
 * Handle receipt with per-sender batching for group chats.
 * From Go handleWAReceipt — groups messages by sender.
 */
internal suspend fun WhatsAppClient.handleReceipt(node: WhatsAppProtocol.Node) {
    val receiptType = node.attrs["type"]
    // The peer couldn't decrypt a message we sent and wants it re-encrypted. This is the
    // outbound counterpart of sendRetryReceipt and is required for delivery to actually
    // happen when a session is desynced.
    if (receiptType == "retry") {
        handleRetryReceipt(node)
        return
    }
    val isRead = receiptType == "read" || receiptType == "read-self"
    val isDelivered = receiptType == null || receiptType.isEmpty()
    if (!isRead && !isDelivered) return

    val from = node.attrs["from"] ?: return
    val participant = node.attrs["participant"]

    // Reroute LID sender
    val sender = resolveJID(participant ?: from)

    val messageId = node.attrs["id"] ?: return
    val timestamp = (node.attrs["t"]?.toLongOrNull() ?: System.currentTimeMillis() / 1000) * 1000

    // Delivery (type absent/empty) advances our sent message to "delivered" (grey ✓✓); a
    // read/read-self receipt advances to "read" (blue ✓✓). Both are surfaced as ReadReceipt.
    eventsMutable.emit(WhatsAppEvent.ReadReceipt(
        source = MessageSource.WHATSAPP,
        conversationId = "wa:$from",
        messageId = messageId,
        senderId = sender,
        timestamp = timestamp,
        isDelivery = isDelivered,
    ))

    // Handle additional message IDs in list node
    val listNode = node.getChildByTag("list")
    listNode?.content?.filterIsInstance<WhatsAppProtocol.Node>()?.forEach { item ->
        val itemId = item.attrs["id"] ?: return@forEach
        eventsMutable.emit(WhatsAppEvent.ReadReceipt(
            source = MessageSource.WHATSAPP,
            conversationId = "wa:$from",
            messageId = itemId,
            senderId = sender,
            timestamp = timestamp,
            isDelivery = isDelivered,
        ))
    }
}

/**
 * Handle chat presence with media type differentiation.
 * From Go handleWAChatPresence — differentiates text/audio/media typing.
 */
internal suspend fun WhatsAppClient.handleChatPresence(node: WhatsAppProtocol.Node) {
    val from = node.attrs["from"] ?: return
    val participant = node.attrs["participant"]
    val sender = resolveJID(participant ?: from)

    val composingNode = node.getChildByTag("composing")
    val pausedNode = node.getChildByTag("paused")

    val isTyping = composingNode != null
    val mediaAttr = composingNode?.attrs?.get("media")

    eventsMutable.emit(WhatsAppEvent.TypingIndicator(
        source = MessageSource.WHATSAPP,
        conversationId = "wa:$from",
        senderId = sender,
        isTyping = isTyping,
    ))
}
