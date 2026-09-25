package com.vayunmathur.communicate.data

import android.content.Context
import android.telephony.TelephonyManager
import com.vayunmathur.communicate.data.rcs.RcsCachedMessage
import com.vayunmathur.communicate.data.rcs.RcsCapabilityExchange
import com.vayunmathur.communicate.data.rcs.RcsConversation
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsFeature
import com.vayunmathur.communicate.data.rcs.RcsFileTransfer
import com.vayunmathur.communicate.data.rcs.RcsFileTransferHttp
import com.vayunmathur.communicate.data.rcs.RcsMsrp
import com.vayunmathur.communicate.data.rcs.RcsSessionManager
import com.vayunmathur.communicate.data.rcs.ImdnDisposition
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
import com.vayunmathur.communicate.data.rcs.buildEditBody
import com.vayunmathur.communicate.data.rcs.buildGeopushBody
import com.vayunmathur.communicate.data.rcs.buildRevokeBody
import com.vayunmathur.communicate.data.rcs.chunkLargeMessage
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
    val repository = this@sendRcsMessage
    runCatching {
        // E2EE first: when the conversation has an MLS group, encrypt and send
        // the framed payload as an MLS content message. Falls through to the
        // plaintext paths when no group exists or encryption fails.
        val e2ePayload = encryptForE2E(context, recipient, body)
        if (e2ePayload != null) {
            val (startLine, headers, content) = RcsSipTransport.buildChatMessage(
                fromUri = "sip:me@rcs",
                toUri = "sip:$recipient@rcs",
                callId = "${UUID.randomUUID()}@rcs-mls",
                body = android.util.Base64.encodeToString(
                    e2ePayload,
                    android.util.Base64.NO_WRAP,
                ),
            )
            // Re-wrap as the MLS content type (buildChatMessage defaults CPIM).
            val mlsHeaders = headers.replace(
                "Content-Type: message/cpim",
                "Content-Type: ${com.vayunmathur.communicate.data.rcs.e2e.RcsE2E.CT_MLS}",
            )
            val ok = RcsSipTransport.sendSipMessage(startLine, mlsHeaders, content)
            if (!ok) return@withContext RcsSendResult.FallbackSms
            if (body.isNotBlank()) {
                cacheOutgoingRcs(context, recipient, body, "local-${UUID.randomUUID()}")
            }
            return@withContext RcsSendResult.Sent
        }
        // Prefer an established session (MSRP) when one exists; else pager-mode CPIM.
        val session = RcsSessionManager.sessionFor(recipient)
        if (session?.msrpRemotePath != null && attachments.isEmpty()) {
            val cpim = RcsSipTransport.buildCpimBody(body).toByteArray(Charsets.UTF_8)
            if (RcsMsrp.send(session, cpim)) {
                if (body.isNotBlank()) {
                    cacheOutgoingRcs(context, recipient, body, "local-${UUID.randomUUID()}")
                }
                return@withContext RcsSendResult.Sent
            }
        }
        if (attachments.isNotEmpty()) {
            // FT-over-HTTP when the content server is known, else the v1 envelope.
            val ftOk = if (RcsFileTransferHttp.contentServerUri != null) {
                var oneOk = false
                for (attachment in attachments) {
                    if (RcsFileTransferHttp.sendFile(context, repository, recipient, attachment, body)) {
                        oneOk = true
                    }
                }
                oneOk
            } else {
                RcsFileTransfer.sendFiles(context, repository, recipient, body, attachments)
            }
            return@withContext if (ftOk) RcsSendResult.Sent else RcsSendResult.FallbackSms
        }
        val (startLine, headers, content) = RcsSipTransport.buildChatMessage(
            fromUri = "sip:me@rcs",
            toUri = "sip:$recipient@rcs",
            body = body,
        )
        // Large Message: chunk oversized bodies with a shared Message-ID so
        // the far end reassembles; cache one row per send (snippet = full text).
        val messageId = "rcs-${UUID.randomUUID()}"
        val chunks = chunkLargeMessage(messageId, body)
        var ok = true
        for ((index, chunk) in chunks.withIndex()) {
            val (line, head, payload) = if (chunks.size == 1) {
                Triple(startLine, headers, content)
            } else {
                RcsSipTransport.buildChatMessage(
                    fromUri = "sip:me@rcs",
                    toUri = "sip:$recipient@rcs",
                    callId = "${UUID.randomUUID()}@rcs-lm$index",
                    body = chunk,
                )
            }
            ok = RcsSipTransport.sendSipMessage(line, head, payload) && ok
        }
        if (!ok) return@withContext RcsSendResult.FallbackSms
        if (body.isNotBlank()) {
            cacheOutgoingRcs(context, recipient, body, messageId)
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

/**
 * Send a Geolocation Push to [recipient] (UP Geolocation Push, geosms tag).
 * Returns true when the SIP leg accepted.
 */
suspend fun CommunicateRepository.sendRcsGeopush(
    context: Context,
    recipient: String,
    latitude: Double,
    longitude: Double,
    label: String? = null,
): Boolean = withContext(Dispatchers.IO) {
    if (!RcsFeature.enabled || !RcsSipTransport.canSend()) return@withContext false
    runCatching {
        val body = buildGeopushBody(latitude, longitude, label)
        val (startLine, headers, content) = RcsSipTransport.buildChatMessage(
            fromUri = "sip:me@rcs",
            toUri = "sip:$recipient@rcs",
            callId = "${UUID.randomUUID()}@rcs-geo",
            body = body,
        )
        val geoHeaders = headers.replace(
            "Content-Type: message/cpim",
            "Content-Type: application/vnd.gsma.rcs.geopush+xml",
        )
        if (!RcsSipTransport.sendSipMessage(startLine, geoHeaders, content)) {
            return@withContext false
        }
        cacheOutgoingRcs(context, recipient, label ?: "📍 Location", "local-${UUID.randomUUID()}")
        true
    }.getOrDefault(false)
}

/**
 * Revoke our outgoing message [messageId] in [conversationId]: sends the
 * revoke report and blanks the local row. Returns true when sent.
 */
suspend fun CommunicateRepository.revokeRcsMessage(
    context: Context,
    conversationId: String,
    messageId: String,
): Boolean = withContext(Dispatchers.IO) {
    if (!RcsFeature.enabled || !RcsSipTransport.canSend()) return@withContext false
    runCatching {
        val (startLine, headers, content) = RcsSipTransport.buildChatMessage(
            fromUri = "sip:me@rcs",
            toUri = "sip:$conversationId@rcs",
            callId = "${UUID.randomUUID()}@rcs-revoke",
            body = buildRevokeBody(messageId),
        )
        val ok = RcsSipTransport.sendSipMessage(startLine, headers, content)
        if (ok) {
            val db = RcsDatabase.getDatabase(context)
            db.cachedMessageDao().get(messageId)?.let {
                db.cachedMessageDao().upsert(it.copy(body = ""))
            }
        }
        ok
    }.getOrDefault(false)
}

/**
 * Edit our outgoing message [messageId] in [conversationId] to [newText]:
 * sends the replacement and updates the local row. Returns true when sent.
 */
suspend fun CommunicateRepository.editRcsMessage(
    context: Context,
    conversationId: String,
    messageId: String,
    newText: String,
): Boolean = withContext(Dispatchers.IO) {
    if (!RcsFeature.enabled || !RcsSipTransport.canSend() || newText.isBlank()) {
        return@withContext false
    }
    runCatching {
        val (startLine, headers, content) = RcsSipTransport.buildChatMessage(
            fromUri = "sip:me@rcs",
            toUri = "sip:$conversationId@rcs",
            callId = "${UUID.randomUUID()}@rcs-edit",
            body = buildEditBody(messageId, newText),
        )
        val ok = RcsSipTransport.sendSipMessage(startLine, headers, content)
        if (ok) {
            val db = RcsDatabase.getDatabase(context)
            db.cachedMessageDao().get(messageId)?.let {
                db.cachedMessageDao().upsert(it.copy(body = newText))
            }
        }
        ok
    }.getOrDefault(false)
}

/**
 * Apply an inbound delivery/display report: advance the cached outgoing
 * message's status ticks (Delivered=2, Read=3). Called from the sync service
 * alongside [RcsImdn.onReportReceived].
 */
internal suspend fun CommunicateRepository.applyRcsDeliveryReport(
    context: Context,
    messageId: String,
    disposition: ImdnDisposition,
) {
    if (!RcsFeature.enabled) return
    runCatching {
        val db = RcsDatabase.getDatabase(context)
        when (disposition) {
            ImdnDisposition.Delivered -> db.cachedMessageDao().markDelivered(messageId)
            ImdnDisposition.Displayed -> db.cachedMessageDao().markReadStatus(messageId)
            ImdnDisposition.Failed -> {
                db.cachedMessageDao().get(messageId)?.let {
                    db.cachedMessageDao().upsert(it.copy(status = 4))
                }
            }
        }
    }
}

/**
 * Encrypt [body] for [recipient]'s E2EE group when one exists. Returns the
 * framed MLS payload, or null when the conversation is plaintext (no group)
 * or encryption failed (caller falls through to plaintext paths).
 */
internal suspend fun CommunicateRepository.encryptForE2E(
    context: Context,
    recipient: String,
    body: String,
): ByteArray? {
    if (!com.vayunmathur.communicate.data.rcs.RcsFeature.enabled || body.isBlank()) return null
    // Group threads address by remoteId; 1:1 threads by E.164 recipient.
    val conversationId = recipient
    val hasGroup = com.vayunmathur.communicate.data.rcs.e2e.RcsE2E.groupIdFor(context, conversationId) != null
    if (!hasGroup) return null
    val local = com.vayunmathur.communicate.data.rcs.e2e.RcsE2E.localE164(context) ?: return null
    return com.vayunmathur.communicate.data.rcs.e2e.RcsE2E.encryptTo(
        context, local, conversationId, body.toByteArray(Charsets.UTF_8),
    )
}
