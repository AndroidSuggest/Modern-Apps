package com.vayunmathur.communicate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.MessageStatus
import com.vayunmathur.communicate.data.SmsMessage
import com.vayunmathur.communicate.data.findContactName
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun MessageBubble(message: SmsMessage) {
    val context = LocalContext.current
    val alignment = if (message.outgoing) Alignment.End else Alignment.Start
    val bubbleColor = if (message.outgoing) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (message.outgoing) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val shape = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = if (message.outgoing) 20.dp else 4.dp,
        bottomEnd = if (message.outgoing) 4.dp else 20.dp,
    )

    val waSd = if (message.line == CommunicateLine.WhatsApp) {
        com.vayunmathur.communicate.data.whatsapp.WhatsAppServiceData.parse(message.serviceData)
    } else {
        null
    }
    val sigSd = if (message.line == CommunicateLine.Signal) {
        com.vayunmathur.communicate.data.signal.SignalServiceData.parse(message.serviceData)
    } else {
        null
    }
    // Canonical name used for bubble extras.
    val sd: Any? = waSd ?: sigSd
    val sdIsRevoked: Boolean = waSd?.isRevoked == true || sigSd?.isRevoked == true
    val sdIsEdited: Boolean = waSd?.isEdited == true || sigSd?.isEdited == true
    val sdQuotedBody: String? = waSd?.quotedBody ?: sigSd?.quotedBody
    val sdQuotedSender: String? = waSd?.quotedSender ?: sigSd?.quotedSender
    val sdPollQuestion: String? = waSd?.pollQuestion ?: sigSd?.pollQuestion
    val sdSenderName: String? = waSd?.senderName ?: sigSd?.senderName
    val bodyText = when {
        sdIsRevoked -> "🚫 This message was deleted"
        else -> message.body
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        // Group sender name (incoming group messages). WhatsApp carries it in serviceData;
        // other lines (SIM/GV groups) resolve it from the per-message sender address.
        val senderLabel = produceState<String?>(initialValue = null, message.id, sdSenderName, message.senderAddress) {
            value = when {
                message.outgoing -> null
                sdSenderName != null -> sdSenderName
                message.senderAddress != null -> withContext(Dispatchers.IO) {
                    CommunicateRepository.findContactName(context, message.senderAddress) ?: message.senderAddress
                }
                else -> null
            }
        }
        senderLabel.value?.let { label ->
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
        }
        // Quoted reply preview.
        if (sdQuotedBody != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.widthIn(max = 320.dp).padding(horizontal = 14.dp, vertical = 2.dp),
            ) {
                Text(
                    (sdQuotedSender?.let { "$it: " } ?: "") + sdQuotedBody,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (bodyText.isNotBlank()) {
            Surface(
                color = bubbleColor,
                contentColor = contentColor,
                shape = shape,
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(bodyText, fontSize = 15.sp)
                    // Poll rendering.
                    if (sdPollQuestion != null) {
                        Spacer(Modifier.size(4.dp))
                        Text("📊 $sdPollQuestion", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        (waSd?.pollOptions ?: emptyList()).forEach { opt ->
                            Text("• ${opt.name} (${opt.voteCount})", fontSize = 13.sp)
                        }
                        (sigSd?.pollOptions ?: emptyList()).forEach { opt ->
                            Text("• ${opt.name} (${opt.voteCount})", fontSize = 13.sp)
                        }
                    }
                    if (sdIsEdited) {
                        Text(
                            "edited",
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
        // Reaction chips (WhatsApp + Signal).
        val reactions: List<Pair<String, Int>> = when {
            waSd != null && waSd.reactions.isNotEmpty() -> waSd.reactions.map { it.emoji to it.count }
            sigSd != null && sigSd.reactions.isNotEmpty() -> sigSd.reactions.map { it.emoji to it.count }
            else -> emptyList()
        }
        if (reactions.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                reactions.forEach { (emoji, count) ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(50),
                    ) {
                        Text(
                            if (count > 1) "$emoji $count" else emoji,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
        message.attachments.forEach { attachment ->
            MessageAttachment(
                attachment = attachment,
                bubbleColor = bubbleColor,
                contentColor = contentColor,
                shape = shape,
            )
        }
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatDateTime(context, message.timestampMillis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (message.outgoing) {
                Spacer(Modifier.size(4.dp))
                MessageStatusTicks(message.status)
            }
        }
    }
}

/**
 * Renders WhatsApp-style delivery ticks for an outgoing message:
 * grey ✓ (Sent), grey ✓✓ (Delivered), blue ✓✓ (Read). [MessageStatus.Failed] shows a red "!".
 * [MessageStatus.None] renders nothing (lines without receipts).
 */
@Composable
internal fun MessageStatusTicks(status: MessageStatus) {
    when (status) {
        MessageStatus.None -> Unit
        MessageStatus.Failed -> Text(
            "!",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold,
        )
        else -> {
            val glyph = if (status == MessageStatus.Sent) "✓" else "✓✓"
            val color = if (status == MessageStatus.Read) {
                // WhatsApp's read-receipt blue (not theme-tinted).
                Color(0xFF34B7F1)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                glyph,
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}
