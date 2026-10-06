package com.vayunmathur.communicate.data.whatsapp

import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient.State
import com.vayunmathur.communicate.data.whatsapp.buildLeaveGroup
import com.vayunmathur.communicate.data.whatsapp.encodeNode
import com.vayunmathur.communicate.data.whatsapp.generateMessageId

/**
 * Delete a chat, optionally leaving group, with AppState mutations.
 * From Go HandleMatrixDeleteChat.
 */
/** True when the socket can carry a group-leave stanza right now. */
private fun WhatsAppClient.canLeaveGroup(): Boolean =
    stateMutable.value is State.Connected && webSocket != null

suspend fun WhatsAppClient.deleteChat(conversationId: String, leaveGroup: Boolean = true): Boolean {
    val jid = extractJid(conversationId) ?: return false
    val ws = webSocket

    if (leaveGroup && jid.contains("@g.us") && canLeaveGroup()) {
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)
        val node = WhatsAppProtocol.buildLeaveGroup(jid, id)
        ws?.send(WhatsAppProtocol.encodeNode(node))
    }

    // Query last message timestamp for the delete anchor
    val conversation = db?.conversationDao()?.getConversation(jid)
    val lastMsgTimestamp = conversation?.lastMessageTimestamp ?: 0L

    // Push AppState delete mutation (Go HandleMatrixDeleteChat PatchDelete)
    if (stateMutable.value is State.Connected && ws != null) {
        val patchAttrs = mutableMapOf("jid" to jid, "action" to "delete")
        if (lastMsgTimestamp > 0) patchAttrs["messageTimestamp"] = lastMsgTimestamp.toString()

        val patchNode = WhatsAppProtocol.Node(
            tag = "iq",
            attrs = mapOf(
                "id" to WhatsAppProtocol.generateMessageId(authData?.wid),
                "type" to "set",
                "xmlns" to "w:sync:app:state",
                "to" to "s.whatsapp.net",
            ),
            content = listOf(
                WhatsAppProtocol.Node(
                    tag = "sync",
                    content = listOf(
                        WhatsAppProtocol.Node(
                            tag = "collection",
                            attrs = mapOf("name" to "regular"),
                            content = listOf(
                                WhatsAppProtocol.Node(
                                    tag = "patch",
                                    attrs = patchAttrs,
                                )
                            ),
                        )
                    ),
                )
            ),
        )
        ws.send(WhatsAppProtocol.encodeNode(patchNode))
    }

    db?.conversationDao()?.delete(jid)
    eventsMutable.emit(WhatsAppEvent.ConversationDeleted(source, conversationId))
    return true
}

suspend fun WhatsAppClient.sendNewThread(recipientJid: String, body: String): String? {
    if (stateMutable.value !is State.Connected) return null
    val ws = webSocket ?: return null
    val jid = if (recipientJid.contains("@")) recipientJid else "$recipientJid@s.whatsapp.net"
    val id = WhatsAppProtocol.generateMessageId(authData?.wid)
    val node = buildEncryptedTextNode(jid, id, body) ?: return null
    pendingMessageIDs.add(id)
    val sent = ws.send(WhatsAppProtocol.encodeNode(node))
    if (!sent) pendingMessageIDs.remove(id)
    return if (sent) "wa:$jid" else null
}

suspend fun WhatsAppClient.deleteThread(conversationId: String): Boolean {
    val jid = extractJid(conversationId) ?: return false
    db?.conversationDao()?.delete(jid)
    eventsMutable.emit(WhatsAppEvent.ConversationDeleted(source, conversationId))
    return true
}

suspend fun WhatsAppClient.markChatUnread(conversationId: String, unread: Boolean) {
    if (stateMutable.value !is State.Connected) return
    val ws = webSocket ?: return
    val chatJid = extractJid(conversationId) ?: return

    val patchNode = WhatsAppProtocol.Node(
        tag = "iq",
        attrs = mapOf(
            "id" to WhatsAppProtocol.generateMessageId(authData?.wid),
            "type" to "set",
            "xmlns" to "w:sync:app:state",
            "to" to "s.whatsapp.net",
        ),
        content = listOf(
            WhatsAppProtocol.Node(
                tag = "sync",
                content = listOf(
                    WhatsAppProtocol.Node(
                        tag = "collection",
                        attrs = mapOf("name" to "regular"),
                        content = listOf(
                            WhatsAppProtocol.Node(
                                tag = "patch",
                                attrs = mapOf(
                                    "jid" to chatJid,
                                    "action" to "markRead",
                                ),
                                data = if (unread) "true".toByteArray() else "false".toByteArray(),
                            )
                        ),
                    )
                ),
            )
        ),
    )
    ws.send(WhatsAppProtocol.encodeNode(patchNode))
}

suspend fun WhatsAppClient.setMute(conversationId: String, muteUntilMs: Long) {
    if (stateMutable.value !is State.Connected) return
    val ws = webSocket ?: return
    val chatJid = extractJid(conversationId) ?: return

    val patchNode = WhatsAppProtocol.Node(
        tag = "iq",
        attrs = mapOf(
            "id" to WhatsAppProtocol.generateMessageId(authData?.wid),
            "type" to "set",
            "xmlns" to "w:sync:app:state",
            "to" to "s.whatsapp.net",
        ),
        content = listOf(
            WhatsAppProtocol.Node(
                tag = "sync",
                content = listOf(
                    WhatsAppProtocol.Node(
                        tag = "collection",
                        attrs = mapOf("name" to "regular"),
                        content = listOf(
                            WhatsAppProtocol.Node(
                                tag = "patch",
                                attrs = mapOf(
                                    "jid" to chatJid,
                                    "action" to "mute",
                                ),
                                data = muteUntilMs.toString().toByteArray(),
                            )
                        ),
                    )
                ),
            )
        ),
    )
    ws.send(WhatsAppProtocol.encodeNode(patchNode))
    val conv = db?.conversationDao()?.getConversation(chatJid)
    if (conv != null) {
        db?.conversationDao()?.upsert(conv.copy(muteEndTime = muteUntilMs))
    }
}

suspend fun WhatsAppClient.togglePin(conversationId: String, pinned: Boolean) {
    if (stateMutable.value !is State.Connected) return
    val ws = webSocket ?: return
    val chatJid = extractJid(conversationId) ?: return

    val patchNode = WhatsAppProtocol.Node(
        tag = "iq",
        attrs = mapOf(
            "id" to WhatsAppProtocol.generateMessageId(authData?.wid),
            "type" to "set",
            "xmlns" to "w:sync:app:state",
            "to" to "s.whatsapp.net",
        ),
        content = listOf(
            WhatsAppProtocol.Node(
                tag = "sync",
                content = listOf(
                    WhatsAppProtocol.Node(
                        tag = "collection",
                        attrs = mapOf("name" to "regular"),
                        content = listOf(
                            WhatsAppProtocol.Node(
                                tag = "patch",
                                attrs = mapOf(
                                    "jid" to chatJid,
                                    "action" to "pin",
                                ),
                                data = if (pinned) "true".toByteArray() else "false".toByteArray(),
                            )
                        ),
                    )
                ),
            )
        ),
    )
    ws.send(WhatsAppProtocol.encodeNode(patchNode))
    val conv = db?.conversationDao()?.getConversation(chatJid)
    if (conv != null) {
        db?.conversationDao()?.upsert(conv.copy(pinned = pinned))
    }
}
