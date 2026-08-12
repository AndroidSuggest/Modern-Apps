package com.vayunmathur.communicate.ui

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.FilledIconButton
import com.vayunmathur.library.ui.IconBackspace
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.IconContacts
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateContact
import com.vayunmathur.communicate.data.CommunicateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DialerScreen() {
    val context = LocalContext.current
    var number by remember { mutableStateOf("") }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.dialer_title)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            DialPad(
                number = number,
                onAppend = { number += it },
                onBackspace = { number = number.dropLast(1) },
                onClear = { number = "" },
                onCall = { CommunicateRepository.placeCall(context, number) },
            )
            PermissionGate(
                permission = Manifest.permission.READ_CONTACTS,
                message = stringResource(R.string.permission_contacts_message),
                modifier = Modifier.weight(1f),
            ) { revision ->
                val contacts = produceState<List<CommunicateContact>?>(initialValue = null, revision) {
                    value = withContext(Dispatchers.IO) { CommunicateRepository.loadContacts(context) }
                }
                when (val rows = contacts.value) {
                    null -> com.vayunmathur.library.ui.LoadingState(Modifier.weight(1f))
                    emptyList<CommunicateContact>() -> EmptyState(
                        title = stringResource(R.string.empty_contacts),
                        icon = { IconContacts() },
                        modifier = Modifier.weight(1f),
                    )
                    else -> LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 12.dp),
                    ) {
                        item {
                            Text(
                                stringResource(R.string.contacts),
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        items(rows, key = { "${it.id}-${it.phoneNumber}" }) { contact ->
                            ContactRow(contact) {
                                CommunicateRepository.placeCall(context, contact.phoneNumber)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialPad(
    number: String,
    onAppend: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onCall: () -> Unit,
) {
    val keys = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("*", "0", "#"),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = number.ifEmpty { stringResource(R.string.phone_number) },
            style = MaterialTheme.typography.headlineMedium,
            color = if (number.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        keys.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                row.forEach { digit ->
                    DialKey(digit = digit, onClick = { onAppend(digit) })
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClear, enabled = number.isNotEmpty()) {
                Text(stringResource(R.string.clear))
            }
            FilledIconButton(onClick = onCall, enabled = number.isNotBlank(), modifier = Modifier.size(64.dp)) {
                IconCall()
            }
            IconButton(onClick = onBackspace, enabled = number.isNotEmpty()) {
                IconBackspace()
            }
        }
    }
}

@Composable
private fun DialKey(digit: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(64.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(digit, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ContactRow(contact: CommunicateContact, onClick: () -> Unit) {
    ListItem(
        content = {
            Text(
                contact.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
        },
        supportingContent = { Text("${contact.label}  ${contact.phoneNumber}", maxLines = 1) },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    initialsFor(contact.name),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        trailingContent = { IconCall() },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
