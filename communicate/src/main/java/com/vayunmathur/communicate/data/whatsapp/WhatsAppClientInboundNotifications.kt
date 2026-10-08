package com.vayunmathur.communicate.data.whatsapp

import com.vayunmathur.library.log.Log
import com.vayunmathur.communicate.data.whatsapp.call.WhatsAppCallManager
import com.vayunmathur.communicate.data.whatsapp.call.WhatsAppCallSignaling
import com.vayunmathur.communicate.data.whatsapp.encodeNode
import kotlinx.coroutines.launch

private const val STALE_CALL_MINUTES = 15L
private const val STALE_CALL_SECONDS = STALE_CALL_MINUTES * 60
private const val MS_PER_SECOND = 1000L

/**
 * Handle notification events (group changes, mute, pin, archive).
 * From Go handleWAGroupInfoChange, handleWAMute, handleWAArchive, handleWAPin.
 */
internal suspend fun WhatsAppClient.handleNotification(node: WhatsAppProtocol.Node) {
    val notifType = node.attrs["type"] ?: return
    val from = node.attrs["from"] ?: return

    when (notifType) {
        "w:gp2" -> handleGroupNotification(node, from)
        "server_sync" -> handleServerSync(node)
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
        val body = groupChangeBody(child) ?: return@forEach
        eventsMutable.emit(WhatsAppEvent.IncomingMessage(
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

internal suspend fun WhatsAppClient.handleServerSync(node: WhatsAppProtocol.Node) {
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
        if (ageSeconds > STALE_CALL_SECONDS) {
            Log.dev(TAG, "Ignoring old call notification (${ageSeconds}s old)")
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
        eventsMutable.emit(WhatsAppEvent.IncomingMessage(
            source = MessageSource.WHATSAPP,
            conversationId = "wa:$from",
            messageId = "call-$callId",
            body = "\u260E Incoming $callLabel",
            peerName = resolveName(caller),
            peerPhone = null,
            timestamp = callTimestamp * MS_PER_SECOND,
        ))
    }
}
