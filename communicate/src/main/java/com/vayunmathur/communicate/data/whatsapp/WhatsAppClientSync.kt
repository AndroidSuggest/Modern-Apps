package com.vayunmathur.communicate.data.whatsapp

import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppAppStateProto
import com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto
import kotlinx.coroutines.launch

internal fun WhatsAppClient.appStateKeyId64(keyId: ByteArray) = Base64.encodeToString(keyId, Base64.NO_WRAP)
internal fun WhatsAppClient.storeAppStateKey(keyId: ByteArray, keyData: ByteArray) {
    appStatePrefs.edit { putString("key_${appStateKeyId64(keyId)}", Base64.encodeToString(keyData, Base64.NO_WRAP)) }
}
internal fun WhatsAppClient.getAppStateKey(keyId: ByteArray): ByteArray? {
    val s = appStatePrefs.getString("key_${appStateKeyId64(keyId)}", null) ?: return null
    return try { Base64.decode(s, Base64.NO_WRAP) } catch (e: Exception) { null }
}
internal fun WhatsAppClient.appStateVersion(name: String): Long = appStatePrefs.getLong("ver_$name", 0L)
internal fun WhatsAppClient.setAppStateVersion(name: String, v: Long) { appStatePrefs.edit { putLong("ver_$name", v) } }

/** Store app-state sync keys shared by our primary, then fetch all collections. */
internal suspend fun WhatsAppClient.handleAppStateKeyShare(share: WhatsAppE2EProto.AppStateSyncKeyShare) {
    var stored = 0
    for (k in share.keysList) {
        val id = k.keyId.keyId.toByteArray()
        val data = k.keyData.keyData.toByteArray()
        if (id.isNotEmpty() && data.isNotEmpty()) { storeAppStateKey(id, data); stored++ }
    }
    WhatsAppDiag.log(TAG, "app-state: stored $stored sync key(s); fetching collections")
    for (name in APP_STATE_COLLECTIONS) {
        fetchAppStateCollection(name, fullSync = appStateVersion(name) == 0L)
    }
}

/**
 * Fetch + decode + apply one app-state collection (snapshot then incremental patches).
 * Ref whatsmeow appstate.go fetchAppState. MAC/LTHash verification is skipped.
 */
internal suspend fun WhatsAppClient.fetchAppStateCollection(name: String, fullSync: Boolean) {
    if (!appStateCollectionsFetching.add(name)) return
    try {
        var version = if (fullSync) 0L else appStateVersion(name)
        var wantSnapshot = fullSync || version == 0L
        var more = true
        var guard = 0
        while (more && guard++ < 12) {
            val collAttrs = mutableMapOf("name" to name, "return_snapshot" to wantSnapshot.toString())
            if (!wantSnapshot) collAttrs["version"] = version.toString()
            val iq = WhatsAppProtocol.Node(
                tag = "iq",
                attrs = mapOf(
                    "id" to generateMessageId(), "type" to "set",
                    "xmlns" to "w:sync:app:state", "to" to "s.whatsapp.net",
                ),
                content = listOf(
                    WhatsAppProtocol.Node(
                        tag = "sync",
                        content = listOf(WhatsAppProtocol.Node(tag = "collection", attrs = collAttrs)),
                    )
                ),
            )
            val resp = sendIqAndWait(iq) ?: break
            val coll = resp.getChildByTag("sync")?.getChildByTag("collection") ?: break

            val snapNode = coll.getChildByTag("snapshot")
            var recCount = 0
            snapNode?.data?.let { snapData ->
                val ext = WhatsAppAppStateProto.ExternalBlobReference.parseFrom(snapData)
                val blob = downloadAppStateBlob(ext)
                if (blob == null) {
                    WhatsAppDiag.log(TAG, "app-state $name: snapshot blob download FAILED (path=${ext.directPath.take(40)})")
                } else {
                    val snap = WhatsAppAppStateProto.SyncdSnapshot.parseFrom(blob)
                    recCount = snap.recordsCount
                    for (rec in snap.recordsList) applyAppStateRecord(name, rec, isSet = true)
                    if (snap.hasVersion()) version = snap.version.version
                }
            }
            val patchNodes = coll.getChildByTag("patches")?.getChildren()?.filter { it.tag == "patch" } ?: emptyList()
            patchNodes.forEach { p ->
                p.data?.let { pd ->
                    val patch = WhatsAppAppStateProto.SyncdPatch.parseFrom(pd)
                    val muts = if (patch.hasExternalMutations()) {
                        downloadAppStateBlob(patch.externalMutations)?.let {
                            WhatsAppAppStateProto.SyncdMutations.parseFrom(it).mutationsList
                        } ?: emptyList()
                    } else patch.mutationsList
                    for (m in muts) applyAppStateRecord(
                        name, m.record,
                        isSet = m.operation == WhatsAppAppStateProto.SyncdMutation.SyncdOperation.SET,
                    )
                    if (patch.hasVersion()) version = patch.version.version
                }
            }
            WhatsAppDiag.log(TAG, "app-state $name: snapshot=${snapNode != null} records=$recCount patches=${patchNodes.size} more=${coll.attrs["has_more_patches"]} -> v$version")
            more = coll.attrs["has_more_patches"] == "true"
            wantSnapshot = false
        }
        setAppStateVersion(name, version)
        WhatsAppDiag.log(TAG, "app-state: $name synced to v$version")
    } catch (e: Exception) {
        WhatsAppDiag.log(TAG, "app-state $name failed: ${e.javaClass.simpleName}: ${e.message}")
    } finally {
        appStateCollectionsFetching.remove(name)
    }
}

/** Download + decrypt an app-state external blob (snapshot/mutations) via the media CDN. */
internal suspend fun WhatsAppClient.downloadAppStateBlob(ext: WhatsAppAppStateProto.ExternalBlobReference): ByteArray? {
    val directPath = ext.directPath
    if (directPath.isEmpty()) return null
    val host = mediaConn()?.first ?: return null
    val hash = Base64.encodeToString(ext.fileEncSha256.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
    val url = "https://$host$directPath&hash=$hash&mms-type=md-app-state&__wa-mms="
    return downloadMedia(url, ext.mediaKey.toByteArray(), WhatsAppProtocol.MEDIA_KEY_APP_STATE)
}

/** Decrypt one app-state record and apply it (contact names, mute/pin/archive). */
internal suspend fun WhatsAppClient.applyAppStateRecord(
    collection: String,
    record: WhatsAppAppStateProto.SyncdRecord,
    isSet: Boolean,
) {
    if (!isSet) return
    val keyId = record.keyId.id.toByteArray()
    val keyData = getAppStateKey(keyId) ?: run {
        requestAppStateKey(keyId); return
    }
    val expanded = WhatsAppProtocol.expandAppStateKeys(keyData)
    val plain = WhatsAppProtocol.decryptAppStateValue(record.value.blob.toByteArray(), expanded[1]) ?: return
    val sad = try { WhatsAppAppStateProto.SyncActionData.parseFrom(plain) } catch (e: Exception) { return }
    val index = try { org.json.JSONArray(String(sad.index.toByteArray(), Charsets.UTF_8)) } catch (e: Exception) { return }
    if (index.length() == 0) return
    val action = index.optString(0)
    val jid = if (index.length() > 1) index.optString(1) else ""
    val value = sad.value
    when (action) {
        "contact" -> {
            val name = value.contactAction.fullName.ifEmpty { value.contactAction.firstName }
            if (name.isNotEmpty() && jid.isNotEmpty()) {
                // Cache name for live messages / future conversations. We do NOT emit a
                // ConversationUpdate here because the consumer upserts (would create empty rows
                // for every contact). History rows already use device-contact lookup for names.
                nameCache[resolveJID(jid)] = name
                nameCache[jid] = name
            }
        }
        "mute" -> if (jid.isNotEmpty()) db?.conversationDao()?.updateMuteEndTime(jid, value.muteAction.muteEndTimestamp)
        "pin_v1" -> if (jid.isNotEmpty()) db?.conversationDao()?.updatePinned(jid, value.pinAction.pinned)
        "archive" -> if (jid.isNotEmpty()) db?.conversationDao()?.updateArchived(jid, value.archiveChatAction.archived)
        "markChatAsRead" -> if (jid.isNotEmpty()) db?.conversationDao()?.updateMarkedAsUnread(jid, !value.markChatAsReadAction.read)
    }
}

/** Request a missing app-state sync key from our primary (deduped). */
internal suspend fun WhatsAppClient.requestAppStateKey(keyId: ByteArray) {
    val id64 = appStateKeyId64(keyId)
    if (!appStateKeyRequested.add(id64)) return
    val auth = authData ?: return
    try {
        val req = WhatsAppE2EProto.AppStateSyncKeyRequest.newBuilder()
            .addKeyIds(
                WhatsAppE2EProto.AppStateSyncKeyId.newBuilder()
                    .setKeyId(com.google.protobuf.ByteString.copyFrom(keyId))
            )
        val proto = WhatsAppE2EProto.ProtocolMessage.newBuilder()
            .setType(WhatsAppE2EProto.ProtocolMessage.Type.APP_STATE_SYNC_KEY_REQUEST)
            .setAppStateSyncKeyRequest(req)
        val msg = WhatsAppE2EProto.Message.newBuilder().setProtocolMessage(proto).build()
        val ownUser = auth.wid.substringBefore("@").substringBefore(":").substringBefore(".")
        val node = buildEncryptedMessageNode("$ownUser@s.whatsapp.net", generateMessageId(), msg, "text") ?: return
        webSocket?.send(WhatsAppProtocol.encodeNode(node))
        WhatsAppDiag.log(TAG, "app-state: requested missing key $id64")
    } catch (e: Exception) {
        WhatsAppDiag.log(TAG, "app-state key request failed: ${e.message}")
    }
}

/**
 * Send a retry receipt asking the sender to re-encrypt an undecryptable message.
 * Ref whatsmeow retry.go sendRetryReceipt. Retries are capped at 5; the identity/prekey <keys>
 * node is included from the 2nd retry onward.
 */
internal suspend fun WhatsAppClient.sendRetryReceipt(node: WhatsAppProtocol.Node) {
    val auth = authData ?: return
    val crypto = ensureE2E(auth) ?: return
    val ws = webSocket ?: return
    val msgId = node.attrs["id"] ?: return
    val count = undecryptableTracker.merge("retry:$msgId", 1) { a, b -> a + b } ?: 1
    if (count > 5) {
        Log.w(TAG, "Not sending more retry receipts for $msgId")
        return
    }
    val keysNode = if (count > 1) {
        try { crypto.buildRetryReceiptKeysNode(accountDeviceIdentity()) } catch (e: Exception) {
            Log.w(TAG, "Failed to build retry keys node", e); null
        }
    } else null
    val receipt = WhatsAppProtocol.buildRetryReceipt(node, auth.registrationId, count, keysNode)
    ws.send(WhatsAppProtocol.encodeNode(receipt))
    Log.d(TAG, "Sent retry receipt #$count for $msgId")
}

/**
 * Handle an inbound <receipt type="retry">: the peer failed to decrypt a message we sent and
 * is asking us to re-encrypt and resend it. Rebuild the session from the fresh keys included
 * in the receipt (present from the 2nd retry), then re-encrypt the cached plaintext for just
 * the requesting device and resend with the same message id. Ref whatsmeow retry.go
 * handleRetryReceipt. 1:1 only — group skmsg resends are not cached.
 */
internal suspend fun WhatsAppClient.handleRetryReceipt(node: WhatsAppProtocol.Node) {
    val auth = authData ?: return
    val crypto = ensureE2E(auth) ?: return
    val ws = webSocket ?: return
    val msgId = node.attrs["id"] ?: return
    val from = node.attrs["from"] ?: return
    // The specific device that couldn't decrypt: participant if present, else the chat peer.
    val deviceJid = node.attrs["participant"] ?: from
    if (deviceJid.contains("@g.us")) {
        WhatsAppDiag.log(TAG, "retry: ignoring group retry for $msgId (not cached)")
        return
    }
    val cached = recentSentDMs[msgId] ?: run {
        WhatsAppDiag.log(TAG, "retry: no cached message for $msgId; cannot resend")
        return
    }
    val resendKey = "$msgId|$deviceJid"
    val resendCount = retryResendCounts.merge(resendKey, 1) { a, b -> a + b } ?: 1
    if (resendCount > 5) {
        WhatsAppDiag.log(TAG, "retry: giving up on $msgId for $deviceJid after $resendCount attempts")
        return
    }

    // Rebuild the session from the keys the peer attached (identity + prekeys), if any.
    val deviceNum = deviceJid.substringBefore("@").substringAfter(":", "0").toIntOrNull() ?: 0
    if (node.getChildByTag("keys") != null) {
        try {
            // parsePreKeyBundleNode reads <registration> + <keys> from the node it is given.
            val bundle = crypto.parsePreKeyBundleNode(deviceNum, node)
            if (bundle != null) {
                crypto.deleteSession(deviceJid)
                crypto.processPreKeyBundle(deviceJid, bundle)
                WhatsAppDiag.log(TAG, "retry: rebuilt session for $deviceJid from receipt keys")
            }
        } catch (e: Exception) {
            WhatsAppDiag.log(TAG, "retry: failed to process receipt keys for $deviceJid: ${e.message}")
        }
    }
    if (!ensureSession(deviceJid)) {
        WhatsAppDiag.log(TAG, "retry: no session for $deviceJid; cannot resend $msgId")
        return
    }

    val ownUser = auth.wid.substringBefore("@").substringBefore(":").substringBefore(".")
    val devUser = deviceJid.substringBefore("@").substringBefore(":").substringBefore(".")
    val plaintext = if (devUser == ownUser && cached.dsmPlaintextPadded != null)
        cached.dsmPlaintextPadded else cached.msgPlaintextPadded
    val enc = try {
        crypto.encryptDM(deviceJid, plaintext)
    } catch (e: Exception) {
        WhatsAppDiag.log(TAG, "retry: re-encrypt failed for $deviceJid: ${e.message}")
        return
    }
    val includeIdentity = enc.type == "pkmsg"
    val resend = WhatsAppProtocol.buildFanOutMessageNode(
        to = cached.to,
        id = msgId,
        type = cached.type,
        participantEncs = listOf(WhatsAppProtocol.ParticipantEnc(deviceJid, enc.type, enc.data)),
        includeDeviceIdentity = includeIdentity,
        deviceIdentity = if (includeIdentity) accountDeviceIdentity() else null,
    )
    val sent = ws.send(WhatsAppProtocol.encodeNode(resend))
    WhatsAppDiag.log(TAG, "retry: resent $msgId to $deviceJid (attempt $resendCount type=${enc.type}) sent=$sent")
}

/**
 * Store poll option hashes for later vote resolution.
 * From Go wadb.PollOption.
 */
internal suspend fun WhatsAppClient.storePollOptions(messageId: String, options: List<String>) {
    val dao = db?.pollOptionDao() ?: return
    val pollOptions = options.map { option ->
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(option.toByteArray(Charsets.UTF_8))
        WhatsAppPollOption(
            msgId = messageId,
            optionHash = hash.joinToString("") { "%02x".format(it) },
            optionName = option,
        )
    }
    dao.upsertAll(pollOptions)
}

/** Persist a poll's shared secret (in-memory + DB) so votes can be encrypted/decrypted later. */
internal suspend fun WhatsAppClient.storePollSecret(msgId: String, secret: ByteArray) {
    pollSecrets[msgId] = secret
    db?.pollSecretDao()?.upsert(
        WhatsAppPollSecret(msgId, secret.joinToString("") { "%02x".format(it) })
    )
}

internal suspend fun WhatsAppClient.loadPollSecret(msgId: String): ByteArray? {
    pollSecrets[msgId]?.let { return it }
    val hex = db?.pollSecretDao()?.get(msgId) ?: return null
    return runCatching {
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }.getOrNull()?.also { pollSecrets[msgId] = it }
}

internal fun WhatsAppClient.kickoffBackfill() {
    // History is PUSHED by the phone after linking as encrypted <message> stanzas carrying a
    // protocolMessage.historySyncNotification (gated by DeviceProps.HistorySyncConfig sent at
    // pairing). There is no w:web "initial" request in the multidevice protocol, so we just
    // wait for those messages here. Ref whatsmeow appstate/history sync.
    WhatsAppDiag.log(TAG, "awaiting pushed history sync messages from phone")
}

/**
 * Download (or read inline), decompress and parse a HistorySync blob, then emit the contained
 * messages as backfill. Ref whatsmeow message.go DownloadHistorySync + download.go.
 */
internal suspend fun WhatsAppClient.handleHistorySync(
    notif: WhatsAppE2EProto.HistorySyncNotification,
    msgId: String,
) {
    try {
        val raw: ByteArray = if (notif.hasInitialHistBootstrapInlinePayload() &&
            !notif.initialHistBootstrapInlinePayload.isEmpty
        ) {
            // Initial bootstrap chunk is inlined in the notification (DeviceProps requested it).
            notif.initialHistBootstrapInlinePayload.toByteArray()
        } else {
            val host = mediaConn()?.first ?: run {
                WhatsAppDiag.log(TAG, "history sync: no media host"); sendHistorySyncReceipt(msgId); return
            }
            val directPath = notif.directPath
            if (directPath.isEmpty()) { WhatsAppDiag.log(TAG, "history sync: no directPath"); sendHistorySyncReceipt(msgId); return }
            val hash = Base64.encodeToString(
                notif.fileEncSha256.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP
            )
            val url = "https://$host$directPath&hash=$hash&mms-type=md-msg-hist&__wa-mms="
            downloadMedia(url, notif.mediaKey.toByteArray(), WhatsAppProtocol.MEDIA_KEY_HISTORY)
                ?: run { WhatsAppDiag.log(TAG, "history sync: download failed"); sendHistorySyncReceipt(msgId); return }
        }

        val inflated = inflateZlib(raw)
        val hs = WhatsAppE2EProto.HistorySync.parseFrom(inflated)
        // Populate LID->phone mappings so conversations addressed by LID resolve to a phone JID.
        for (m in hs.phoneNumberToLidMappingsList) {
            if (m.lidJid.isNotEmpty() && m.pnJid.isNotEmpty()) lidToPhoneMap[m.lidJid] = m.pnJid
        }
        WhatsAppDiag.log(
            TAG,
            "history sync: type=${hs.syncType} chunk=${hs.chunkOrder} conversations=${hs.conversationsCount} lidMaps=${hs.phoneNumberToLidMappingsCount} (blob=${raw.size}B inflated=${inflated.size}B)",
        )

        // Backfill continues (paginates older messages) from the initial bootstrap and from
        // each on-demand response, so full history is pulled page by page. RECENT/FULL/etc are
        // terminal pushes and don't trigger further requests.
        val requestMore = fullHistorySync &&
            (hs.syncType == SYNC_INITIAL_BOOTSTRAP || hs.syncType == SYNC_ON_DEMAND)
        var emitted = 0
        for (conv in hs.conversationsList) {
            val chatJid = conv.newJid.ifEmpty { conv.id }
            WhatsAppDiag.log(TAG, "  conv '${conv.name}' ${chatJid} msgs=${conv.messagesCount}")
            if (chatJid.isEmpty() || chatJid.startsWith("status@broadcast")) continue
            emitted += emitHistoryConversation(conv, chatJid, requestMore)
        }
        WhatsAppDiag.log(TAG, "history sync: emitted $emitted message(s)")
    } catch (e: Exception) {
        WhatsAppDiag.log(TAG, "history sync failed: ${e.javaClass.simpleName}: ${e.message}")
        Log.e(TAG, "history sync failed", e)
    }
    // Acknowledge the chunk so the phone advances to the next one and finishes "syncing".
    sendHistorySyncReceipt(msgId)
}

/**
 * Send the history-sync receipt so the phone stops waiting ("syncing, keep WhatsApp open") and
 * sends the next chunk. Ref whatsmeow SendProtocolMessageReceipt(id, ReceiptTypeHistorySync).
 * <receipt id="{notifMsgId}" type="hist_sync" to="{ownUserJID}"/>
 */
internal fun WhatsAppClient.sendHistorySyncReceipt(msgId: String) {
    if (msgId.isEmpty()) return
    val ownUser = (authData?.wid ?: return).substringBefore("@").substringBefore(":").substringBefore(".")
    if (ownUser.isEmpty()) return
    val receipt = WhatsAppProtocol.Node(
        tag = "receipt",
        attrs = mapOf("id" to msgId, "type" to "hist_sync", "to" to "$ownUser@s.whatsapp.net"),
    )
    webSocket?.send(WhatsAppProtocol.encodeNode(receipt))
    WhatsAppDiag.log(TAG, "history sync: sent hist_sync receipt for $msgId")
}

/**
 * Backfill one conversation: emit a MessageUpdate (outgoing-aware) for each message with a
 * body, then a ConversationUpdate so the chat row appears with its last preview. Returns the
 * number of messages emitted. MessageUpdate is the backfill path (no notifications); IncomingMessage
 * is reserved for live messages.
 */
internal suspend fun WhatsAppClient.emitHistoryConversation(
    conv: WhatsAppE2EProto.HsConversation,
    rawChatJid: String,
    requestMore: Boolean,
): Int {
    // Resolve LID-addressed chats to a phone JID so they match live conversations and have a
    // displayable number/name (instead of "unknown").
    val chatJid = resolveJID(rawChatJid)
    val convId = "wa:$chatJid"
    // E.164 (with +) so device-contact lookup (ContactsContract.PhoneLookup) matches.
    val phone = if (chatJid.endsWith("@s.whatsapp.net"))
        "+" + chatJid.substringBefore("@").substringBefore(":").substringBefore(".") else null
    val contactName = resolveDeviceContactName(phone)
    // Collect displayable messages first so we can register the conversation row BEFORE the
    // messages (the message table has a FK to the conversation).
    data class HMsg(val id: String, val body: String, val outgoing: Boolean, val ts: Long, val sender: String?)
    val msgs = ArrayList<HMsg>()
    var lastBody = ""
    var lastTs = conv.conversationTimestamp * 1000
    var peerPush = ""
    for (hsMsg in conv.messagesList) {
        if (!hsMsg.hasMessage()) continue
        val wmi = hsMsg.message
        if (!wmi.hasMessage()) continue
        val body = WhatsAppProtocol.extractMessageBody(wmi.message)
        if (body.isEmpty()) continue
        val key = wmi.key
        val tsMs = wmi.messageTimestamp * 1000
        if (!key.fromMe && wmi.pushName.isNotEmpty()) peerPush = wmi.pushName
        val senderName = if (key.fromMe) null
        else wmi.pushName.ifEmpty { conv.name.ifEmpty { contactName ?: phone } }
        msgs.add(HMsg(key.id, body, key.fromMe, tsMs, senderName))
        if (tsMs >= lastTs) { lastTs = tsMs; lastBody = body }
    }
    if (msgs.isEmpty()) return 0

    // Register the conversation row first.
    val isGroupChat = chatJid.endsWith("@g.us")
    _events.emit(
        WhatsAppEvent.ConversationUpdate(
            source = MessageSource.WHATSAPP,
            conversationId = convId,
            peerName = conv.name.ifEmpty { contactName ?: peerPush.ifEmpty { phone } },
            peerPhone = phone,
            avatarUrl = null,
            lastPreview = lastBody,
            lastTimestamp = lastTs,
            unreadCount = conv.unreadCount,
            isGroup = isGroupChat,
        )
    )
    // Then backfill its messages.
    for (m in msgs) {
        _events.emit(
            WhatsAppEvent.MessageUpdate(
                source = MessageSource.WHATSAPP,
                conversationId = convId,
                messageId = m.id,
                body = m.body,
                outgoing = m.outgoing,
                timestamp = m.ts,
                senderName = m.sender,
            )
        )
    }
    // Pull the next older page on demand. Fire-and-forget: the request fans out via usync (a
    // slow IQ) and must NOT block the conversation backfill loop, or only the first chat would
    // appear promptly. Pagination walks backwards one page at a time — each on-demand response
    // re-enters handleHistorySync with a new (older) oldest message, until the phone returns an
    // empty/anchor-only chunk (msgs.isEmpty above → no further request), i.e. end of history.
    if (requestMore) {
        val oldest = msgs.minByOrNull { it.ts }
        if (oldest != null) {
            scope.launch {
                sendHistoryOnDemandRequest(rawChatJid, oldest.id, oldest.outgoing, oldest.ts / 1000)
            }
        }
    }
    return msgs.size
}

/**
 * Ask our own account to stream older history for a chat. Ref whatsmeow BuildHistorySyncRequest
 * — a HISTORY_SYNC_ON_DEMAND peer-data-operation message sent E2E to self. Deduped per
 * (chat, oldest-message) cursor so a repeated/anchor-only response ends the walk, while genuinely
 * older responses keep paginating. Bounded by MAX_ON_DEMAND_PAGES_PER_CHAT. Best-effort.
 */
internal suspend fun WhatsAppClient.sendHistoryOnDemandRequest(
    chatJid: String,
    oldestMsgId: String,
    oldestFromMe: Boolean,
    oldestTsSec: Long,
) {
    if (oldestMsgId.isEmpty() || chatJid.isEmpty()) return
    // Stop if we've already asked for this exact cursor (prevents same-page loops).
    if (!onDemandRequested.add("$chatJid|$oldestMsgId")) return
    // Bound total pages per chat.
    val pages = onDemandPageCount.merge(chatJid, 1, Int::plus) ?: 1
    if (pages > MAX_ON_DEMAND_PAGES_PER_CHAT) {
        WhatsAppDiag.log(TAG, "on-demand history: hit page cap for $chatJid, stopping")
        return
    }
    val auth = authData ?: return
    try {
        val ownUser = auth.wid.substringBefore("@").substringBefore(":").substringBefore(".")
        val ownJid = "$ownUser@s.whatsapp.net"
        val msg = WhatsAppProtocol.buildHistoryOnDemandRequest(
            chatJid, oldestMsgId, oldestFromMe, oldestTsSec, ON_DEMAND_PAGE_SIZE,
        )
        val id = WhatsAppProtocol.generateMessageId(auth.wid)
        val node = buildEncryptedMessageNode(ownJid, id, msg, "text") ?: return
        webSocket?.send(WhatsAppProtocol.encodeNode(node))
        WhatsAppDiag.log(TAG, "on-demand history requested for $chatJid (page $pages, oldest=$oldestMsgId)")
    } catch (e: Exception) {
        WhatsAppDiag.log(TAG, "on-demand request failed: ${e.message}")
    }
}
