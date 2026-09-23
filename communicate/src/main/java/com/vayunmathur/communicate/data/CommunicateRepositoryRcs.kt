package com.vayunmathur.communicate.data

import android.content.Context
import android.telephony.TelephonyManager
import com.vayunmathur.communicate.data.rcs.RcsCachedMessage
import com.vayunmathur.communicate.data.rcs.RcsCapabilityExchange
import com.vayunmathur.communicate.data.rcs.RcsConversation
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsFeature
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
import com.vayunmathur.library.util.AppMessages
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CommunicateRepository RCS single-registration line (mirrors
 * CommunicateRepositorySignal.kt).
 *
 * Threads/messages read from the local Room cache (populated by
 * RcsSyncService); sends go through [RcsSipTransport] with SMS fallback when
 * the contact is not capable or the transport is unavailable. Extension
 * functions on [CommunicateRepository]; behavior identical, call sites
 * unchanged.
 */

/** Result of an RCS send attempt, so the caller can fall back to SMS. */
enum class RcsSendResult {
    Sent,
    FallbackSms,
    Failed,
}

internal suspend fun CommunicateRepository.loadRcsThreads(context: Context): List<SmsThread> {
    if (!RcsFeature.enabled) return emptyList()
    return runCatching {
        val db = RcsDatabase.getDatabase(context)
        db.cachedMessageDao().getLatestPerConversation().map { m ->
            val cid = m.conversationId
            val conv = db.conversationDao().getConversation(cid)
            val isGroup = conv?.isGroup ?: false
            val unread = conv?.unreadCount ?: 0
            val participants = parseParticipantsCsv(conv?.participants)
            val groupTitle = conv?.name?.takeIf { it.isNotBlank() }
            SmsThread(
                threadId = stableThreadId(cid),
                address = if (isGroup) cid else cid,
                displayName = if (isGroup) {
                    groupTitle ?: rcsDisplayName(context, cid)
                } else {
                    rcsDisplayName(context, cid)
                },
                snippet = m.body,
                timestampMillis = m.timestamp,
                unreadCount = unread,
                line = CommunicateLine.Rcs,
                remoteId = cid,
                isGroup = isGroup,
                avatarUrl = null,
                participants = participants,
                groupTitle = groupTitle,
            )
        }
    }.getOrDefault(emptyList())
}

internal suspend fun CommunicateRepository.loadRcsMessages(context: Context, conversationId: String): List<SmsMessage> =
    runCatching {
        val db = RcsDatabase.getDatabase(context)
        db.cachedMessageDao().getForConversation(conversationId).map { m ->
            SmsMessage(
                id = (m.messageId.hashCode().toLong() and 0xFFFFFFFFL),
                threadId = stableThreadId(conversationId),
                address = conversationId,
                body = m.body,
                timestampMillis = m.timestamp,
                outgoing = m.outgoing,
                read = true,
                line = CommunicateLine.Rcs,
                remoteId = m.messageId,
                attachments = if (m.ftUrl != null) {
                    listOf(CommunicateAttachment(m.ftUrl, m.ftMime ?: "application/octet-stream"))
                } else {
                    emptyList()
                },
                senderAddress = m.senderId.takeIf { it.isNotBlank() && !m.outgoing },
                status = m.status.let { s ->
                    MessageStatus.entries.getOrElse(s) { MessageStatus.None }
                },
            )
        }
    }.getOrDefault(emptyList())

/**
 * Send on the RCS line. Returns [RcsSendResult.FallbackSms] when the contact
 * is not RCS-capable or the transport is unavailable, so the caller can
 * re-send via SMS. Never throws.
 */
suspend fun CommunicateRepository.sendRcsMessage(
    context: Context,
    address: String,
    body: String,
    threadRemoteId: String?,
    attachments: List<CommunicateAttachment> = emptyList(),
    participants: List<String> = emptyList(),
): RcsSendResult = withContext(Dispatchers.IO) {
    if (!RcsFeature.enabled) return@withContext RcsSendResult.Failed
    val recipient = threadRemoteId?.takeIf { it.isNotBlank() } ?: toRcsRecipient(context, address)
    if (recipient.isBlank()) return@withContext RcsSendResult.Failed
    // Capability pre-check: unknown contacts are not capable → SMS path.
    val capable = runCatching {
        if (participants.isNotEmpty()) {
            RcsCapabilityExchange.filterCapable(context, participants).isNotEmpty()
        } else {
            RcsCapabilityExchange.isContactRcsCapable(context, recipient)
        }
    }.getOrDefault(false)
    if (!capable) return@withContext RcsSendResult.FallbackSms
    if (!RcsSipTransport.canSend()) return@withContext RcsSendResult.FallbackSms
    runCatching {
        val (startLine, headers, content) = if (attachments.isEmpty()) {
            RcsSipTransport.buildChatMessage(
                fromUri = "sip:me@rcs",
                toUri = "sip:$recipient@rcs",
                body = body,
            )
        } else {
            // v1: attachments ride as a plain-text placeholder + FT metadata row;
            // full MSRP/FT flows land in RcsFileTransfer.
            RcsSipTransport.buildChatMessage(
                fromUri = "sip:me@rcs",
                toUri = "sip:$recipient@rcs",
                body = body.ifBlank { "[attachment]" },
            )
        }
        val ok = RcsSipTransport.sendSipMessage(startLine, headers, content)
        if (!ok) return@withContext RcsSendResult.FallbackSms
        if (body.isNotBlank()) {
            cacheOutgoingRcs(context, recipient, body, "local-${UUID.randomUUID()}")
        }
        RcsSendResult.Sent
    }.getOrDefault(RcsSendResult.FallbackSms)
}

/**
 * Build an RCS recipient id from a phone number / address. Conference URIs
 * (groups) pass through; otherwise the number is normalized to E.164 via
 * libphonenumber, mirroring [toSignalRecipient].
 */
internal fun CommunicateRepository.toRcsRecipient(context: Context, address: String): String {
    if (address.isBlank()) return ""
    if (address.contains("@") || address.contains(":")) return address
    val region = runCatching {
        val tm = context.getSystemService(TelephonyManager::class.java)
        (tm?.simCountryIso?.takeIf { it.isNotBlank() } ?: tm?.networkCountryIso)?.uppercase()
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: context.resources.configuration.locales[0].country.ifEmpty { "US" }
    val e164 = runCatching {
        val util = com.google.i18n.phonenumbers.PhoneNumberUtil.getInstance()
        val parsed = util.parse(address, region)
        util.format(parsed, com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat.E164)
    }.getOrNull()
    return e164 ?: address
}

private fun CommunicateRepository.rcsDisplayName(context: Context, conversationId: String): String? {
    if (conversationId.contains("@") || conversationId.contains(":")) return conversationId
    return findContactName(context, conversationId) ?: conversationId
}

/** Insert an outgoing RCS message into the local cache so it shows in our own thread. */
internal suspend fun CommunicateRepository.cacheOutgoingRcs(
    context: Context,
    conversationId: String,
    body: String,
    messageId: String,
) {
    runCatching {
        val db = RcsDatabase.getDatabase(context)
        val now = System.currentTimeMillis()
        db.cachedMessageDao().upsert(
            RcsCachedMessage(
                messageId = messageId,
                conversationId = conversationId,
                body = body,
                timestamp = now,
                outgoing = true,
                status = 1,
            ),
        )
        val existing = db.conversationDao().getConversation(conversationId)
        db.conversationDao().upsert(
            (existing ?: RcsConversation(remoteId = conversationId)).copy(lastMessageTimestamp = now),
        )
    }
}

/** Write an inbound RCS message into the local cache (sync service). */
internal suspend fun CommunicateRepository.cacheInboundRcs(
    context: Context,
    conversationId: String,
    body: String,
    senderId: String = "",
    messageId: String = "in-${UUID.randomUUID()}",
    ftUrl: String? = null,
    ftMime: String? = null,
) {
    runCatching {
        val db = RcsDatabase.getDatabase(context)
        val now = System.currentTimeMillis()
        db.cachedMessageDao().upsert(
            RcsCachedMessage(
                messageId = messageId,
                conversationId = conversationId,
                body = body,
                timestamp = now,
                outgoing = false,
                senderId = senderId,
                ftUrl = ftUrl,
                ftMime = ftMime,
            ),
        )
        val existing = db.conversationDao().getConversation(conversationId)
        db.conversationDao().upsert(
            (existing ?: RcsConversation(remoteId = conversationId)).copy(
                lastMessageTimestamp = now,
                unreadCount = (existing?.unreadCount ?: 0) + 1,
            ),
        )
    }
}

/** Cache an outgoing file-transfer row (our own FT send echo). */
internal suspend fun CommunicateRepository.cacheOutgoingRcsFile(
    context: Context,
    conversationId: String,
    body: String,
    messageId: String,
    ftUrl: String?,
    ftMime: String?,
) {
    runCatching {
        val db = RcsDatabase.getDatabase(context)
        val now = System.currentTimeMillis()
        db.cachedMessageDao().upsert(
            RcsCachedMessage(
                messageId = messageId,
                conversationId = conversationId,
                body = body,
                timestamp = now,
                outgoing = true,
                ftUrl = ftUrl,
                ftMime = ftMime,
                status = 1,
            ),
        )
        val existing = db.conversationDao().getConversation(conversationId)
        db.conversationDao().upsert(
            (existing ?: RcsConversation(remoteId = conversationId)).copy(lastMessageTimestamp = now),
        )
    }
}

/** Mark an RCS conversation read (clears the local unread badge). */
suspend fun CommunicateRepository.markRcsRead(
    context: Context,
    remoteId: String?,
    address: String,
): Unit = withContext(Dispatchers.IO) {
    if (!RcsFeature.enabled) return@withContext
    val cid = remoteId?.takeIf { it.isNotBlank() } ?: toRcsRecipient(context, address)
    runCatching {
        val db = RcsDatabase.getDatabase(context)
        db.conversationDao().getConversation(cid)?.let {
            if (it.unreadCount != 0) db.conversationDao().upsert(it.copy(unreadCount = 0))
        }
    }
}

/** Surface the SMS-fallback notice on the caller's behalf (never `Toast`). */
fun CommunicateRepository.notifyRcsFallback(context: Context) {
    AppMessages.show(context.getString(com.vayunmathur.communicate.R.string.rcs_fallback_sms))
}
