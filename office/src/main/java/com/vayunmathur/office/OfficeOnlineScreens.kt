package com.vayunmathur.office

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.OfflineBanner
import com.vayunmathur.library.util.rememberIsOnline
import com.vayunmathur.office.util.OfficeViewModel
import com.vayunmathur.office.util.approveJoinRequest
import com.vayunmathur.office.util.denyRequest
import com.vayunmathur.office.util.enableOnlineSharing
import com.vayunmathur.office.util.initSync
import com.vayunmathur.office.util.refreshOnline
import com.vayunmathur.office.util.shareLinkFor
import kotlinx.coroutines.launch

/** Initializes online sync once when the Online tab first appears. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnlineInit(viewModel: OfficeViewModel) {
    LaunchedEffect(Unit) { viewModel.initSync() }
}

/** Shown on the Online tab before the user opts in. No keys or device id exist yet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnlineDisabledScreen(onEnable: () -> Unit) {
    // RAW SCAFFOLD EXCEPTION: bar-less full-screen opt-in prompt; the shared scaffolds all
    // impose a top app bar (AppScaffold/DetailScaffold) or lazy-list content semantics
    // (LazyListScaffold), none of which fit a centered, bar-less message screen.
    Scaffold { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.online_sharing_is_off), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.turn_on_online_sharing_to_collaborate_on),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onEnable, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.enable_online_sharing_1)) }
        }
    }
}

/** Dialog to copy the current document into the online folder and share it with a device id. */
@Composable
fun ShareOnlineDialog(
    deviceId: String,
    isOwner: Boolean,
    isOnline: Boolean,
    initialName: String,
    myRole: String,
    members: List<com.vayunmathur.office.util.OfficeMember>,
    shareLink: String? = null,
    onShare: (String, String, String, (String?) -> Unit) -> Unit,
    onSetRole: (String, String) -> Unit,
    onTransferOwner: (String) -> Unit,
    onRename: (String) -> Unit,
    onComputeCode: (String, (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var recipient by remember { mutableStateOf("") }
    var docName by remember { mutableStateOf(initialName) }
    var addRole by remember { mutableStateOf(com.vayunmathur.office.util.OfficeRoles.EDITOR) }
    var roleMenu by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<String?>(null) }
    var computing by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var memberMenu by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_online)) },
        text = {
            Column {
                if (members.isNotEmpty()) {
                    Text(stringResource(R.string.people_with_access), style = MaterialTheme.typography.labelMedium)
                    members.filter { it.role != com.vayunmathur.office.util.OfficeRoles.REVOKED }.forEach { m ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            val label = (if (m.name.isNotBlank()) m.name else m.id.take(8)) + if (m.id == deviceId) " (you)" else ""
                            Text("• $label — ${m.role}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            if (isOwner && m.id != deviceId) {
                                Box {
                                    TextButton(onClick = { memberMenu = m.id }) { Text(stringResource(R.string.manage)) }
                                    DropdownMenu(expanded = memberMenu == m.id, onDismissRequest = { memberMenu = null }) {
                                        if (m.role == com.vayunmathur.office.util.OfficeRoles.EDITOR)
                                            DropdownMenuItem(text = { Text(stringResource(R.string.make_viewer)) }, onClick = { memberMenu = null; onSetRole(m.id, com.vayunmathur.office.util.OfficeRoles.VIEWER) })
                                        else
                                            DropdownMenuItem(text = { Text(stringResource(R.string.make_editor)) }, onClick = { memberMenu = null; onSetRole(m.id, com.vayunmathur.office.util.OfficeRoles.EDITOR) })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.make_owner)) }, onClick = { memberMenu = null; onTransferOwner(m.id) })
                                        DropdownMenuItem(text = { Text(stringResource(UiR.string.remove)) }, onClick = { memberMenu = null; onSetRole(m.id, com.vayunmathur.office.util.OfficeRoles.REVOKED) })
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (!isOwner) {
                    Text(stringResource(R.string.you_have_access_only_the_owner_can_chang, myRole), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.your_device_id), style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(deviceId.ifEmpty { "…" }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { if (deviceId.isNotEmpty()) scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("device id", deviceId))) } }) { Text(stringResource(R.string.copy)) }
                    }
                } else {
                    if (!isOnline) {
                        // Owner names the document before it first goes online.
                        OutlinedTextField(
                            value = docName, onValueChange = { docName = it },
                            label = { Text(stringResource(R.string.document_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                    } else {
                        // Already online: owner can rename; the new name is published to all members.
                        OutlinedTextField(
                            value = docName, onValueChange = { docName = it },
                            label = { Text(stringResource(R.string.document_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        TextButton(enabled = docName.isNotBlank() && docName.trim() != initialName, onClick = { onRename(docName.trim()) }) { Text(stringResource(UiR.string.rename)) }
                        Spacer(Modifier.height(8.dp))
                    }
                    // Share sheet: sends an https link that bounces to office://join/<docId>?owner=<deviceId>
                    // (public ids only) so the recipient can request access without you typing their device id.
                    shareLink?.let { link ->
                        OutlinedButton(
                            onClick = { ExternalIntents.shareText(context, link, context.getString(R.string.share_link_chooser)) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            IconShare()
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.share_link))
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(stringResource(R.string.add_someone_by_device_id_copies_this_doc))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = recipient, onValueChange = { recipient = it; code = null },
                        label = { Text(stringResource(R.string.recipient_device_id)) }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.role), style = MaterialTheme.typography.bodySmall)
                        Box {
                            TextButton(onClick = { roleMenu = true }) { Text(addRole) }
                            DropdownMenu(expanded = roleMenu, onDismissRequest = { roleMenu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.editor)) }, onClick = { addRole = com.vayunmathur.office.util.OfficeRoles.EDITOR; roleMenu = false })
                                DropdownMenuItem(text = { Text(stringResource(R.string.viewer)) }, onClick = { addRole = com.vayunmathur.office.util.OfficeRoles.VIEWER; roleMenu = false })
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.your_device_id), style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(deviceId.ifEmpty { "…" }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { if (deviceId.isNotEmpty()) scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("device id", deviceId))) } }) { Text(stringResource(R.string.copy)) }
                    }
                    TextButton(
                        enabled = recipient.isNotBlank() && !computing,
                        onClick = { computing = true; code = null; onComputeCode(recipient.trim()) { c -> code = c; computing = false } }
                    ) { Text(if (computing) stringResource(R.string.computing) else stringResource(R.string.show_security_code)) }
                    code?.let {
                        Text(stringResource(R.string.compare_with_the_recipient_out_of_band_i), style = MaterialTheme.typography.bodySmall)
                        Text(it, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    }
                    status?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = {
            if (isOwner) TextButton(
                enabled = recipient.isNotBlank() && (isOnline || docName.isNotBlank()) && !sharing,
                onClick = {
                    sharing = true; status = null
                    onShare(recipient.trim(), addRole, docName.trim()) { err ->
                        sharing = false
                        status = err ?: "Added ✓"
                        if (err == null) recipient = ""
                    }
                }
            ) { Text(if (sharing) stringResource(R.string.adding) else stringResource(UiR.string.add)) }
            else TextButton(onClick = onDismiss) { Text(stringResource(R.string.close_search)) }
        },
        dismissButton = { if (isOwner) TextButton(onClick = onDismiss) { Text(stringResource(R.string.close_search)) } }
    )
}

/** Lists documents shared by you or with you; tap to pull + open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineTab(viewModel: OfficeViewModel, onOpenDoc: (com.vayunmathur.office.util.OfficeDocMeta) -> Unit) {
    val onlineEnabled by viewModel.onlineEnabled.collectAsState()
    if (!onlineEnabled) {
        OnlineDisabledScreen(onEnable = { viewModel.enableOnlineSharing() })
        return
    }
    OnlineInit(viewModel)
    val docs by viewModel.onlineDocs.collectAsState()
    val requests by viewModel.pendingRequests.collectAsState()
    val deviceId = viewModel.syncDeviceId
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val online by rememberIsOnline()
    val context = LocalContext.current
    AppScaffold(
        title = stringResource(R.string.online),
        actions = {
            IconButton(onClick = { viewModel.refreshOnline() }) {
                IconRefresh()
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OfflineBanner(online)
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.your_device_id_share_this_so_others_can),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(deviceId.ifEmpty { "…" }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { if (deviceId.isNotEmpty()) scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("device id", deviceId))) } }) { Text(stringResource(R.string.copy)) }
            }
            Spacer(Modifier.height(12.dp))
            if (docs.isEmpty() && requests.isEmpty()) {
                Text(stringResource(R.string.no_online_documents_yet_open_a_document),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Inbound join requests (from tapped share links) for docs you own.
                    if (requests.isNotEmpty()) {
                        item {
                            Text(stringResource(R.string.requests_header),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        items(requests, key = { "req-${it.docId}-${it.requesterId}" }) { req ->
                            val docTitle = docs.firstOrNull { it.docId == req.docId }?.title ?: req.docId.take(8)
                            val requester = req.name.ifBlank { req.requesterId.take(8) }
                            Card(Modifier.fillMaxWidth()) {
                                ListItem(
                                    content = { Text(stringResource(R.string.request_wants_to_join, requester, docTitle)) },
                                    trailingContent = {
                                        Row {
                                            TextButton(onClick = { viewModel.approveJoinRequest(req.docId, req.requesterId, req.name) }) { Text(stringResource(R.string.approve)) }
                                            TextButton(onClick = { viewModel.denyRequest(req.docId, req.requesterId) }) { Text(stringResource(R.string.deny)) }
                                        }
                                    }
                                )
                            }
                        }
                    }
                    items(docs, key = { it.docId }) { meta ->
                        Card(Modifier.fillMaxWidth().clickable { onOpenDoc(meta) }) {
                            ListItem(
                                content = { Text(meta.title) },
                                supportingContent = { Text(if (meta.owner) stringResource(R.string.shared_by_you) else stringResource(R.string.shared_with_you)) },
                                trailingContent = {
                                    // Owners can share a tappable invite link (public ids only).
                                    if (meta.owner) IconButton(onClick = {
                                        ExternalIntents.shareText(context, viewModel.shareLinkFor(meta.docId), context.getString(R.string.share_link_chooser))
                                    }) { IconShare() }
                                }
                            )
                        }
                    }
                }
            }
            }
        }
    }
}
