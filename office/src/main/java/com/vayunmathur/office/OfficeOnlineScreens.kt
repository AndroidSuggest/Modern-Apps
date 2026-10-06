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
import com.vayunmathur.office.util.OfficeDocMeta
import com.vayunmathur.office.util.OfficeMember
import com.vayunmathur.office.util.OfficeRoles
import com.vayunmathur.office.util.OfficeViewModel
import com.vayunmathur.office.util.approveJoinRequest
import com.vayunmathur.office.util.denyRequest
import com.vayunmathur.office.util.enableOnlineSharing
import com.vayunmathur.office.util.initSync
import com.vayunmathur.office.util.refreshOnline
import com.vayunmathur.office.util.shareLinkFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal val SpacingXs = 4.dp
internal val SpacingSm = 8.dp
private const val PADDING_SCREEN = 24
private val SpacingScreen = PADDING_SCREEN.dp
private val SpacingContent = 16.dp

/** Copies [deviceId] to the clipboard; no-op when blank. */
@Composable
private fun CopyDeviceIdButton(
    deviceId: String,
    scope: CoroutineScope,
    clipboard: androidx.compose.ui.platform.Clipboard,
) {
    TextButton(
        onClick = {
            if (deviceId.isNotEmpty()) {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("device id", deviceId)))
                }
            }
        },
    ) {
        Text(stringResource(UiR.string.copy))
    }
}

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
            Modifier.fillMaxSize().padding(pad).padding(SpacingScreen),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(SpacingScreen))
            Text(stringResource(R.string.online_sharing_is_off), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(SpacingSm))
            Text(stringResource(R.string.turn_on_online_sharing_to_collaborate_on),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(SpacingScreen))
            Button(
                onClick = onEnable,
                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.enable_online_sharing_1)) }
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
    members: List<OfficeMember>,
    shareLink: String? = null,
    onShare: (String, String, String, (String?) -> Unit) -> Unit,
    onSetRole: (String, String) -> Unit,
    onTransferOwner: (String) -> Unit,
    onRename: (String) -> Unit,
    onComputeCode: (String, (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    val dialog = rememberShareDialogState(initialName)
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_online)) },
        text = {
            Column {
                ShareMembersList(
                    members, deviceId, isOwner, dialog.memberMenu, { dialog.memberMenu = it },
                    onSetRole, onTransferOwner)
                if (!isOwner) {
                    ViewerShareBody(deviceId, myRole, scope, clipboard)
                } else {
                    OwnerShareBody(
                        dialog = dialog, deviceId = deviceId, isOnline = isOnline,
                        initialName = initialName, shareLink = shareLink, scope = scope,
                        clipboard = clipboard, context = context,
                        onRename = onRename, onComputeCode = onComputeCode)
                }
            }
        },
        confirmButton = {
            ShareConfirmButton(
                dialog = dialog, isOwner = isOwner, isOnline = isOnline,
                onShare = onShare, onDismiss = onDismiss)
        },
        dismissButton = { if (isOwner) TextButton(onClick = onDismiss) { Text(stringResource(R.string.close_search)) } }
    )
}

/** Mutable share-dialog state. */
private class ShareDialogState(
    initialName: String,
) {
    var recipient by mutableStateOf("")
    var docName by mutableStateOf(initialName)
    var addRole by mutableStateOf(OfficeRoles.EDITOR)
    var roleMenu by mutableStateOf(false)
    var code by mutableStateOf<String?>(null)
    var computing by mutableStateOf(false)
    var sharing by mutableStateOf(false)
    var status by mutableStateOf<String?>(null)
    var memberMenu by mutableStateOf<String?>(null)
}

/** Remembered share-dialog state. */
@Composable
private fun rememberShareDialogState(initialName: String) = remember(initialName) {
    ShareDialogState(initialName)
}

/** Non-owner body: role note + device id. */
@Composable
private fun ViewerShareBody(
    deviceId: String,
    myRole: String,
    scope: CoroutineScope,
    clipboard: androidx.compose.ui.platform.Clipboard,
) {
    Text(
        stringResource(R.string.you_have_access_only_the_owner_can_chang, myRole),
        style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(SpacingXs))
    DeviceIdRow(deviceId, scope, clipboard)
}

/** Owner body: name field + link + recipient + role + code. */
@Composable
private fun OwnerShareBody(
    dialog: ShareDialogState,
    deviceId: String,
    isOnline: Boolean,
    initialName: String,
    shareLink: String?,
    scope: CoroutineScope,
    clipboard: androidx.compose.ui.platform.Clipboard,
    context: android.content.Context,
    onRename: (String) -> Unit,
    onComputeCode: (String, (String?) -> Unit) -> Unit,
) {
    DocNameField(dialog, isOnline, initialName, onRename)
    // Share sheet: sends an https link that bounces to office://join/<docId>?owner=<deviceId>
    // (public ids only) so the recipient can request access without you typing their device id.
    shareLink?.let { link ->
        OutlinedButton(
            onClick = { ExternalIntents.shareText(
                context,
                link,
                context.getString(R.string.share_link_chooser)) },
            modifier = Modifier.fillMaxWidth()
        ) {
            IconShare()
            Spacer(Modifier.width(SpacingXs))
            Text(stringResource(R.string.share_link))
        }
        Spacer(Modifier.height(SpacingXs))
    }
    RecipientFields(dialog, deviceId, scope, clipboard, onComputeCode)
}

/** Document-name field (create vs rename). */
@Composable
private fun DocNameField(
    dialog: ShareDialogState,
    isOnline: Boolean,
    initialName: String,
    onRename: (String) -> Unit,
) {
    if (!isOnline) {
        // Owner names the document before it first goes online.
        OutlinedTextField(
            value = dialog.docName, onValueChange = { dialog.docName = it },
            label =
                { Text(stringResource(R.string.document_name)) }, singleLine =
                    true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(SpacingXs))
        return
    }
    // Already online: owner can rename; the new name is published to all members.
    OutlinedTextField(
        value = dialog.docName, onValueChange = { dialog.docName = it },
        label =
            { Text(stringResource(R.string.document_name)) }, singleLine =
                true, modifier = Modifier.fillMaxWidth()
    )
    TextButton(
        enabled = dialog.docName.isNotBlank() && dialog.docName.trim() != initialName,
        onClick = { onRename(dialog.docName.trim()) }) { Text(stringResource(UiR.string.rename)) }
    Spacer(Modifier.height(SpacingXs))
}

/** Recipient id + role + device id + security code. */
@Composable
private fun RecipientFields(
    dialog: ShareDialogState,
    deviceId: String,
    scope: CoroutineScope,
    clipboard: androidx.compose.ui.platform.Clipboard,
    onComputeCode: (String, (String?) -> Unit) -> Unit,
) {
    Text(stringResource(R.string.add_someone_by_device_id_copies_this_doc))
    Spacer(Modifier.height(SpacingXs))
    OutlinedTextField(
        value = dialog.recipient, onValueChange = { dialog.recipient = it; dialog.code = null },
        label =
            { Text(stringResource(R.string.recipient_device_id)) }, singleLine =
                true, modifier = Modifier.fillMaxWidth()
    )
    RoleMenuRow(dialog)
    Spacer(Modifier.height(SpacingXs))
    DeviceIdRow(deviceId, scope, clipboard)
    SecurityCodeSection(dialog, onComputeCode)
}

/** Device-id row with copy button. */
@Composable
private fun DeviceIdRow(
    deviceId: String,
    scope: CoroutineScope,
    clipboard: androidx.compose.ui.platform.Clipboard,
) {
    Text(stringResource(R.string.your_device_id), style = MaterialTheme.typography.bodySmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            deviceId.ifEmpty { "…" },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f))
        CopyDeviceIdButton(deviceId, scope, clipboard)
    }
}

/** Role dropdown row. */
@Composable
private fun RoleMenuRow(dialog: ShareDialogState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.role), style = MaterialTheme.typography.bodySmall)
        Box {
            TextButton(onClick = { dialog.roleMenu = true }) { Text(dialog.addRole) }
            DropdownMenu(expanded = dialog.roleMenu, onDismissRequest = { dialog.roleMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor)) },
                    onClick = { dialog.addRole = OfficeRoles.EDITOR; dialog.roleMenu = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.viewer)) },
                    onClick = { dialog.addRole = OfficeRoles.VIEWER; dialog.roleMenu = false },
                )
            }
        }
    }
}

/** Security-code compute + display. */
@Composable
private fun SecurityCodeSection(
    dialog: ShareDialogState,
    onComputeCode: (String, (String?) -> Unit) -> Unit,
) {
    TextButton(
        enabled = dialog.recipient.isNotBlank() && !dialog.computing,
        onClick = {
            dialog.computing = true
            dialog.code = null
            onComputeCode(dialog.recipient.trim()) { c ->
                dialog.code = c
                dialog.computing = false
            }
        },
    ) {
        Text(
            if (dialog.computing) {
                stringResource(R.string.computing)
            } else {
                stringResource(R.string.show_security_code)
            },
        )
    }
    dialog.code?.let {
        Text(
            stringResource(R.string.compare_with_the_recipient_out_of_band_i),
            style = MaterialTheme.typography.bodySmall)
        Text(it, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
    dialog.status?.let {
        Spacer(Modifier.height(SpacingXs))
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

/** Confirm button (add / close). */
@Composable
private fun ShareConfirmButton(
    dialog: ShareDialogState,
    isOwner: Boolean,
    isOnline: Boolean,
    onShare: (String, String, String, (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!isOwner) {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.close_search)) }
        return
    }
    TextButton(
        enabled = dialog.recipient.isNotBlank() && (isOnline || dialog.docName.isNotBlank()) && !dialog.sharing,
        onClick = {
            dialog.sharing = true
            dialog.status = null
            onShare(dialog.recipient.trim(), dialog.addRole, dialog.docName.trim()) { err ->
                dialog.sharing = false
                dialog.status = err ?: "Added ✓"
                if (err == null) dialog.recipient = ""
            }
        }
    ) {
        Text(
            if (dialog.sharing) stringResource(R.string.adding) else stringResource(UiR.string.add))
    }
}

/** Lists documents shared by you or with you; tap to pull + open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineTab(viewModel: OfficeViewModel, onOpenDoc: (OfficeDocMeta) -> Unit) {
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
            Column(Modifier.fillMaxSize().padding(horizontal = SpacingContent)) {
                OnlineDeviceHeader(deviceId, scope, clipboard)
                Spacer(Modifier.height(SpacingSm))
                OnlineDocLists(viewModel, docs, requests, context, onOpenDoc)
            }
        }
    }
}

/** Device-id header for the online tab. */
@Composable
private fun OnlineDeviceHeader(
    deviceId: String,
    scope: CoroutineScope,
    clipboard: androidx.compose.ui.platform.Clipboard,
) {
    Text(stringResource(R.string.your_device_id_share_this_so_others_can),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            deviceId.ifEmpty { "…" },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f))
        CopyDeviceIdButton(deviceId, scope, clipboard)
    }
}

/** Empty notice vs requests + docs lists. */
@Composable
private fun OnlineDocLists(
    viewModel: OfficeViewModel,
    docs: List<OfficeDocMeta>,
    requests: List<com.vayunmathur.office.util.OfficeSync.JoinRequest>,
    context: android.content.Context,
    onOpenDoc: (OfficeDocMeta) -> Unit,
) {
    if (docs.isEmpty() && requests.isEmpty()) {
        Text(stringResource(R.string.no_online_documents_yet_open_a_document),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(SpacingXs)) {
        // Inbound join requests (from tapped share links) for docs you own.
        if (requests.isNotEmpty()) {
            item {
                Text(stringResource(R.string.requests_header),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            items(requests, key = { "req-${it.docId}-${it.requesterId}" }) { req ->
                JoinRequestCard(viewModel, docs, req)
            }
        }
        items(docs, key = { it.docId }) { meta ->
            OnlineDocCard(viewModel, meta, context, onOpenDoc)
        }
    }
}

/** One inbound join request. */
@Composable
private fun JoinRequestCard(
    viewModel: OfficeViewModel,
    docs: List<OfficeDocMeta>,
    req: com.vayunmathur.office.util.OfficeSync.JoinRequest,
) {
    val docTitle = docs.firstOrNull { it.docId == req.docId }?.title ?: req.docId.take(8)
    val requester = req.name.ifBlank { req.requesterId.take(8) }
    Card(Modifier.fillMaxWidth()) {
        ListItem(
            content = { Text(stringResource(
                R.string.request_wants_to_join,
                requester,
                docTitle)) },
            trailingContent = {
                Row {
                    TextButton(onClick = { viewModel.approveJoinRequest(
                        req.docId,
                        req.requesterId,
                        req.name) }) { Text(stringResource(R.string.approve)) }
                    TextButton(onClick = { viewModel.denyRequest(
                        req.docId,
                        req.requesterId) }) { Text(stringResource(R.string.deny)) }
                }
            }
        )
    }
}

/** One online document row. */
@Composable
private fun OnlineDocCard(
    viewModel: OfficeViewModel,
    meta: OfficeDocMeta,
    context: android.content.Context,
    onOpenDoc: (OfficeDocMeta) -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable { onOpenDoc(meta) }) {
        ListItem(
            content = { Text(meta.title) },
            supportingContent = {
                Text(
                    if (meta.owner) {
                        stringResource(R.string.shared_by_you)
                    } else {
                        stringResource(R.string.shared_with_you)
                    },
                )
            },
            trailingContent = {
                // Owners can share a tappable invite link (public ids only).
                if (meta.owner) IconButton(onClick = {
                    ExternalIntents.shareText(
                        context,
                        viewModel.shareLinkFor(meta.docId),
                        context.getString(R.string.share_link_chooser))
                }) { IconShare() }
            }
        )
    }
}
