package com.vayunmathur.communicate.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.SmsThread
import com.vayunmathur.communicate.data.deleteConversation
import com.vayunmathur.communicate.data.updateGoogleVoiceThread
import com.vayunmathur.library.ui.IconArchive
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.sharedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ConversationTitle(
    threadId: Long,
    isGroup: Boolean,
    groupTitle: String?,
    title: String,
    groupSubtitle: String?,
    address: String,
    line: CommunicateLine,
) {
    val displayTitle = if (isGroup) (groupTitle ?: title) else title
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                displayTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.sharedText("communicate-thread-title-$threadId"),
            )
            LineBadge(line, modifier = Modifier.padding(start = 8.dp))
        }
        if (isGroup) {
            groupSubtitle?.let { subtitle ->
                Text(
                    subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (address.isNotBlank() && title != address) {
            Text(
                address,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ConversationActions(
    threadId: Long,
    address: String,
    line: CommunicateLine,
    remoteId: String?,
    isGroup: Boolean,
    participants: List<String>,
    groupTitle: String?,
    isUnknownContact: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    if (showDeleteConfirm) {
        com.vayunmathur.library.ui.ConfirmDialog(
            title = stringResource(com.vayunmathur.communicate.R.string.delete_conversation_title),
            message = stringResource(com.vayunmathur.communicate.R.string.delete_conversation_message),
            confirmLabel = stringResource(com.vayunmathur.library.ui.R.string.delete),
            dismissLabel = stringResource(com.vayunmathur.library.ui.R.string.cancel),
            destructive = true,
            onConfirm = {
                showDeleteConfirm = false
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        CommunicateRepository.deleteConversation(
                            context,
                            SmsThread(
                                threadId = threadId,
                                address = address,
                                displayName = null,
                                snippet = "",
                                timestampMillis = 0L,
                                unreadCount = 0,
                                line = line,
                                remoteId = remoteId,
                                isGroup = isGroup,
                                participants = participants,
                                groupTitle = groupTitle,
                            ),
                        )
                    }
                    if (ok) onBack() else AppMessages.show(context.getString(com.vayunmathur.communicate.R.string.delete_failed))
                }
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
    // Calling is per line: SIM and Google Voice hand off to Telecom, WhatsApp and Signal place an
    // in-app WebRTC call. Signal groups call the SFU; other lines have no group calling.
    val canCall = address.isNotBlank() && CommunicateRepository.canPlaceCall(line) &&
        (!isGroup || CommunicateRepository.canPlaceGroupCall(line))
    if (canCall) {
        IconButton(onClick = {
            scope.launch {
                val placed = CommunicateRepository.placeCallForLine(
                    context = context,
                    line = line,
                    address = address,
                    remoteId = remoteId,
                )
                if (!placed) AppMessages.show(context.getString(R.string.call_failed))
            }
        }) { com.vayunmathur.library.ui.IconCall() }
        // Only the in-app WebRTC lines can carry video; Telecom lines cannot.
        if (CommunicateRepository.canPlaceVideoCall(line)) {
            IconButton(onClick = {
                scope.launch {
                    val placed = CommunicateRepository.placeCallForLine(
                        context = context,
                        line = line,
                        address = address,
                        remoteId = remoteId,
                        video = true,
                    )
                    if (!placed) AppMessages.show(context.getString(R.string.call_failed))
                }
            }) { com.vayunmathur.library.ui.IconVideoCamera() }
        }
    }
    if (line == CommunicateLine.GoogleVoice && remoteId != null) {
        IconButton(onClick = {
            scope.launch {
                val ok = CommunicateRepository.updateGoogleVoiceThread(
                    context, remoteId,
                    com.vayunmathur.communicate.data.googlevoice.GoogleVoiceParser.ThreadAction.Archive,
                )
                if (ok) onBack() else AppMessages.show(context.getString(R.string.gv_action_failed))
            }
        }) { IconArchive() }
    }
    com.vayunmathur.library.ui.OverflowMenu(icon = { com.vayunmathur.library.ui.IconMoreVert() }) {
        // Only worth offering when the address is a bare number we could not resolve to a contact.
        if (!isGroup && isUnknownContact) {
            Item(
                text = stringResource(R.string.contact_create_new),
                leadingIcon = { com.vayunmathur.library.ui.IconPersonAdd() },
                onClick = { ContactIntents.createNew(context, address) },
            )
            Item(
                text = stringResource(R.string.contact_add_to_existing),
                leadingIcon = { com.vayunmathur.library.ui.IconPerson() },
                onClick = { ContactIntents.addToExisting(context, address) },
            )
        }
        Item(
            text = stringResource(com.vayunmathur.library.ui.R.string.delete),
            leadingIcon = { com.vayunmathur.library.ui.IconDelete() },
            onClick = { showDeleteConfirm = true },
        )
    }
}
