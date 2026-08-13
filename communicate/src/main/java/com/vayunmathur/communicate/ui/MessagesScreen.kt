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
import android.content.Context
import android.telephony.TelephonyManager
import androidx.compose.foundation.layout.heightIn
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.vayunmathur.communicate.data.CommunicateContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MessagesScreen(onOpenThread: (SmsThread) -> Unit, onOpenAccounts: () -> Unit) {
    val context = LocalContext.current
    val lineChoices = rememberLineChoices()
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
                // Open the contact picker to choose a recipient + line.
                showPicker = true
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
                LineBadge(thread.line, thread.subscriptionId, modifier = Modifier.padding(start = 6.dp))
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
 * FAB flow when more than one line is available: enter a recipient number and pick which line
 * (SIM or Google Voice) to compose from, then open that conversation.
 */
@Composable
private fun NewMessagePicker(
    choices: List<com.vayunmathur.communicate.data.LineChoice>,
    onDismiss: () -> Unit,
    onCompose: (com.vayunmathur.communicate.data.LineChoice, String) -> Unit,
) {
    val context = LocalContext.current
    val region = remember { deviceRegion(context) }
    var query by remember { mutableStateOf("") }
    var selected by remember(choices) { mutableStateOf(choices.firstOrNull()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_message)) },
        text = {
            Column {
                val sel = selected
                if (sel != null && choices.size > 1) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 8.dp),
                    ) {
                        Text(
                            stringResource(R.string.choose_line),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        LineSelector(choices = choices, selected = sel, onSelect = { selected = it })
                    }
                }
                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search name or number") },
                    singleLine = true,
                )
                Spacer(Modifier.size(8.dp))
                PermissionGate(
                    permission = Manifest.permission.READ_CONTACTS,
                    message = "Allow contacts access to pick a recipient.",
                ) { rev ->
                    val contacts by produceState(initialValue = emptyList<CommunicateContact>(), rev) {
                        value = withContext(Dispatchers.IO) { CommunicateRepository.loadContacts(context) }
                    }
                    val q = query.trim()
                    val qDigits = q.filter { it.isDigit() }
                    val filtered = if (q.isEmpty()) {
                        contacts
                    } else {
                        contacts.filter { c ->
                            c.name.contains(q, ignoreCase = true) ||
                                (qDigits.isNotEmpty() && c.phoneNumber.filter { it.isDigit() }.contains(qDigits))
                        }
                    }
                    val exactExists = qDigits.isNotEmpty() &&
                        contacts.any { it.phoneNumber.filter { c -> c.isDigit() }.endsWith(qDigits) }
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                        // Fallback: message a raw number that isn't in contacts (shown formatted).
                        if (qDigits.length >= 4 && !exactExists) {
                            item {
                                ContactPickRow(title = "Send to ${formatNumber(q, region)}", subtitle = null) {
                                    selected?.let { onCompose(it, q) }
                                }
                            }
                        }
                        items(filtered, key = { it.id }) { c ->
                            ContactPickRow(title = c.name, subtitle = c.phoneNumber) {
                                selected?.let { onCompose(it, c.phoneNumber) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.clear)) } },
    )
}

@Composable
private fun ContactPickRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(title, fontWeight = FontWeight.Medium)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Device region (SIM > network > locale) for phone-number formatting/parsing. */
private fun deviceRegion(context: Context): String {
    val tm = runCatching { context.getSystemService(TelephonyManager::class.java) }.getOrNull()
    return (tm?.simCountryIso?.takeIf { it.isNotBlank() } ?: tm?.networkCountryIso)?.uppercase()
        ?: context.resources.configuration.locales[0].country.ifEmpty { "US" }
}

/** Human-friendly display of a typed number (national format), falling back to the raw input. */
private fun formatNumber(raw: String, region: String): String = runCatching {
    val util = PhoneNumberUtil.getInstance()
    util.format(util.parse(raw, region), PhoneNumberUtil.PhoneNumberFormat.NATIONAL)
}.getOrDefault(raw)
