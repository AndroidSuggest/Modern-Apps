package com.vayunmathur.communicate.ui

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateAttachment
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.LineChoice
import com.vayunmathur.communicate.data.SmsMessage
import com.vayunmathur.communicate.data.SmsThread
import com.vayunmathur.communicate.data.canSendPoll
import com.vayunmathur.communicate.data.canShareContact
import com.vayunmathur.communicate.data.sendPoll
import com.vayunmathur.communicate.data.shareContact
import com.vayunmathur.communicate.data.updateGoogleVoiceThread
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ConversationScreen(
    threadId: Long,
    address: String,
    line: CommunicateLine,
    remoteId: String?,
    subscriptionId: Int? = null,
    isGroup: Boolean = false,
    participants: List<String> = emptyList(),
    groupTitle: String? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(threadId) { mutableStateOf("") }
    var selectedAttachments by remember(threadId) { mutableStateOf<List<CommunicateAttachment>>(emptyList()) }
    // Bumped after a send to re-fetch the thread.
    var refresh by remember(threadId) { mutableIntStateOf(0) }
    val lineChoices = rememberLineChoices()
    val fixedLineChoice = remember(line, subscriptionId, lineChoices) {
        when (line) {
            CommunicateLine.GoogleVoice -> LineChoice.GoogleVoice
            CommunicateLine.WhatsApp -> LineChoice.WhatsApp
            CommunicateLine.Signal -> LineChoice.Signal
            CommunicateLine.Sim -> lineChoices
                .filterIsInstance<LineChoice.Sim>()
                .firstOrNull { subscriptionId == null || it.subscriptionId == subscriptionId }
                ?: LineChoice.Sim(subscriptionId ?: -1, context.getString(R.string.line_sim))
        }
    }
    // Poll composer and contact picker, offered only on lines that support them.
    var showPollComposer by remember { mutableStateOf(false) }
    val contactPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickContact(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val shared = withContext(Dispatchers.IO) { readSharedContact(context, uri) }
            if (shared == null) {
                AppMessages.show(context.getString(R.string.contact_share_failed))
                return@launch
            }
            val ok = CommunicateRepository.shareContact(
                context = context,
                line = line,
                conversationId = remoteId ?: address,
                contact = shared,
            )
            if (!ok) AppMessages.show(context.getString(R.string.contact_share_failed))
        }
    }

    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        selectedAttachments = uris.map { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            CommunicateAttachment(
                contentUri = uri.toString(),
                mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream",
                fileName = displayNameOf(context, uri),
            )
        }
    }
    val thread = remember(threadId) {
        SmsThread(
            threadId = threadId,
            address = address,
            displayName = null,
            snippet = "",
            timestampMillis = 0,
            unreadCount = 0,
            line = line,
            remoteId = remoteId,
            isGroup = isGroup,
            participants = participants,
            groupTitle = groupTitle,
        )
    }
    val title = produceState(initialValue = address.ifBlank { context.getString(R.string.conversation_title) }, address) {
        value = withContext(Dispatchers.IO) {
            CommunicateRepository.findContactName(context, address)
                ?: address.ifBlank { context.getString(R.string.conversation_title) }
        }
    }
    // Offer "save this number" only for an address that is genuinely an unsaved phone number.
    val isUnknownContact by produceState(initialValue = false, address) {
        value = withContext(Dispatchers.IO) { CommunicateRepository.isUnknownContact(context, address) }
    }
    // For groups, resolve a "Alice, Bob +N" subtitle from the participant addresses (contact names
    // where available). Cheap: runs once off the main thread.
    val groupSubtitle = produceState<String?>(initialValue = null, isGroup, participants) {
        if (!isGroup || participants.isEmpty()) {
            value = null
        } else {
            value = withContext(Dispatchers.IO) {
                val names = participants.take(3).map { p ->
                    CommunicateRepository.findContactName(context, p) ?: p
                }
                val extra = participants.size - names.size
                if (extra > 0) names.joinToString(", ") + " +$extra" else names.joinToString(", ")
            }
        }
    }

    // Opening a SIM thread clears the provider's unread flags. Nothing else writes them back, and
    // the badge is recomputed from the provider, so imported rows would stay unread forever (#562).
    androidx.compose.runtime.LaunchedEffect(threadId, line) {
        if (line == CommunicateLine.Sim) {
            CommunicateRepository.markSimThreadRead(context, threadId)
        }
    }
    // Opening a Google Voice thread marks it read server-side via batchupdateattributes.
    androidx.compose.runtime.LaunchedEffect(remoteId, line) {
        if (line == CommunicateLine.GoogleVoice && remoteId != null) {
            CommunicateRepository.updateGoogleVoiceThread(
                context, remoteId, com.vayunmathur.communicate.data.googlevoice.GoogleVoiceParser.ThreadAction.MarkRead,
            )
        }
    }
    // Foreground polling for the open GV thread (no realtime channel yet).
    androidx.compose.runtime.LaunchedEffect(remoteId, line) {
        if (line == CommunicateLine.GoogleVoice && remoteId != null) {
            while (true) {
                kotlinx.coroutines.delay(10_000)
                refresh++
            }
        }
    }
    // WhatsApp messages land in local Room via the socket→event-processor; poll the cache so inbound
    // (and our own outgoing echo) appear live while the conversation is open. Cheap local reads.
    androidx.compose.runtime.LaunchedEffect(line) {
        if (line == CommunicateLine.WhatsApp) {
            while (true) {
                kotlinx.coroutines.delay(2_000)
                refresh++
            }
        }
    }
    // Send WhatsApp read receipts (and clear the unread badge) for the open conversation. Re-runs on
    // each poll tick; the repository guards against re-sending for an already-read message.
    var waLastReadId by remember(remoteId, address) { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(line, remoteId, refresh) {
        if (line == CommunicateLine.WhatsApp) {
            waLastReadId = CommunicateRepository.markWhatsAppRead(context, remoteId, address, waLastReadId)
        }
    }
    // Signal messages land in local Room via the socket→event-processor; poll so inbound
    // (and our own outgoing echo) appear live while the conversation is open.
    androidx.compose.runtime.LaunchedEffect(line) {
        if (line == CommunicateLine.Signal) {
            while (true) {
                kotlinx.coroutines.delay(2_000)
                refresh++
            }
        }
    }
    // Send Signal read receipts for the open conversation (mirrors WhatsApp).
    var sigLastReadId by remember(remoteId, address) { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(line, remoteId, refresh) {
        if (line == CommunicateLine.Signal) {
            sigLastReadId = try {
                CommunicateRepository.markSignalRead(context, remoteId, address, sigLastReadId)
            } catch (_: Throwable) {
                sigLastReadId
            }
        }
    }

    AppScaffold(
        title = {
            ConversationTitle(
                threadId = threadId,
                isGroup = isGroup,
                groupTitle = groupTitle,
                title = title.value,
                groupSubtitle = groupSubtitle.value,
                address = address,
                line = line,
            )
        },
        onNavigateBack = onBack,
        actions = {
            ConversationActions(
                threadId = threadId,
                address = address,
                line = line,
                remoteId = remoteId,
                isGroup = isGroup,
                participants = participants,
                groupTitle = groupTitle,
                isUnknownContact = isUnknownContact,
                onBack = onBack,
            )
        },
        bottomBar = {
            ComposeSmsRow(
                draft = draft,
                onDraftChange = { draft = it },
                attachments = selectedAttachments,
                // Media and documents both: every line's send path already accepts an arbitrary content type,
                // only this filter was stopping documents from being picked.
                onAttachMedia = { attachmentPicker.launch(arrayOf("image/*", "video/*")) },
                onAttachFile = { attachmentPicker.launch(arrayOf("*/*")) },
                onCreatePoll = if (CommunicateRepository.canSendPoll(line)) {
                    { showPollComposer = true }
                } else {
                    null
                },
                onShareContact = if (CommunicateRepository.canShareContact(line)) {
                    { contactPicker.launch(null) }
                } else {
                    null
                },
                onRemoveAttachment = { attachment ->
                    selectedAttachments = selectedAttachments.filterNot { it.contentUri == attachment.contentUri }
                },
                onSend = {
                    val text = draft.trim()
                    val attachments = selectedAttachments
                    if (text.isEmpty() && attachments.isEmpty()) return@ComposeSmsRow
                    draft = ""
                    selectedAttachments = emptyList()
                    scope.launch {
                        val ok = CommunicateRepository.sendMessage(
                            context,
                            fixedLineChoice,
                            address,
                            text,
                            if (fixedLineChoice is LineChoice.Sim) null else remoteId,
                            attachments,
                            participants = if (isGroup) participants else emptyList(),
                        )
                        if (ok) refresh++ else AppMessages.show(context.getString(R.string.gv_send_failed))
                    }
                },
            )
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        if (showPollComposer) {
            PollComposerDialog(
                onDismiss = { showPollComposer = false },
                onCreate = { question, options ->
                    scope.launch {
                        val ok = CommunicateRepository.sendPoll(
                            context = context,
                            line = line,
                            conversationId = remoteId ?: address,
                            question = question,
                            options = options,
                        )
                        if (ok) refresh++ else AppMessages.show(context.getString(R.string.poll_send_failed))
                    }
                },
            )
        }
        // A changed Signal identity key blocks sending until the user verifies it, so the warning has to
        // be in front of them here rather than only in a notification.
        var safetyNumber by remember(remoteId, address) { mutableStateOf<Pair<String, String>?>(null) }
        androidx.compose.runtime.LaunchedEffect(line, remoteId, address, refresh) {
            safetyNumber = if (line == CommunicateLine.Signal) {
                try {
                    CommunicateRepository.signalPendingIdentityChange(context, remoteId, address)
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }
        }
        safetyNumber?.let { (number, keyHex) ->
            SafetyNumberBanner(
                safetyNumber = number,
                onAccept = {
                    scope.launch {
                        val ok = CommunicateRepository.acceptSignalIdentity(context, remoteId, address, keyHex)
                        if (ok) {
                            safetyNumber = null
                            refresh++
                        } else {
                            AppMessages.show(context.getString(R.string.signal_safety_number_accept_failed))
                        }
                    }
                },
                modifier = Modifier.padding(padding),
            )
        }
        // Google Voice threads don't require the default-SMS role or READ_SMS; only SIM does.
        if (line == CommunicateLine.GoogleVoice) {
            MessagesList(padding, refresh) {
                CommunicateRepository.loadSmsMessagesMerged(context, thread)
            }
            return@AppScaffold
        }
        DefaultSmsGate(modifier = Modifier.padding(padding)) { roleRevision ->
            PermissionGate(
                permission = Manifest.permission.READ_SMS,
                message = stringResource(R.string.permission_sms_message),
                modifier = Modifier.padding(padding),
            ) { permissionRevision ->
                val messages = produceState<List<SmsMessage>?>(initialValue = null, threadId, roleRevision, permissionRevision, refresh) {
                    value = withContext(Dispatchers.IO) { CommunicateRepository.loadSmsMessagesMerged(context, thread) }
                }
                MessagesContent(padding, messages.value)
            }
        }
    }
}
