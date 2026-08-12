package com.vayunmathur.communicate.ui

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.IconSms
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.SmsThread
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceSession
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MessagesScreen(onOpenThread: (SmsThread) -> Unit, onOpenAccounts: () -> Unit) {
    val context = LocalContext.current
    val session = remember { GoogleVoiceSession.get(context) }
    val gvSignedIn by session.signedInFlow.collectAsState(initial = false)
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        NewMessagePicker(
            onDismiss = { showPicker = false },
            onSim = {
                showPicker = false
                CommunicateRepository.openSmsComposer(context)
            },
            onGoogleVoice = { number ->
                showPicker = false
                onOpenThread(
                    SmsThread(
                        threadId = CommunicateRepository.stableThreadId(number),
                        address = number,
                        displayName = null,
                        snippet = "",
                        timestampMillis = System.currentTimeMillis(),
                        unreadCount = 0,
                        line = CommunicateLine.GoogleVoice,
                        remoteId = null,
                    ),
                )
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.messages_title)) },
                actions = {
                    IconButton(onClick = onOpenAccounts) { IconPerson() }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (gvSignedIn) showPicker = true else CommunicateRepository.openSmsComposer(context)
            }) {
                IconAdd()
            }
        },
    ) { padding ->
        DefaultSmsGate(modifier = Modifier.padding(padding)) { roleRevision ->
            PermissionGate(
                permission = Manifest.permission.READ_SMS,
                message = stringResource(R.string.permission_sms_message),
                modifier = Modifier.padding(padding),
            ) { permissionRevision ->
                // Foreground polling: Google Voice has no cheap realtime channel wired up yet,
                // so while this screen is shown we re-fetch the merged inbox on an interval.
                // (The Punctual/WebChannel realtime upgrade is noted as future work.)
                var tick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
                androidx.compose.runtime.LaunchedEffect(roleRevision, permissionRevision) {
                    while (true) {
                        kotlinx.coroutines.delay(15_000)
                        tick++
                    }
                }
                val threads = produceState<List<SmsThread>?>(initialValue = null, roleRevision, permissionRevision, tick) {
                    value = withContext(Dispatchers.IO) { CommunicateRepository.loadSmsThreadsMerged(context) }
                }

                when (val rows = threads.value) {
                    null -> com.vayunmathur.library.ui.LoadingState(Modifier.padding(padding))
                    emptyList<SmsThread>() -> EmptyState(
                        title = stringResource(R.string.empty_messages),
                        icon = { IconSms() },
                        modifier = Modifier.padding(padding),
                    )
                    else -> LazyColumn(
                        modifier = Modifier
                            .padding(padding)
                            .fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 88.dp),
                    ) {
                        items(rows, key = { it.threadId }) { thread ->
                            MessageThreadRow(thread = thread, onClick = { onOpenThread(thread) })
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageThreadRow(thread: SmsThread, onClick: () -> Unit) {
    val context = LocalContext.current
    val title = thread.displayName ?: thread.address.ifBlank { stringResource(R.string.conversation_title) }
    ListItem(
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    fontWeight = if (thread.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                LineBadge(thread.line, modifier = Modifier.padding(start = 6.dp))
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(
                    formatDateTime(context, thread.timestampMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        supportingContent = {
            Text(
                thread.snippet,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (thread.unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        leadingContent = { ThreadAvatar(title = title) },
        trailingContent = {
            if (thread.unreadCount > 0) UnreadBadge(thread.unreadCount)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun ThreadAvatar(title: String) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initialsFor(title),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Text(
            count.toString(),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * FAB flow when a Google Voice line is connected: pick which line to compose from. SIM opens
 * the system composer (we're the default SMS app); Google Voice collects a recipient number
 * and opens a new GV conversation.
 */
@Composable
private fun NewMessagePicker(
    onDismiss: () -> Unit,
    onSim: () -> Unit,
    onGoogleVoice: (String) -> Unit,
) {
    var number by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_line)) },
        text = {
            Column {
                ListItem(
                    leadingContent = { IconSms() },
                    content = { Text(stringResource(R.string.account_sim)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(onClick = onSim),
                )
                HorizontalDivider()
                ListItem(
                    content = { Text(stringResource(R.string.account_google_voice)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it.filter { c -> c.isDigit() || c == '+' } },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.phone_number)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (number.isNotBlank()) onGoogleVoice(number) },
                enabled = number.isNotBlank(),
            ) { Text(stringResource(R.string.account_google_voice)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.clear)) }
        },
    )
}
