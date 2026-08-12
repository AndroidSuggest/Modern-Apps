package com.vayunmathur.euicc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.euicc.data.EuiccInfo
import com.vayunmathur.euicc.data.Notification
import com.vayunmathur.euicc.data.Profile
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.OverflowMenu
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

@Composable
fun EuiccApp(viewModel: EuiccViewModel) {
    val state = viewModel.state
    var renameTarget by remember { mutableStateOf<Profile?>(null) }
    var deleteTarget by remember { mutableStateOf<Profile?>(null) }

    AppScaffold(
        title = "EUICC",
        actions = {
            IconButton(onClick = viewModel::reload, enabled = !state.loading) { IconRefresh() }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.loading) CircularProgressIndicator()
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            ProfilesSection(
                profiles = state.profiles,
                onEnable = { viewModel.enable(it.iccid) },
                onDisable = { viewModel.disable(it.iccid) },
                onRename = { renameTarget = it },
                onDelete = { deleteTarget = it },
            )
            NotificationsSection(
                notifications = state.notifications,
                onRemove = { viewModel.removeNotification(it.seqNumber) },
            )
            EuiccSection(eid = state.eid, info = state.info)
        }
    }

    renameTarget?.let { profile ->
        RenameDialog(
            profile = profile,
            onConfirm = { name ->
                viewModel.rename(profile.iccid, name)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { profile ->
        ConfirmDialog(
            title = "Delete ${profile.displayName}?",
            message = "This permanently removes the profile from the eUICC.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                viewModel.delete(profile.iccid)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun ProfilesSection(
    profiles: List<Profile>,
    onEnable: (Profile) -> Unit,
    onDisable: (Profile) -> Unit,
    onRename: (Profile) -> Unit,
    onDelete: (Profile) -> Unit,
) {
    SectionCard(title = "Profiles") {
        if (profiles.isEmpty()) {
            Text("No profiles installed.")
            return@SectionCard
        }
        for (profile in profiles) {
            ListItem(
                headlineContent = { Text(profile.displayName) },
                supportingContent = {
                    Text(
                        "${if (profile.isEnabled) "Enabled" else "Disabled"} · ${profile.iccidDisplay}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = {
                    OverflowMenu {
                        if (profile.isEnabled) {
                            Item("Disable") { onDisable(profile) }
                        } else {
                            Item("Enable") { onEnable(profile) }
                        }
                        Item("Rename") { onRename(profile) }
                        Item("Delete") { onDelete(profile) }
                    }
                },
            )
        }
    }
}

@Composable
private fun NotificationsSection(
    notifications: List<Notification>,
    onRemove: (Notification) -> Unit,
) {
    SectionCard(title = "Notifications") {
        if (notifications.isEmpty()) {
            Text("No pending notifications.")
            return@SectionCard
        }
        for (note in notifications) {
            ListItem(
                headlineContent = { Text("#${note.seqNumber} · ${note.operation}") },
                supportingContent = {
                    Text(note.address, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                trailingContent = {
                    TextButton(onClick = { onRemove(note) }) { Text("Remove") }
                },
            )
        }
    }
}

@Composable
private fun EuiccSection(eid: String?, info: EuiccInfo?) {
    SectionCard(title = "eUICC") {
        Text("EID", style = MaterialTheme.typography.labelMedium)
        Text(eid ?: "unavailable")
        if (info != null) {
            Text("SGP.22 version", style = MaterialTheme.typography.labelMedium)
            Text(info.svn.ifEmpty { "unknown" })
        }
    }
}

@Composable
private fun RenameDialog(profile: Profile, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(profile.nickname) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename profile") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nickname") },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}
