package com.vayunmathur.library.ui

import android.content.ClipData
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.R as UiR
import kotlinx.coroutines.launch

/** One row of a shared vault's member list. Role is a free string (`owner`/`editor`/`viewer`). */
data class VaultMember(val id: String, val name: String = "", val role: String = "editor")

/** One pending join request: public ids only, never key material. */
data class VaultJoinRequest(val requesterId: String, val name: String = "")

/**
 * Shared "share this vault" dialog, used only by EverySync (vault creation and
 * sharing live there alone — no per-app share buttons or dialogs).
 *
 * Covers the whole invite loop in one place, mirroring office's
 * `ShareOnlineDialog` shape: the vault title, a share-link row (an https link
 * with the ids in the fragment, which browsers never send to the server, so
 * the recipient can request access without the owner typing their device id),
 * a device-id entry with role picker, the member list, a safety-code row for
 * out-of-band verification, and the pending-request approve/deny list.
 *
 * Feedback goes through the snackbar [Messenger], never a Toast.
 */
@Composable
fun ShareVaultDialog(
    vaultTitle: String,
    shareLink: String,
    deviceId: String,
    roles: List<String>,
    members: List<VaultMember>,
    pendingRequests: List<VaultJoinRequest>,
    isOwner: Boolean,
    safetyCode: String?,
    computingCode: Boolean,
    onShareLink: () -> Unit,
    onSendInvite: (recipientId: String, role: String) -> Unit,
    onApproveRequest: (requesterId: String) -> Unit,
    onDenyRequest: (requesterId: String) -> Unit,
    onComputeSafetyCode: (recipientId: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var recipient by remember { mutableStateOf("") }
    var role by remember(roles) { mutableStateOf(roles.firstOrNull().orEmpty()) }
    var roleMenu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val messenger = rememberMessenger()
    val scope = rememberCoroutineScope()
    val copiedText = stringResource(UiR.string.share_vault_device_copied)

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(stringResource(UiR.string.share_vault_title)) },
        text = {
            Column {
                Text(vaultTitle, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                ShareVaultMembersList(members, deviceId)
                if (!isOwner) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(UiR.string.share_vault_viewer_note),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                    ShareLinkRow(shareLink, onShareLink)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(UiR.string.share_vault_add_by_device),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = recipient,
                        onValueChange = { recipient = it },
                        label = { Text(stringResource(UiR.string.share_vault_recipient)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(UiR.string.share_vault_role),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.width(8.dp))
                        Box {
                            TextButton(onClick = { roleMenu = true }) {
                                Text(role.ifEmpty { stringResource(UiR.string.share_vault_role_editor) })
                            }
                            DropdownMenu(expanded = roleMenu, onDismissRequest = { roleMenu = false }) {
                                roles.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option) },
                                        onClick = { role = option; roleMenu = false },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Button(
                            enabled = recipient.isNotBlank() && role.isNotBlank(),
                            onClick = { onSendInvite(recipient.trim(), role) },
                        ) { Text(stringResource(UiR.string.share_vault_send_invite)) }
                    }
                    Spacer(Modifier.height(4.dp))
                    SafetyCodeRow(
                        enabled = recipient.isNotBlank() && !computingCode,
                        computing = computingCode,
                        code = safetyCode,
                        onShow = { onComputeSafetyCode(recipient.trim()) },
                    )
                    ShareVaultRequestsList(pendingRequests, onApproveRequest, onDenyRequest)
                }
                Spacer(Modifier.height(8.dp))
                DeviceIdRow(deviceId) {
                    if (deviceId.isNotEmpty()) {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("device id", deviceId)))
                        }
                        messenger.show(copiedText)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.close)) }
        },
    )
}

/** Share-link row: tappable share sheet button plus a copy button. */
@Composable
private fun ShareLinkRow(shareLink: String, onShareLink: () -> Unit) {
    val clipboard = LocalClipboard.current
    val messenger = rememberMessenger()
    val scope = rememberCoroutineScope()
    val copiedText = stringResource(UiR.string.share_vault_link_copied)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onShareLink, modifier = Modifier.weight(1f)) {
            IconShare()
            Spacer(Modifier.width(4.dp))
            Text(stringResource(UiR.string.share_vault_link))
        }
        Spacer(Modifier.width(8.dp))
        TextButton(
            onClick = {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("share link", shareLink)))
                    messenger.show(copiedText)
                }
            },
        ) { Text(stringResource(UiR.string.copy)) }
    }
    Text(
        shareLink,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Member list: name (or id) plus role, with "(you)" on this device. */
@Composable
private fun ShareVaultMembersList(members: List<VaultMember>, deviceId: String) {
    if (members.isEmpty()) return
    Text(stringResource(UiR.string.share_vault_members), style = MaterialTheme.typography.labelMedium)
    members.forEach { member ->
        ListItem(
            content = {
                Text(
                    member.name.ifBlank { member.id } +
                        if (member.id == deviceId) " (${stringResource(UiR.string.share_vault_you)})" else "",
                )
            },
            trailingContent = {
                Text(member.role, style = MaterialTheme.typography.bodySmall)
            },
        )
    }
}

/** Pending join requests with approve/deny buttons. */
@Composable
private fun ShareVaultRequestsList(
    requests: List<VaultJoinRequest>,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
) {
    if (requests.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    Text(stringResource(UiR.string.share_vault_requests), style = MaterialTheme.typography.labelMedium)
    requests.forEach { request ->
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    request.name.ifBlank { request.requesterId },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { onDeny(request.requesterId) }) {
                    Text(stringResource(UiR.string.share_vault_deny))
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onApprove(request.requesterId) }) {
                    Text(stringResource(UiR.string.share_vault_approve))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** Safety-code row: compute the code for the entered recipient and show it for out-of-band check. */
@Composable
private fun SafetyCodeRow(enabled: Boolean, computing: Boolean, code: String?, onShow: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconVerify()
        Spacer(Modifier.width(8.dp))
        TextButton(enabled = enabled, onClick = onShow) {
            Text(
                if (computing) stringResource(UiR.string.share_vault_computing)
                else stringResource(UiR.string.share_vault_show_code),
            )
        }
    }
    code?.let {
        Text(
            stringResource(UiR.string.share_vault_code_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(it, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

/** This device's id with a copy button. */
@Composable
private fun DeviceIdRow(deviceId: String, onCopy: () -> Unit) {
    Text(stringResource(UiR.string.share_vault_your_device), style = MaterialTheme.typography.bodySmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            deviceId.ifEmpty { "…" },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onCopy) { IconCopy() }
    }
}
