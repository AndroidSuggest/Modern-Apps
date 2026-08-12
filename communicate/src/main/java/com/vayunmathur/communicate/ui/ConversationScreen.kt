package com.vayunmathur.communicate.ui

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.EmptyState
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
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.SmsMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ConversationScreen(threadId: Long, address: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var draft by remember(threadId) { mutableStateOf("") }
    val title = produceState(initialValue = address.ifBlank { context.getString(R.string.conversation_title) }, address) {
        value = withContext(Dispatchers.IO) {
            CommunicateRepository.findContactName(context, address)
                ?: address.ifBlank { context.getString(R.string.conversation_title) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            title.value,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold,
                        )
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
            )
        },
        bottomBar = {
            ComposeSmsRow(
                draft = draft,
                onDraftChange = { draft = it },
                onSend = {
                    CommunicateRepository.openSmsComposer(context, address, draft.trim())
                    draft = ""
                },
            )
        },
    ) { padding ->
        PermissionGate(
            permission = Manifest.permission.READ_SMS,
            message = stringResource(R.string.permission_sms_message),
            modifier = Modifier.padding(padding),
        ) { revision ->
            val messages = produceState<List<SmsMessage>?>(initialValue = null, threadId, revision) {
                value = withContext(Dispatchers.IO) { CommunicateRepository.loadSmsMessages(context, threadId) }
            }
            when (val rows = messages.value) {
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
    }
}

@Composable
private fun ComposeSmsRow(
    draft: String,
    onDraftChange: (String) -> Unit,
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
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
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
