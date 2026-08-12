package com.vayunmathur.communicate.ui

import android.Manifest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.IconArchive
import com.vayunmathur.library.ui.IconAttachment
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSend
import com.vayunmathur.library.ui.IconSms
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.SmsMessage
import com.vayunmathur.communicate.data.SmsThread
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Intent

@Composable
fun ConversationScreen(
    threadId: Long,
    address: String,
    line: CommunicateLine,
    remoteId: String?,
    subscriptionId: Int? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(threadId) { mutableStateOf("") }
    // Bumped after a send to re-fetch the thread.
    var refresh by remember(threadId) { mutableIntStateOf(0) }
    val lineChoices = rememberLineChoices()
    // Default the send line to this thread's line (matching SIM subscription when known).
    var selectedLine by remember(threadId, lineChoices) {
        mutableStateOf(
            when (line) {
                CommunicateLine.GoogleVoice ->
                    lineChoices.firstOrNull { it is com.vayunmathur.communicate.data.LineChoice.GoogleVoice }
                CommunicateLine.Sim ->
                    lineChoices.firstOrNull { it is com.vayunmathur.communicate.data.LineChoice.Sim && it.subscriptionId == subscriptionId }
            } ?: lineChoices.firstOrNull(),
        )
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
        )
    }
    val title = produceState(initialValue = address.ifBlank { context.getString(R.string.conversation_title) }, address) {
        value = withContext(Dispatchers.IO) {
            CommunicateRepository.findContactName(context, address)
                ?: address.ifBlank { context.getString(R.string.conversation_title) }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                title.value,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold,
                            )
                            LineBadge(line, modifier = Modifier.padding(start = 8.dp))
                        }
                        if (address.isNotBlank() && title.value != address) {
                            Text(
                                address,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = { IconNavigation(onBack) },
                actions = {
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
                },
            )
        },
        bottomBar = {
            ComposeSmsRow(
                draft = draft,
                onDraftChange = { draft = it },
                lineSelector = {
                    val sel = selectedLine
                    if (lineChoices.size > 1 && sel != null) {
                        LineSelector(choices = lineChoices, selected = sel, onSelect = { selectedLine = it })
                    }
                },
                onSend = {
                    val text = draft.trim()
                    val choice = selectedLine
                    if (text.isEmpty() || choice == null) return@ComposeSmsRow
                    draft = ""
                    scope.launch {
                        val ok = CommunicateRepository.sendMessage(
                            context,
                            choice,
                            address,
                            text,
                            if (choice is com.vayunmathur.communicate.data.LineChoice.GoogleVoice) remoteId else null,
                        )
                        if (ok) refresh++ else AppMessages.show(context.getString(R.string.gv_send_failed))
                    }
                },
            )
        },
    ) { padding ->
        // Google Voice threads don't require the default-SMS role or READ_SMS; only SIM does.
        if (line == CommunicateLine.GoogleVoice) {
            MessagesList(padding, refresh) {
                CommunicateRepository.loadSmsMessagesMerged(context, thread)
            }
            return@Scaffold
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

@Composable
private fun MessagesList(padding: PaddingValues, refresh: Int, load: suspend () -> List<SmsMessage>) {
    val messages = produceState<List<SmsMessage>?>(initialValue = null, refresh) {
        value = withContext(Dispatchers.IO) { load() }
    }
    MessagesContent(padding, messages.value)
}

@Composable
private fun MessagesContent(padding: PaddingValues, rows: List<SmsMessage>?) {
    when (rows) {
        null -> com.vayunmathur.library.ui.LoadingState(Modifier.padding(padding))
        emptyList<SmsMessage>() -> EmptyState(
            title = stringResource(R.string.empty_messages),
            icon = { IconSms() },
            modifier = Modifier.padding(padding),
        )
        else -> LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(rows, key = { it.id }) { message ->
                MessageBubble(message)
            }
        }
    }
}

@Composable
private fun ComposeSmsRow(
    draft: String,
    onDraftChange: (String) -> Unit,
    lineSelector: @Composable () -> Unit,
    onSend: () -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            lineSelector()
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                placeholder = { Text(stringResource(R.string.message_hint)) },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
            )
            IconButton(onClick = onSend, enabled = draft.isNotBlank()) {
                IconSend()
            }
        }
    }
}

@Composable
private fun MessageBubble(message: SmsMessage) {
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

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        if (message.body.isNotBlank()) {
            Surface(
                color = bubbleColor,
                contentColor = contentColor,
                shape = shape,
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Text(
                    message.body,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    fontSize = 15.sp,
                )
            }
        }
        message.attachments.forEach { attachment ->
            Surface(
                color = bubbleColor,
                contentColor = contentColor,
                shape = shape,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .padding(top = 2.dp)
                    .clickable {
                        ExternalIntents.launch(
                            context,
                            Intent(Intent.ACTION_VIEW, attachment.contentUri.toUri()),
                        )
                    },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconAttachment()
                    Spacer(Modifier.size(6.dp))
                    Text(attachment.mimeType, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
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
                Text(
                    stringResource(R.string.send),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
