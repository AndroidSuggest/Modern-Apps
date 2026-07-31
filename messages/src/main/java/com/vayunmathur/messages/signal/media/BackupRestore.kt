@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.messages.signal.media

import kotlin.uuid.Uuid
import android.util.Log
import com.vayunmathur.messages.data.MessageSource
import com.vayunmathur.messages.gmessages.GMEvent
import com.vayunmathur.messages.signal.groups.GroupManager
import com.vayunmathur.messages.signal.proto.backup.Backup
import com.vayunmathur.messages.signal.store.SignalDatabase
import com.vayunmathur.messages.signal.store.SignalRecipientStore
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer

/**
 * Reads the parsed Signal "link & sync" backup that [BackupManager] persisted into the
 * separate signal_backup_* Room tables, converts it into real conversations + messages,
 * and replays them through [emit] (SignalClient._events) using the SAME conversation-id
 * scheme as the live receive path so restored rows merge with live ones:
 *   - 1:1  -> the peer's ACI (lowercase UUID), matching handleDecryptedMessage's chatId
 *   - group -> GroupManager.deriveGroupId(masterKey), matching the live group id
 *
 * Message-request state is derived from the backup (Contact.profileSharing / visibility /
 * blocked, Group.whitelisted) and carried to the data layer via the existing
 * ConversationUpdate.serviceData JSON escape hatch (key "isMessageRequest").
 */
class BackupRestore(
    private val db: SignalDatabase,
    private val recipientStore: SignalRecipientStore,
    private val groupManager: GroupManager?,
    private val selfAci: String,
    private val source: MessageSource,
    private val emit: suspend (GMEvent) -> Unit,
) {
    companion object {
        private const val TAG = "BackupRestore"
        private const val MAX_MESSAGES_PER_CHAT = 500
    }

    private val recipients = HashMap<Long, Backup.Recipient>()

    suspend fun restoreAll() {
        // Persist message-request / block state onto the recipient store up front so the
        // send-side (acceptMessageRequest / deleteThread) and UI reflect backup state.
        persistRecipientFlags()

        val chats = db.backupChatDao().getAll()
        Log.i(TAG, "Restoring ${chats.size} chats from backup (${recipients.size} recipients)")
        var restoredChats = 0
        for (chat in chats) {
            try {
                if (restoreChat(chat)) restoredChats++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore chat ${chat.id}: ${e.message}")
            }
        }
        Log.i(TAG, "Backup restore complete: emitted $restoredChats conversations")
    }

    private suspend fun getBackupRecipient(id: Long): Backup.Recipient? {
        recipients[id]?.let { return it }
        val entity = db.backupRecipientDao().get(id) ?: return null
        return try {
            Backup.Recipient.parseFrom(entity.data).also { recipients[id] = it }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse recipient $id: ${e.message}")
            null
        }
    }

    private suspend fun persistRecipientFlags() {
        // Walk every chat's recipient and persist whitelisted/blocked so live state is seeded.
        val chats = db.backupChatDao().getAll()
        for (chat in chats) {
            val recipient = getBackupRecipient(chat.recipientId) ?: continue
            try {
                when (recipient.destinationCase) {
                    Backup.Recipient.DestinationCase.CONTACT -> {
                        val contact = recipient.contact
                        val aci = aciToUuid(contact.aci.toByteArray()) ?: continue
                        if (aci == selfAci) continue
                        recipientStore.setWhitelisted(aci, contact.profileSharing)
                        recipientStore.setBlocked(aci, contact.blocked)
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist flags for recipient ${chat.recipientId}: ${e.message}")
            }
        }
    }

    /** Returns true if a conversation was emitted. */
    private suspend fun restoreChat(chat: com.vayunmathur.messages.signal.store.SignalBackupChatEntity): Boolean {
        val recipient = getBackupRecipient(chat.recipientId) ?: return false

        val target: ChatTarget = when (recipient.destinationCase) {
            Backup.Recipient.DestinationCase.CONTACT -> contactTarget(recipient.contact) ?: return false
            Backup.Recipient.DestinationCase.GROUP -> groupTarget(recipient.group) ?: return false
            else -> {
                // Self/Note-to-Self, distribution lists, call links, release notes: skip.
                return false
            }
        }

        val items = db.backupChatItemDao().getByChatId(chat.id, MAX_MESSAGES_PER_CHAT)

        // Skip empty 1:1 chats (mirrors the bridge); keep groups so they still appear.
        if (items.isEmpty() && !target.isGroup) return false

        var lastPreview: String? = null
        var lastTimestamp = 0L
        var unread = 0

        // items come newest-first; emit oldest-first so ordering is natural.
        for (entity in items.reversed()) {
            val item = try {
                Backup.ChatItem.parseFrom(entity.data)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse chat item: ${e.message}")
                continue
            }
            val rendered = renderChatItem(item, target) ?: continue
            emit(rendered.event)
            lastPreview = rendered.preview
            lastTimestamp = rendered.timestamp
            if (rendered.unread) unread++
        }

        val serviceData = JSONObject()
            .put("isMessageRequest", target.isMessageRequest)
            .put("blocked", target.blocked)
            .apply {
                if (target.participantNames.isNotEmpty()) {
                    put("participantNames", JSONArray(target.participantNames))
                }
            }
            .toString()

        emit(
            GMEvent.ConversationUpdate(
                source = source,
                conversationId = target.conversationId,
                peerName = target.name,
                peerPhone = target.phone,
                avatarUrl = null,
                lastPreview = lastPreview,
                lastTimestamp = lastTimestamp,
                unreadCount = unread,
                isGroup = target.isGroup,
                participantCount = target.participantCount,
                conversationType = "Signal",
                serviceData = serviceData,
            )
        )
        return true
    }

    private data class ChatTarget(
        val conversationId: String,
        val name: String?,
        val phone: String?,
        val isGroup: Boolean,
        val isMessageRequest: Boolean,
        val blocked: Boolean,
        val participantCount: Int = 0,
        val participantNames: List<String> = emptyList(),
    )

    private data class RenderedItem(
        val event: GMEvent.MessageUpdate,
        val preview: String,
        val timestamp: Long,
        val unread: Boolean,
    )

    private fun contactTarget(contact: Backup.Contact): ChatTarget? {
        val aci = aciToUuid(contact.aci.toByteArray()) ?: return null
        if (aci == selfAci) return null // Note-to-Self
        val phone = if (contact.e164 != 0L) "+${contact.e164}" else null
        val name = contactDisplayName(contact, phone)
        val pending = !contact.profileSharing ||
            contact.visibility == Backup.Contact.Visibility.HIDDEN_MESSAGE_REQUEST
        return ChatTarget(
            conversationId = aci,
            name = name,
            phone = phone,
            isGroup = false,
            isMessageRequest = pending && !contact.blocked,
            blocked = contact.blocked,
        )
    }

    private suspend fun groupTarget(group: Backup.Group): ChatTarget? {
        val masterKey = group.masterKey.toByteArray()
        if (masterKey.isEmpty()) return null
        val gm = groupManager ?: return null
        val groupId = try {
            gm.deriveGroupId(masterKey)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to derive group id: ${e.message}")
            return null
        }
        // Seed the master key so the group becomes resolvable for live ops.
        try {
            gm.storeMasterKey(groupId, masterKey)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to store group master key: ${e.message}")
        }
        val title = try {
            group.snapshot.title.title.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
        val participantNames = group.snapshot.membersList.mapNotNull { member ->
            val aci = aciToUuid(member.userId.toByteArray()) ?: return@mapNotNull null
            resolveRecipientName(aci) ?: aci.take(8)
        }
        return ChatTarget(
            conversationId = groupId,
            name = title,
            phone = null,
            isGroup = true,
            isMessageRequest = !group.whitelisted && !group.blocked,
            blocked = group.blocked,
            participantCount = group.snapshot.membersCount,
            participantNames = participantNames,
        )
    }

    private suspend fun renderChatItem(item: Backup.ChatItem, target: ChatTarget): RenderedItem? {
        val outgoing = item.hasOutgoing()
        val timestamp = item.dateSent

        // Resolve the actual author (self for outgoing, else the chat item's author recipient).
        val authorContact = if (outgoing) {
            null
        } else {
            getBackupRecipient(item.authorId)
                ?.takeIf { it.destinationCase == Backup.Recipient.DestinationCase.CONTACT }
                ?.contact
        }
        val authorAci = if (outgoing) {
            selfAci
        } else {
            authorContact?.let { aciToUuid(it.aci.toByteArray()) } ?: target.conversationId
        }
        val messageId = "${authorAci}_$timestamp"
        val senderId = authorAci
        // Per-message sender name: individual sender in groups; peer in 1:1; null for our own.
        val senderName = when {
            outgoing -> null
            target.isGroup -> {
                val phone = authorContact?.let { if (it.e164 != 0L) "+${it.e164}" else null }
                authorContact?.let { contactDisplayName(it, phone) } ?: authorAci.take(8)
            }
            else -> target.name
        }

        val unread = item.hasIncoming() && !item.incoming.read

        when (item.itemCase) {
            Backup.ChatItem.ItemCase.STANDARDMESSAGE -> {
                val std = item.standardMessage
                val text = if (std.hasText()) std.text.body else ""
                val media = restoreFirstAttachment(std)
                val body = when {
                    text.isNotEmpty() -> text
                    media != null -> media.fileName ?: "[Attachment]"
                    std.attachmentsCount > 0 -> "[Attachment]"
                    else -> return null
                }
                val event = GMEvent.MessageUpdate(
                    source = source,
                    conversationId = target.conversationId,
                    messageId = messageId,
                    body = body,
                    outgoing = outgoing,
                    timestamp = timestamp,
                    senderName = senderName,
                    senderId = senderId,
                    mediaData = media?.data,
                    mediaMime = media?.mime,
                    mediaName = media?.fileName,
                )
                return RenderedItem(event, body, timestamp, unread)
            }
            Backup.ChatItem.ItemCase.STICKERMESSAGE -> {
                val body = "[Sticker]"
                val event = GMEvent.MessageUpdate(
                    source = source,
                    conversationId = target.conversationId,
                    messageId = messageId,
                    body = body,
                    outgoing = outgoing,
                    timestamp = timestamp,
                    senderName = senderName,
                    senderId = senderId,
                )
                return RenderedItem(event, body, timestamp, unread)
            }
            else -> {
                // contactMessage / payment / giftBadge / viewOnce / poll / remoteDeleted /
                // adminDeleted: not restored as messages.
                return null
            }
        }
    }

    private data class RestoredMedia(val data: ByteArray, val mime: String?, val fileName: String?)

    /**
     * Best-effort media restore: download + decrypt the first attachment from the transit
     * CDN (reusing AttachmentManager). Backup-media-tier-only attachments (no transit
     * locator) are not reconstructed here and fall back to a placeholder. Returns null on
     * any failure, in which case the caller renders a placeholder.
     */
    private suspend fun restoreFirstAttachment(std: Backup.StandardMessage): RestoredMedia? {
        if (std.attachmentsCount == 0) return null
        val attachment = std.attachmentsList.firstOrNull() ?: return null
        if (!attachment.hasPointer()) return null
        val pointer = attachment.pointer
        if (!pointer.hasLocatorInfo()) return null
        val locator = pointer.locatorInfo

        val transitCdnKey = locator.transitCdnKey
        if (transitCdnKey.isNullOrEmpty()) {
            // Media-tier-only: would require mediaRootBackupKey-based CDN derivation. Skip.
            return null
        }
        val key = locator.key.toByteArray()
        if (key.size < 64) return null

        val (digest, plaintextDigest) = when (locator.integrityCheckCase) {
            Backup.FilePointer.LocatorInfo.IntegrityCheckCase.ENCRYPTEDDIGEST ->
                locator.encryptedDigest.toByteArray() to false
            Backup.FilePointer.LocatorInfo.IntegrityCheckCase.PLAINTEXTHASH ->
                locator.plaintextHash.toByteArray() to true
            else -> null to false
        }

        return try {
            val bytes = AttachmentManager.download(
                cdnId = 0L,
                cdnKey = transitCdnKey,
                cdnNumber = locator.transitCdnNumber,
                key = key,
                digest = digest,
                plaintextDigest = plaintextDigest,
                size = locator.size,
            ) ?: return null
            val mime = if (pointer.hasContentType()) pointer.contentType else null
            val fileName = if (pointer.hasFileName()) pointer.fileName else null
            RestoredMedia(bytes, mime, fileName)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore attachment: ${e.message}")
            null
        }
    }

    private fun contactDisplayName(contact: Backup.Contact, phone: String?): String? {
        val profile = listOf(contact.profileGivenName, contact.profileFamilyName)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .takeIf { it.isNotEmpty() }
        if (profile != null) return profile
        val system = listOf(contact.systemGivenName, contact.systemFamilyName)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .takeIf { it.isNotEmpty() }
        if (system != null) return system
        if (contact.hasNickname()) {
            val nick = listOf(contact.nickname.given, contact.nickname.family)
                .filter { it.isNotEmpty() }
                .joinToString(" ")
                .takeIf { it.isNotEmpty() }
            if (nick != null) return nick
        }
        return phone
    }

    private suspend fun resolveRecipientName(aci: String): String? {
        val r = recipientStore.getRecipient(aci) ?: return null
        return r.contactName ?: r.profileName ?: r.e164
    }

    private fun aciToUuid(bytes: ByteArray): String? {
        if (bytes.size != 16) return null
        return try {
            val bb = ByteBuffer.wrap(bytes)
            Uuid.fromLongs(bb.long, bb.long).toString()
        } catch (e: Exception) {
            null
        }
    }
}
