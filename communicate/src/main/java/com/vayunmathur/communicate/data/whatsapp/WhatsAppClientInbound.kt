package com.vayunmathur.communicate.data.whatsapp

import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient.State
import com.vayunmathur.communicate.data.whatsapp.buildAck
import com.vayunmathur.communicate.data.whatsapp.decodeNode
import com.vayunmathur.communicate.data.whatsapp.decryptPollVote
import com.vayunmathur.communicate.data.whatsapp.encodeNode
import com.vayunmathur.communicate.data.whatsapp.parseMessage
import com.vayunmathur.communicate.data.whatsapp.pollCreation
import android.util.Log
import com.vayunmathur.communicate.data.whatsapp.call.WhatsAppCallManager
import com.vayunmathur.communicate.data.whatsapp.call.WhatsAppCallSignaling
import kotlinx.coroutines.launch

internal fun WhatsAppClient.handleIncomingMessage(data: ByteArray) {
    scope.launch {
        try {
            val node = WhatsAppProtocol.decodeNode(data)
            WhatsAppDiag.log(TAG, "← login stanza ${nodeSummary(node)}")

            // Server ack for an outbound stanza. An ack with an `error` attr means the server
            // REJECTED the message (it will never be delivered) — surface it prominently so a
            // "sent but not delivered" report can be diagnosed from logs.
            if (node.tag == "ack") {
                val ackErr = node.attrs["error"]
                if (ackErr != null) {
                    WhatsAppDiag.log(TAG, "← ack REJECTED class=${node.attrs["class"]} id=${node.attrs["id"]} error=$ackErr")
                }
            }

            // Ack messages, notifications, receipts AND calls (whatsmeow/receipt.go +
            // call.go). Calls MUST be acked too: during the post-login offline flush the server
            // is flow-controlled and head-of-line — if the offline <call> offers aren't acked it
            // stops delivering, so queued <message> stanzas never arrive ("stuck syncing").
            if (node.tag == "message" || node.tag == "notification" ||
                node.tag == "receipt" || node.tag == "call"
            ) {
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

            // Complete any pending IQ request awaiting this response (prekey fetch/upload).
            if (node.tag == "iq") {
                val iqId = node.attrs["id"]
                val pending = if (iqId != null) pendingIqs.remove(iqId) else null
                if (pending != null) {
                    pending.complete(node)
                    return@launch
                }
            }

            // Handle connection failure events (Go handleWALogout, ClientOutdated, TemporaryBan)
            if (node.tag == "failure") {
                val reason = node.attrs["reason"] ?: "unknown"
                WhatsAppDiag.log(TAG, "login <failure> reason=$reason full=${nodeSummary(node)}")
                when (reason) {
                    "401" -> {
                        _state.value = State.Disconnected("Logged out from WhatsApp")
                        scope.launch { WhatsAppAuthData.clear(appContext) }
                        authData = null
                    }
                    "405" -> _state.value = State.Disconnected("Client outdated — update required")
                    "503" -> _state.value = State.Disconnected("Temporarily banned")
                    else -> _state.value = State.Disconnected("Connection failed: $reason")
                }
                return@launch
            }

            if (node.tag == "stream:error") {
                val errorNode = node.content.firstOrNull()
                val code = node.attrs["code"]
                val conflict = node.getChildByTag("conflict")
                val conflictType = conflict?.attrs?.get("type")
                WhatsAppDiag.log(TAG, "login stream:error code=$code child=${errorNode?.tag} conflictType=$conflictType")
                when {
                    // Device removed from the account on the phone, or another session took over.
                    conflict != null || code == "401" -> {
                        suppressReconnect = true
                        if (conflictType == "device_removed" || code == "401") {
                            WhatsAppDiag.log(TAG, "device removed/logged out — clearing credentials")
                            scope.launch { WhatsAppAuthData.clear(appContext) }
                            authData = null
                            _state.value = State.NeedsSetup
                            scope.launch { _events.emit(WhatsAppEvent.SourceLoggedOut(source)) }
                        } else {
                            // "replaced" or other conflict: another WhatsApp session is active.
                            _state.value = State.Disconnected("Session replaced by another device")
                        }
                    }
                    // 515 = restart required (normal after first pair-login); allow reconnect.
                    code == "515" -> _state.value = State.Disconnected("Restart required")
                    else -> _state.value = State.Disconnected("Stream error${if (code != null) " $code" else ""}")
                }
                return@launch
            }

            // Handle the server <success> stanza — the real authentication signal
            // (Go connectionevents.go handleConnectSuccess). Until this arrives the Noise
            // transport is up but the login is not yet authenticated.
            if (node.tag == "success") {
                handleConnectSuccess(node)
                return@launch
            }

            // Handle receipts with per-sender batching (Go handleWAReceipt)
            if (node.tag == "receipt") {
                handleReceipt(node)
                return@launch
            }

            // Handle chat presence with media type differentiation (Go handleWAChatPresence)
            if (node.tag == "chatstate") {
                handleChatPresence(node)
                return@launch
            }

            // Handle notifications (group changes, mute/pin/archive)
            if (node.tag == "notification") {
                handleNotification(node)
                return@launch
            }

            // <ib> info blocks: respond to <dirty> with <clean> so the phone finishes "syncing".
            if (node.tag == "ib") {
                node.getChildByTag("dirty")?.let { handleDirty(it) }
                node.getChildByTag("offline_preview")?.let {
                    WhatsAppDiag.log(TAG, "offline preview: count=${it.attrs["count"]} msg=${it.attrs["message"]} notif=${it.attrs["notification"]} receipt=${it.attrs["receipt"]}")
                }
                node.getChildByTag("offline")?.let {
                    WhatsAppDiag.log(TAG, "offline sync completed: count=${it.attrs["count"]}")
                }
                return@launch
            }

            if (node.tag != "message") return@launch

            // Check for undecryptable messages (Go handleWAUndecryptableMessage)
            val encNode = node.getChildByTag("enc")
            if (encNode?.data == null && node.attrs["type"] == "text") {
                // No ciphertext at all (a decrypt-fail placeholder). Track it AND ask the
                // sender to re-encrypt, otherwise this first-of-session message is lost for
                // good. sendRetryReceipt is self-capped at 5 attempts.
                trackUndecryptable(node)
                sendRetryReceipt(node)
                return@launch
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
                } catch (e: Exception) {
                    Log.e(TAG, "E2E decrypt failed ($encType) from $senderJid", e)
                    null
                }
                if (result == null) decryptFailed = true
                result
            } ?: return@launch

            // Decrypt failure: ask the sender to re-encrypt (Go decryptMessages -> sendRetryReceipt).
            if (decryptFailed) {
                val encTypes = node.getChildren()
                    .filter { it.tag == "enc" }
                    .mapNotNull { it.attrs["type"] }
                WhatsAppDiag.log(TAG, "recv: DECRYPT FAILED id=${node.attrs["id"]} type=${node.attrs["type"]} from=${node.attrs["from"]} encs=$encTypes")
                sendRetryReceipt(node)
                return@launch
            }

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
                return@launch
            }

            // App-state sync keys, shared by our primary as a peer protocolMessage.
            val keyShare = message.e2eMessage
                ?.takeIf { it.hasProtocolMessage() && it.protocolMessage.hasAppStateSyncKeyShare() }
                ?.protocolMessage?.appStateSyncKeyShare
            if (keyShare != null) {
                scope.launch { handleAppStateKeyShare(keyShare) }
                return@launch
            }

            // Process inbound sender-key distribution so future group skmsg decrypt.
            // UNVERIFIED: group sender-key wire mapping not runtime-tested.
            val skdm = message.e2eMessage?.takeIf { it.hasSenderKeyDistributionMessage() }
                ?.senderKeyDistributionMessage
            if (skdm != null && crypto != null) {
                try {
                    crypto.processSenderKeyDistribution(
                        message.from,
                        message.participant ?: message.from,
                        skdm.axolotlSenderKeyDistributionMessage.toByteArray(),
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to process SKDM", e)
                }
                // A SKDM-only message carries no user-visible content (types as "ignore"); once
                // the key is stored there's nothing to display, so stop before emitting a blank
                // bubble. Messages that bundle the SKDM with real content fall through to render.
                if (message.messageType == "ignore") return@launch
            }

            // Skip status broadcasts (Go handleWAMessage status@broadcast check)
            if (message.from.startsWith("status@broadcast")) {
                Log.d(TAG, "Skipping status broadcast from ${message.participant}")
                return@launch
            }

            // Pending message dedup (Go handleWAMessage pendingMessages check)
            if (pendingMessageIDs.remove(message.id)) {
                Log.d(TAG, "Ignoring pending message ${message.id}")
                return@launch
            }

            // LID routing (Go rerouteWAMessage)
            val sender = resolveJID(message.participant ?: message.from)

            // Treat the message as ours when it was sent by our own account from another
            // device — either flagged during parse (DeviceSentMessage / recipient attr) or the
            // sender JID resolves to our own user (the common group case: from=group,
            // participant=ourJid). Otherwise it wrongly renders as an incoming bubble "from"
            // ourselves.
            val fromMe = message.isFromMe || isOwnJid(sender) ||
                isOwnJid(message.participant ?: message.from)

            // Cache push name from notify attr (Go syncGhost / PushName handling)
            val notifyName = node.attrs["notify"]
            if (!notifyName.isNullOrEmpty()) {
                nameCache[sender] = notifyName
            }

            // Handle revoke (message deletion) from Go handleWAMessage/revoke case
            if (message.isRevoke && message.revokeTargetId != null) {
                _events.emit(WhatsAppEvent.MessageDeleted(
                    source = MessageSource.WHATSAPP,
                    conversationId = "wa:${message.from}",
                    messageId = message.revokeTargetId,
                    timestamp = message.timestamp * 1000,
                ))
                return@launch
            }

            // Handle edit from Go handleWAMessage/edit case
            if (message.isEdit && message.editTargetId != null) {
                // Edit dedup (Go events.go ConvertEdit meta.Edits check)
                if (!processedEditIDs.add(message.id)) {
                    Log.d(TAG, "Ignoring duplicate edit ${message.id}")
                    return@launch
                }
                _events.emit(WhatsAppEvent.MessageEdited(
                    source = MessageSource.WHATSAPP,
                    conversationId = "wa:${message.from}",
                    messageId = message.editTargetId,
                    newBody = message.body,
                    timestamp = message.timestamp * 1000,
                ))
                return@launch
            }

            // Handle disappearing timer change (Go handleWAMessage/ephemeral case)
            if (message.disappearingTimer != null) {
                _events.emit(WhatsAppEvent.IncomingMessage(
                    source = MessageSource.WHATSAPP,
                    conversationId = "wa:${message.from}",
                    messageId = message.id,
                    body = message.body,
                    peerName = resolveName(sender),
                    peerPhone = null,
                    timestamp = message.timestamp * 1000,
                ))
                return@launch
            }

            // Handle poll creation — store option hashes (Go handleWAMessage/poll case)
            if (message.pollData != null && !message.pollData.isPollVote) {
                storePollOptions(message.id, message.pollData.options)
            }

            // Prepend forwarded indicator (Go from-whatsapp.go addForwardedFlag)
            val displayBody = buildString {
                if (message.isViewOnce) append("\u26A0\uFE0F View once: ")
                if (message.isHD) append("[HD] ")
                if (message.isForwarded) {
                    append("↷ Forwarded\n")
                }
                append(message.body)
            }.let { resolveMentionsInBody(it, message.mentionedJids) }

            // Store poll secrets from incoming polls for vote encryption/decryption.
            if (message.pollData != null && !message.pollData.isPollVote && message.e2eMessage != null) {
                val secret = message.e2eMessage.messageContextInfo?.messageSecret?.toByteArray()
                if (secret != null) storePollSecret(message.id, secret)
            }

            // Handle incoming poll votes (PollUpdateMessage): decrypt the selected option
            // hashes, map them back to names, and emit a tally update. Ref whatsmeow
            // DecryptPollVote.
            if (message.e2eMessage?.hasPollUpdateMessage() == true) {
                val update = message.e2eMessage.pollUpdateMessage
                val key = update.pollCreationMessageKey
                val pollId = key.id
                val voter = if (fromMe) (authData?.wid ?: "") else sender
                val creator = when {
                    key.fromMe -> voter
                    key.participant.isNotEmpty() -> key.participant
                    else -> key.remoteJid
                }
                val secret = loadPollSecret(pollId)
                if (secret != null) {
                    val hashes = WhatsAppProtocol.decryptPollVote(update, pollId, creator, voter, secret)
                    if (hashes != null) {
                        val dao = db?.pollOptionDao()
                        val names = hashes.mapNotNull { h ->
                            val hex = h.joinToString("") { "%02x".format(it) }
                            dao?.getByHash(pollId, hex)?.optionName
                        }
                        _events.emit(WhatsAppEvent.PollVote(
                            source = MessageSource.WHATSAPP,
                            conversationId = "wa:${message.from}",
                            pollMessageId = pollId,
                            voterId = if (fromMe) "self" else sender,
                            optionNames = names,
                        ))
                    }
                }
                return@launch
            }

            // Handle reactions separately (Go handleWAMessage reaction case)
            if (message.e2eMessage?.hasReactionMessage() == true) {
                val reaction = message.e2eMessage.reactionMessage
                val emoji = reaction.text?.replace("\uFE0F", "") ?: ""
                val targetId = reaction.key?.id ?: ""
                // A reaction the user made on another device echoes back as fromMe;
                // tag its sender "self" so it isn't misattributed to the chat peer.
                val reactorId = if (fromMe) "self" else sender
                if (emoji.isEmpty()) {
                    _events.emit(WhatsAppEvent.ReactionRemoved(
                        source = MessageSource.WHATSAPP,
                        conversationId = "wa:${message.from}",
                        messageId = targetId,
                        senderId = reactorId,
                    ))
                } else {
                    _events.emit(WhatsAppEvent.ReactionReceived(
                        source = MessageSource.WHATSAPP,
                        conversationId = "wa:${message.from}",
                        messageId = targetId,
                        senderId = reactorId,
                        emoji = emoji,
                    ))
                }
                return@launch
            }

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
                _events.emit(WhatsAppEvent.MessageUpdate(
                    source = MessageSource.WHATSAPP,
                    conversationId = "wa:${message.from}",
                    messageId = message.id,
                    body = displayBody,
                    outgoing = true,
                    timestamp = message.timestamp * 1000,
                    senderName = null,
                    attachments = mediaAttachments,
                ))
                return@launch
            }

            // In group chats, attribute the message to the actual participant so
            // the UI can show who sent each bubble. `sender` is the participant JID
            // (resolveJID(participant ?: from)); for 1:1 chats it's the peer, so we
            // leave sender fields null and fall back to the conversation peer.
            val isGroupChat = message.from.contains("@g.us")
            _events.emit(WhatsAppEvent.IncomingMessage(
                source = MessageSource.WHATSAPP,
                conversationId = "wa:${message.from}",
                messageId = message.id,
                body = displayBody,
                peerName = resolveName(sender),
                peerPhone = null,
                timestamp = message.timestamp * 1000,
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

        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle incoming message", e)
        }
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
        Log.i(TAG, "Identity/security code changed for $jid")
        _events.emit(WhatsAppEvent.IncomingMessage(
            source = MessageSource.WHATSAPP,
            conversationId = "wa:$from",
            messageId = "idchange-$from-$jid-$ts",
            body = "\uD83D\uDD12 Security code changed for ${resolveName(jid)}",
            peerName = resolveName(jid),
            peerPhone = null,
            timestamp = ts * 1000,
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
        Log.d(TAG, "Picture ${if (isRemoved) "removed" else "updated"} for $from")
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
                    Log.d(TAG, "Push name updated: $jid -> $pushName")
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
    _events.emit(WhatsAppEvent.ReadReceipt(
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
        _events.emit(WhatsAppEvent.ReadReceipt(
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

    _events.emit(WhatsAppEvent.TypingIndicator(
        source = MessageSource.WHATSAPP,
        conversationId = "wa:$from",
        senderId = sender,
        isTyping = isTyping,
    ))
}

/**
 * Handle notification events (group changes, mute, pin, archive).
 * From Go handleWAGroupInfoChange, handleWAMute, handleWAArchive, handleWAPin.
 */
internal suspend fun WhatsAppClient.handleNotification(node: WhatsAppProtocol.Node) {
    val notifType = node.attrs["type"] ?: return
    val from = node.attrs["from"] ?: return

    when (notifType) {
        "w:gp2" -> handleGroupNotification(node, from)
        "server_sync" -> handleServerSync(node, from)
        "call" -> handleCallNotification(node, from)
        "encrypt" -> handleIdentityChange(node, from)
        "picture" -> handlePictureUpdate(node, from)
        "account_sync" -> handleAccountSync(node)
        "devices" -> {} // Device list update — Go ignores silently
        "mediaretry" -> {} // Media retry — Go handles but requires bridge infra
    }
}

/**
 * Handle group info change notifications.
 * From Go handleWAGroupInfoChange.
 */
internal suspend fun WhatsAppClient.handleGroupNotification(node: WhatsAppProtocol.Node, groupJid: String) {
    val timestamp = (node.attrs["t"]?.toLongOrNull() ?: System.currentTimeMillis() / 1000) * 1000
    val actor = resolveName(node.attrs["participant"] ?: groupJid)
    val msgId = node.attrs["id"] ?: ""

    // Any group notification (being added, subject/participant changes, …) implies the
    // group should exist locally. Fetch + publish its metadata so the conversation appears
    // named and flagged as a group even when we were just added and have no history for it.
    fetchAndEmitGroupInfo(groupJid)

    node.content.filterIsInstance<WhatsAppProtocol.Node>().forEach { child ->
        val body = when (child.tag) {
            "subject" -> {
                val newName = child.attrs["subject"] ?: child.data?.let { String(it, Charsets.UTF_8) } ?: return@forEach
                "[Group name changed to: $newName]"
            }
            "description" -> {
                val newDesc = child.data?.let { String(it, Charsets.UTF_8) } ?: ""
                "[Group description changed: $newDesc]"
            }
            "add", "remove", "promote", "demote" -> {
                val participants = child.content.filterIsInstance<WhatsAppProtocol.Node>()
                    .mapNotNull { it.attrs["jid"] }
                "[Group: ${participants.joinToString()} ${child.tag}ed]"
            }
            // Go wrapGroupInfoChange: ephemeral setting
            "ephemeral" -> {
                val expiration = child.attrs["expiration"]?.toLongOrNull() ?: 0
                if (expiration > 0) {
                    val duration = when {
                        expiration >= 86400 * 7 -> "${expiration / (86400 * 7)} week(s)"
                        expiration >= 86400 -> "${expiration / 86400} day(s)"
                        expiration >= 3600 -> "${expiration / 3600} hour(s)"
                        else -> "$expiration seconds"
                    }
                    "[Disappearing messages set to $duration]"
                } else {
                    "[Disappearing messages turned off]"
                }
            }
            // Go wrapGroupInfoChange: announce mode
            "announce" -> {
                val isAnnounce = child.attrs["announce"] == "true" || child.attrs["value"] == "on"
                if (isAnnounce) "[Only admins can send messages now]"
                else "[All participants can send messages now]"
            }
            // Go wrapGroupInfoChange: locked (restrict edit to admins)
            "locked" -> {
                val isLocked = child.attrs["locked"] == "true" || child.attrs["value"] == "on"
                if (isLocked) "[Only admins can edit group info now]"
                else "[All participants can edit group info now]"
            }
            // Go wrapGroupInfoChange: link/unlink community
            "link" -> {
                val linkedGroup = child.attrs["link_type"]
                "[Group linked: $linkedGroup]"
            }
            "unlink" -> {
                val unlinkedGroup = child.attrs["unlink_type"]
                "[Group unlinked: $unlinkedGroup]"
            }
            else -> null
        }

        if (body != null) {
            _events.emit(WhatsAppEvent.IncomingMessage(
                source = MessageSource.WHATSAPP,
                conversationId = "wa:$groupJid",
                messageId = msgId,
                body = body,
                peerName = actor,
                peerPhone = null,
                timestamp = timestamp,
            ))
        }
    }
}

/**
 * Respond to a server <dirty> info block with <clean> (MarkNotDirty) so the primary phone
 * stops showing "syncing / keep WhatsApp open". Ref whatsmeow appstate.go MarkNotDirty.
 * <iq to=s.whatsapp.net type=set xmlns=urn:xmpp:whatsapp:dirty><clean type=.. timestamp=../></iq>
 */
internal suspend fun WhatsAppClient.handleDirty(dirty: WhatsAppProtocol.Node) {
    val type = dirty.attrs["type"] ?: return
    val ts = dirty.attrs["timestamp"]
    // account_sync dirty is cleared automatically by the server once we've synced; whatsmeow
    // only explicitly cleans non-account_sync types, but cleaning is harmless and idempotent.
    val cleanAttrs = mutableMapOf("type" to type)
    if (ts != null) cleanAttrs["timestamp"] = ts
    val iq = WhatsAppProtocol.Node(
        tag = "iq",
        attrs = mapOf(
            "id" to generateMessageId(), "type" to "set",
            "xmlns" to "urn:xmpp:whatsapp:dirty", "to" to "s.whatsapp.net",
        ),
        content = listOf(WhatsAppProtocol.Node(tag = "clean", attrs = cleanAttrs)),
    )
    webSocket?.send(WhatsAppProtocol.encodeNode(iq))
    WhatsAppDiag.log(TAG, "cleaned dirty state: $type")
}

internal suspend fun WhatsAppClient.handleServerSync(node: WhatsAppProtocol.Node, from: String) {
    node.getChildren().filter { it.tag == "collection" }.forEach { coll ->
        val name = coll.attrs["name"] ?: return@forEach
        if (name in APP_STATE_COLLECTIONS) {
            scope.launch { fetchAppStateCollection(name, fullSync = appStateVersion(name) == 0L) }
        }
    }
}

/**
 * Handle incoming call notification.
 * From Go handleWACallStart.
 */
internal suspend fun WhatsAppClient.handleCallNotification(node: WhatsAppProtocol.Node, from: String) {
    // Route the typed stanza to the call state machine (offer/preaccept/accept/reject/
    // terminate/relay). This drives ringing + WebRTC media (client-to-client).
    WhatsAppCallSignaling.parse(node)?.let { WhatsAppCallManager.onInbound(it) }

    val offer = node.getChildByTag("offer")
    if (offer != null) {
        // Go handleWACallStart: ignore calls older than 15 minutes
        val callTimestamp = node.attrs["t"]?.toLongOrNull() ?: (System.currentTimeMillis() / 1000)
        val ageSeconds = (System.currentTimeMillis() / 1000) - callTimestamp
        if (ageSeconds > 15 * 60) {
            Log.d(TAG, "Ignoring old call notification (${ageSeconds}s old)")
            return
        }

        val caller = node.attrs["participant"] ?: from
        val callId = node.attrs["id"] ?: ""
        val callType = offer.attrs["call-type"] ?: "voice"
        val callLabel = when {
            callType.contains("video") -> "video call"
            callType.contains("group") -> "group call"
            else -> "voice call"
        }
        _events.emit(WhatsAppEvent.IncomingMessage(
            source = MessageSource.WHATSAPP,
            conversationId = "wa:$from",
            messageId = "call-$callId",
            body = "\u260E Incoming $callLabel",
            peerName = resolveName(caller),
            peerPhone = null,
            timestamp = callTimestamp * 1000,
        ))
    }
}
