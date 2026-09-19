package com.vayunmathur.communicate.ui

import android.Manifest
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.SmsThread
import com.vayunmathur.communicate.data.createSignalGroup
import com.vayunmathur.communicate.data.createWhatsAppGroup
import com.vayunmathur.communicate.data.deleteConversation
import com.vayunmathur.communicate.data.getOrCreateSmsGroupThreadId
import com.vayunmathur.communicate.data.getOrCreateSmsThreadId
import com.vayunmathur.communicate.data.isWhatsAppConnected
import com.vayunmathur.communicate.data.loadSmsThreadsMerged
import com.vayunmathur.communicate.data.stableThreadId
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.IconSms
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MessagesScreen(onOpenThread: (SmsThread) -> Unit, onOpenAccounts: () -> Unit) {
    val context = LocalContext.current
    val lineChoices = rememberLineChoices()
    val scope = rememberCoroutineScope()
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        NewMessagePicker(
            choices = lineChoices,
            onDismiss = { showPicker = false },
            onCompose = { choice, number ->
                showPicker = false
                val sim = choice as? com.vayunmathur.communicate.data.LineChoice.Sim
                val threadId = if (sim != null) {
                    CommunicateRepository.getOrCreateSmsThreadId(context, number)
                        ?: CommunicateRepository.stableThreadId(number)
                } else {
                    CommunicateRepository.stableThreadId(number)
                }
                onOpenThread(
                    SmsThread(
                        threadId = threadId,
                        address = number,
                        displayName = null,
                        snippet = "",
                        timestampMillis = System.currentTimeMillis(),
                        unreadCount = 0,
                        line = choice.category,
                        remoteId = null,
                        subscriptionId = sim?.subscriptionId,
                    ),
                )
            },
            onCreateGroup = { choice, subject, contacts ->
                showPicker = false
                scope.launch {
                    when (choice.category) {
                        CommunicateLine.WhatsApp -> {
                            if (!CommunicateRepository.isWhatsAppConnected()) {
                                AppMessages.show("WhatsApp isn't connected — the number may be logged out or banned. Re-register in Accounts.")
                                return@launch
                            }
                            val groupJid = withContext(Dispatchers.IO) {
                                CommunicateRepository.createWhatsAppGroup(context, subject, contacts)
                            }
                            if (groupJid == null) {
                                AppMessages.show("Couldn't create the group (server rejected it)")
                            } else {
                                onOpenThread(
                                    SmsThread(
                                        threadId = CommunicateRepository.stableThreadId(groupJid),
                                        address = groupJid,
                                        displayName = subject.ifBlank { null },
                                        snippet = "",
                                        timestampMillis = System.currentTimeMillis(),
                                        unreadCount = 0,
                                        line = CommunicateLine.WhatsApp,
                                        remoteId = groupJid,
                                        isGroup = true,
                                        participants = contacts,
                                        groupTitle = subject.ifBlank { null },
                                    ),
                                )
                            }
                        }
                        CommunicateLine.Signal -> {
                            val groupId = withContext(Dispatchers.IO) {
                                CommunicateRepository.createSignalGroup(context, subject, contacts)
                            }
                            if (groupId == null) {
                                AppMessages.show("Couldn't create the Signal group (server rejected it)")
                            } else {
                                onOpenThread(
                                    SmsThread(
                                        threadId = CommunicateRepository.stableThreadId(groupId),
                                        address = groupId,
                                        displayName = subject.ifBlank { null },
                                        snippet = "",
                                        timestampMillis = System.currentTimeMillis(),
                                        unreadCount = 0,
                                        line = CommunicateLine.Signal,
                                        remoteId = groupId,
                                        isGroup = true,
                                        participants = contacts,
                                        groupTitle = subject.ifBlank { null },
                                    ),
                                )
                            }
                        }
                        CommunicateLine.Sim -> {
                            val sim = choice as? com.vayunmathur.communicate.data.LineChoice.Sim
                            val groupThreadId = withContext(Dispatchers.IO) {
                                CommunicateRepository.getOrCreateSmsGroupThreadId(context, contacts)
                            }
                            if (groupThreadId == null) {
                                AppMessages.show("Couldn't create the group thread")
                            } else {
                                onOpenThread(
                                    SmsThread(
                                        threadId = groupThreadId,
                                        address = contacts.joinToString(", "),
                                        displayName = subject.ifBlank { null },
                                        snippet = "",
                                        timestampMillis = System.currentTimeMillis(),
                                        unreadCount = 0,
                                        line = CommunicateLine.Sim,
                                        remoteId = null,
                                        subscriptionId = sim?.subscriptionId,
                                        isGroup = true,
                                        participants = contacts,
                                        groupTitle = subject.ifBlank { null },
                                    ),
                                )
                            }
                        }
                        else -> AppMessages.show("Groups aren't supported on this line")
                    }
                }
            },
        )
    }

    AppScaffold(
        title = stringResource(R.string.messages_title),
        actions = {
            IconButton(onClick = onOpenAccounts) { IconPerson() }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                // Open the contact picker to choose a recipient + line.
                showPicker = true
            }) {
                IconAdd()
            }
        },
        scrollBehavior = appBarScrollBehavior(),
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
                    else -> {
                        var pendingDelete by remember { mutableStateOf<SmsThread?>(null) }
                        LazyColumn(
                            modifier = Modifier
                                .padding(padding)
                                .fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 88.dp),
                        ) {
                            items(rows, key = { it.threadId }) { thread ->
                                MessageThreadRow(
                                    thread = thread,
                                    onClick = { onOpenThread(thread) },
                                    onDelete = { pendingDelete = thread },
                                )
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            }
                        }
                        val toDelete = pendingDelete
                        if (toDelete != null) {
                            com.vayunmathur.library.ui.ConfirmDialog(
                                title = stringResource(R.string.delete_conversation_title),
                                message = stringResource(R.string.delete_conversation_message),
                                confirmLabel = stringResource(com.vayunmathur.library.ui.R.string.delete),
                                dismissLabel = stringResource(com.vayunmathur.library.ui.R.string.cancel),
                                destructive = true,
                                onConfirm = {
                                    pendingDelete = null
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) {
                                            CommunicateRepository.deleteConversation(context, toDelete)
                                        }
                                        if (ok) tick++ else AppMessages.show(context.getString(R.string.delete_failed))
                                    }
                                },
                                onDismiss = { pendingDelete = null },
                            )
                        }
                    }
                }
            }
        }
    }
}
