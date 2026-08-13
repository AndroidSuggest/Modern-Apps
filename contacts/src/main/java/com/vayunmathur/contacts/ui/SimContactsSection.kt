package com.vayunmathur.contacts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.SimContact
import com.vayunmathur.library.ui.*

@Composable
fun SimContactsSection(
    simContacts: List<SimContact>,
    hasSim: Boolean,
    onImportOne: (SimContact) -> Unit,
    onDeleteOne: (SimContact) -> Unit,
    onImportAll: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Use Sim-like icon: contacts + card
                IconContacts(tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(R.string.sim_contacts),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (simContacts.isNotEmpty()) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(
                            "${simContacts.size}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (simContacts.isNotEmpty()) {
                    TextButton(onClick = onImportAll) { Text(stringResource(R.string.import_all)) }
                }
                IconButton(onClick = onRefresh) { IconRefresh() }
            }
        }
        if (!hasSim) {
            Text(
                stringResource(R.string.no_sim_inserted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            return
        }
        if (simContacts.isEmpty()) {
            Text(
                stringResource(R.string.no_sim_contacts),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            return
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            simContacts.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifEmpty { it.number } }).forEachIndexed { index, sc ->
                SimContactRow(
                    simContact = sc,
                    onImport = { onImportOne(sc) },
                    onDelete = { onDeleteOne(sc) },
                    shape = groupShape(index, simContacts.size)
                )
            }
        }
    }
}

@Composable
private fun SimContactRow(
    simContact: SimContact,
    onImport: () -> Unit,
    onDelete: () -> Unit,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp)
) {
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    simContact.name.firstOrNull()?.uppercase() ?: simContact.number.firstOrNull()?.toString() ?: "#",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val title = simContact.name.ifEmpty { simContact.number.ifEmpty { stringResource(R.string.no_name) } }
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (simContact.number.isNotBlank()) {
                    Text(simContact.number, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!simContact.emails.isNullOrBlank()) {
                    Text(simContact.emails!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (simContact.subscriptionId != null) {
                    Text("SIM ${simContact.subscriptionId}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            IconButton(onClick = onImport) { IconDownload() }
            IconButton(onClick = onDelete) { IconDelete() }
        }
    }
}
