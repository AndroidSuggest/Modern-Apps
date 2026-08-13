package com.vayunmathur.communicate.data.whatsapp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Drains [WhatsAppClient.events] into Room ([WhatsAppDatabase]) so the merged inbox survives restarts
 * and reflects rich features. Runs on its own `Dispatchers.IO + SupervisorJob` scope; [HistorySync]
 * is written in a batched `@Transaction` (via `upsertAll`) to avoid per-row overhead.
 */
class WhatsAppEventProcessor(private val db: WhatsAppDatabase) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val messages = db.cachedMessageDao()
    private val reactions = db.cachedReactionDao()
    private val conversations = db.conversationDao()

    fun start(events: SharedFlow<WhatsAppEvent>) {
        scope.launch {
            events.collect { event ->
                try {
                    handle(event)
                } catch (t: Throwable) {
                    Log.e(TAG, "failed to process ${event::class.simpleName}", t)
                }
            }
        }
    }

    fun stop() {
        scope.cancel()
    }

    private suspend fun handle(event: WhatsAppEvent) {
        when (event) {
            is WhatsAppEvent.IncomingMessage -> {
                val cid = chatJid(event.conversationId)
                // Only bump unread for a genuinely new message — the same stanza can be delivered
                // more than once (live + offline replay/retry), which otherwise over-counts.
                val isNew = messages.get(event.messageId) == null
                val sd = WhatsAppServiceData(
                    senderName = event.senderName,
                    senderJid = event.senderId,
                    pollQuestion = event.pollQuestion,
                    pollOptions = event.pollOptions.map { PollOptionData(it) },
                    mediaUrl = event.attachments.firstOrNull()?.url,
                    mediaMime = event.attachments.firstOrNull()?.mimeType,
                    mediaName = event.attachments.firstOrNull()?.fileName,
                )
                messages.upsert(
                    WhatsAppCachedMessage(
                        messageId = event.messageId,
                        conversationJid = cid,
                        body = event.body,
                        timestamp = event.timestamp,
                        outgoing = false,
                        senderJid = event.senderId ?: "",
                        senderName = event.senderName ?: "",
                        mediaUrl = event.attachments.firstOrNull()?.url,
                        mediaMime = event.attachments.firstOrNull()?.mimeType,
                        mediaName = event.attachments.firstOrNull()?.fileName,
                        serviceData = event.serviceData ?: sd.serialize(),
                    ),
                )
                touchConversation(cid, event.timestamp, incrementUnread = isNew)
            }

            is WhatsAppEvent.MessageUpdate -> {
                messages.upsert(
                    WhatsAppCachedMessage(
                        messageId = event.messageId,
                        conversationJid = chatJid(event.conversationId),
                        body = event.body,
                        timestamp = event.timestamp,
                        outgoing = event.outgoing,
                        senderJid = event.senderId ?: "",
                        senderName = event.senderName ?: "",
                        mediaMime = event.mediaMime,
                        mediaName = event.mediaName,
                        serviceData = event.serviceData,
                    ),
                )
                touchConversation(chatJid(event.conversationId), event.timestamp, incrementUnread = false)
            }

            is WhatsAppEvent.MessageEdited -> {
                messages.markEdited(event.messageId, event.newBody)
                mergeServiceData(event.messageId) { it.copy(isEdited = true) }
            }

            is WhatsAppEvent.MessageDeleted -> {
                messages.markRevoked(event.messageId)
                mergeServiceData(event.messageId) { it.copy(isRevoked = true) }
            }

            is WhatsAppEvent.ReactionReceived -> {
                reactions.upsert(
                    WhatsAppCachedReaction(event.messageId, event.emoji, event.senderId, System.currentTimeMillis()),
                )
                refreshReactions(event.messageId)
            }

            is WhatsAppEvent.ReactionRemoved -> {
                reactions.remove(event.messageId, event.senderId)
                refreshReactions(event.messageId)
            }

            is WhatsAppEvent.PollVote -> {
                mergeServiceData(event.pollMessageId) { sd ->
                    val updated = sd.pollOptions.map { opt ->
                        if (opt.name in event.optionNames && event.voterId !in opt.voters) {
                            opt.copy(voteCount = opt.voteCount + 1, voters = opt.voters + event.voterId)
                        } else {
                            opt
                        }
                    }
                    sd.copy(pollOptions = updated)
                }
            }

            is WhatsAppEvent.ConversationUpdate -> {
                val jid = chatJid(event.conversationId)
                touchConversation(jid, event.lastTimestamp, incrementUnread = false)
                // Persist group metadata (name + participants) so groups render named/flagged and
                // survive restarts. Only overwrite with non-empty values so a later bare update
                // (e.g. a plain message touch) doesn't clobber a good name/participant list.
                val isGroup = event.isGroup || jid.endsWith("@g.us")
                val participantsJson = participantsJsonFromServiceData(event.serviceData)
                if (isGroup || !event.peerName.isNullOrBlank() || participantsJson != null) {
                    val existing = conversations.getConversation(jid) ?: WhatsAppConversation(chatJid = jid)
                    conversations.upsert(
                        existing.copy(
                            isGroup = isGroup || existing.isGroup,
                            name = event.peerName?.takeIf { it.isNotBlank() } ?: existing.name,
                            participants = participantsJson ?: existing.participants,
                        ),
                    )
                }
            }

            is WhatsAppEvent.ConversationDeleted -> {
                messages.deleteConversation(chatJid(event.conversationId))
                conversations.delete(chatJid(event.conversationId))
            }

            is WhatsAppEvent.ReadReceipt -> {
                // Advance the outgoing message's tick: delivery → Delivered (grey ✓✓), read → Read
                // (blue ✓✓). The client emits one event per message id (incl. <list><item> batches),
                // so a single update per event covers the batch case.
                event.messageId?.let { id ->
                    if (event.isDelivery) messages.markDelivered(id) else messages.markReadStatus(id)
                }
            }

            is WhatsAppEvent.HistorySync -> {
                val rows = ArrayList<WhatsAppCachedMessage>()
                for (conv in event.conversations) {
                    for (m in conv.messages) {
                        rows.add(
                            WhatsAppCachedMessage(
                                messageId = m.messageId,
                                conversationJid = chatJid(conv.conversationId),
                                body = m.body,
                                timestamp = m.timestamp,
                                outgoing = m.outgoing,
                                senderJid = m.senderId ?: "",
                                senderName = m.senderName ?: "",
                                serviceData = m.serviceData,
                            ),
                        )
                    }
                    val newest = conv.messages.maxOfOrNull { it.timestamp } ?: 0L
                    touchConversation(chatJid(conv.conversationId), newest, incrementUnread = false)
                }
                if (rows.isNotEmpty()) messages.upsertAll(rows)
            }

            else -> { /* StateChanged, receipts, typing, presence, etc. — not persisted. */ }
        }
    }

    /** Canonical chat JID: strip the "wa:" source prefix and any :device suffix (user:5@srv -> user@srv). */
    private fun chatJid(raw: String): String {
        val s = raw.removePrefix("wa:")
        val at = s.indexOf('@')
        if (at <= 0) return s
        return s.substring(0, at).substringBefore(':') + s.substring(at)
    }

    /**
     * Extract the group's participant display names from a [WhatsAppEvent.ConversationUpdate]'s
     * serviceData blob (`{"participantNames":[...]}`, emitted by `fetchAndEmitGroupInfo`) and
     * re-serialize them as a JSON array for the conversation's `participants` column. Returns null
     * when there is nothing to persist (so we don't clobber an existing list).
     */
    private fun participantsJsonFromServiceData(serviceData: String?): String? {
        if (serviceData.isNullOrBlank()) return null
        return runCatching {
            val arr = org.json.JSONObject(serviceData).optJSONArray("participantNames") ?: return null
            if (arr.length() == 0) return null
            arr.toString()
        }.getOrNull()
    }

    private suspend fun touchConversation(jid: String, timestamp: Long, incrementUnread: Boolean) {
        val existing = conversations.getConversation(jid)
        val unread = (existing?.unreadCount ?: 0) + if (incrementUnread) 1 else 0
        conversations.upsert(
            (existing ?: WhatsAppConversation(chatJid = jid)).copy(
                lastMessageTimestamp = maxOf(existing?.lastMessageTimestamp ?: 0L, timestamp),
                unreadCount = unread,
            ),
        )
    }

    /** Rebuild the emoji→count reaction summary into the cached message's serviceData. */
    private suspend fun refreshReactions(messageId: String) {
        val rows = reactions.getForMessage(messageId)
        val summary = rows.groupingBy { it.emoji }.eachCount().map { Reaction(it.key, it.value) }
        mergeServiceData(messageId) { it.copy(reactions = summary) }
    }

    private suspend fun mergeServiceData(
        messageId: String,
        transform: (WhatsAppServiceData) -> WhatsAppServiceData,
    ) {
        val msg = messages.get(messageId) ?: return
        val current = WhatsAppServiceData.parse(msg.serviceData) ?: WhatsAppServiceData()
        messages.updateServiceData(messageId, transform(current).serialize())
    }

    companion object {
        private const val TAG = "WAEventProcessor"
    }
}
