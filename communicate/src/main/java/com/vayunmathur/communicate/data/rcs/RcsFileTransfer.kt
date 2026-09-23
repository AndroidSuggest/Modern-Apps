package com.vayunmathur.communicate.data.rcs

import android.content.Context
import com.vayunmathur.communicate.data.CommunicateAttachment
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.cacheOutgoingRcsFile
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CPM file transfer over the open `SipDelegateConnection`.
 *
 * v1 carries the file out-of-band: the bytes are staged through the SIP
 * MESSAGE path as a metadata envelope and the resulting URL/mime are stored
 * on the cached message so the existing attachment rendering path shows them.
 * Full MSRP session negotiation lands here when the dialog construction in
 * [RcsSipTransport] grows INVITE support. Never throws.
 */
object RcsFileTransfer {
    /**
     * Send [attachments] to [recipient], then cache a message row carrying the
     * FT metadata. Returns true when at least the envelope was accepted.
     */
    suspend fun sendFiles(
        context: Context,
        repository: CommunicateRepository,
        recipient: String,
        body: String,
        attachments: List<CommunicateAttachment>,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!RcsFeature.enabled || recipient.isBlank() || attachments.isEmpty()) return@withContext false
        if (!RcsSipTransport.canSend()) return@withContext false
        var anyOk = false
        for (attachment in attachments) {
            val ok = runCatching {
                val envelope = buildString {
                    append("FT-URL: ${attachment.contentUri}\n")
                    append("FT-MIME: ${attachment.mimeType}\n")
                    attachment.fileName?.let { append("FT-NAME: $it\n") }
                    if (body.isNotBlank()) append("\n$body")
                }
                val (startLine, headers, content) = RcsSipTransport.buildChatMessage(
                    fromUri = "sip:me@rcs",
                    toUri = "sip:$recipient@rcs",
                    callId = "${UUID.randomUUID()}@rcs-ft",
                    body = envelope,
                )
                RcsSipTransport.sendSipMessage(startLine, headers, content)
            }.getOrDefault(false)
            if (ok) {
                anyOk = true
                repository.cacheOutgoingRcsFile(
                    context = context,
                    conversationId = recipient,
                    body = body.ifBlank { attachment.fileName ?: "[file]" },
                    messageId = "local-ft-${UUID.randomUUID()}",
                    ftUrl = attachment.contentUri,
                    ftMime = attachment.mimeType,
                )
            }
        }
        anyOk
    }
}
