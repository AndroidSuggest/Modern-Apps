package com.vayunmathur.appstore.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.appstore.R
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.security.SecurityTier
import com.vayunmathur.appstore.data.security.VerificationResult
import com.vayunmathur.appstore.util.AppDetailActions
import com.vayunmathur.appstore.util.AppDetailUiState
import com.vayunmathur.appstore.util.AppStoreViewModel
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CenterAlignedTopAppBar
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconGlobe
import com.vayunmathur.library.ui.IconShoppingCart
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Binds [AppStoreViewModel] to the stateless [AppDetailScreen]. */
@Composable
fun AppDetailPage(
    viewModel: AppStoreViewModel,
    onBack: () -> Unit,
    onOpenTiers: () -> Unit = {},
) {
    val app by viewModel.selectedApp.collectAsState()
    val verificationMap by viewModel.verification.collectAsState()
    val installed by viewModel.installedApps.collectAsState()
    val progressMap by viewModel.downloadProgress.collectAsState()
    val icons by viewModel.installedIcons.collectAsState()
    val syncMessage by viewModel.syncMessage.collectAsState()
    // "" can never be a real package name, so the lookups below miss harmlessly while
    // nothing is selected and the screen bails out.
    val pkg = app?.packageName.orEmpty()

    AppDetailScreen(
        state = AppDetailUiState(
            app = app,
            installedInfo = installed.find { it.packageName == pkg },
            verification = verificationMap[pkg],
            progress = progressMap[pkg],
            installedIcon = icons[pkg],
            syncMessage = syncMessage,
        ),
        actions = viewModel,
        onBack = onBack,
        onOpenTiers = onOpenTiers,
    )
}

/**
 * The app detail screen, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@Composable
fun AppDetailScreen(
    state: AppDetailUiState,
    actions: AppDetailActions,
    onBack: () -> Unit = {},
    onOpenTiers: () -> Unit = {},
) {
    val current = state.app ?: return
    val tier = SecurityTier.of(current.source)
    val lastVerification = state.verification
    val installedInfo = state.installedInfo
    val isInstalled = installedInfo != null
    val progress = state.progress
    val installedIcon = state.installedIcon
    val syncMessage = state.syncMessage

    var showUninstallConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(current.name) },
                navigationIcon = { IconButton(onClick = onBack) { IconBack() } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (installedIcon != null) {
                    val bitmap = remember(installedIcon) {
                        try { installedIcon.toBitmap(width = 144, height = 144).asImageBitmap() } catch (_: Exception) { null }
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        AsyncImage(
                            model = current.iconUrl,
                            contentDescription = null,
                            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                } else {
                    AsyncImage(
                        model = current.iconUrl,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(current.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    current.author?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SecurityTierBadge(tier, onClick = onOpenTiers)
                        Spacer(Modifier.width(6.dp))
                        SourceBadge(current.source)
                        Spacer(Modifier.width(8.dp))
                        if (isInstalled) {
                            Text(stringResource(R.string.installed_2, installedInfo?.versionName ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (syncMessage.isNotBlank()) {
                        Text(syncMessage, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }

            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.downloading, (progress * 100).toInt()), style = MaterialTheme.typography.labelSmall)
            }

            // What this tier claims, and what the last install attempt actually proved.
            Card(
                onClick = onOpenTiers,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SecurityTierBadge(tier, onClick = onOpenTiers)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(tier.title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(stringResource(tier.summary), style = MaterialTheme.typography.bodySmall)
                    when (val v = lastVerification) {
                        is VerificationResult.Rejected -> Text(
                            stringResource(R.string.detail_install_blocked, v.reason),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        is VerificationResult.Verified -> Text(
                            stringResource(R.string.detail_checked_on_install, v.detail),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        is VerificationResult.Unverified -> Text(
                            stringResource(R.string.detail_installed_unverified, v.reason),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        null -> Text(
                            stringResource(R.string.detail_tap_for_tier),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Action row redesigned per plan: Open + Uninstall for installed, Install/Update for others
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    isInstalled && current.versionCode > (installedInfo?.versionCode ?: 0L) -> {
                        Button(onClick = { actions.downloadAndInstall(current) }, modifier = Modifier.weight(1f), enabled = progress == null) {
                            IconDownload()
                            Spacer(Modifier.width(6.dp))
                            Text(if (progress != null) "Downloading ${(progress * 100).toInt()}%" else "Update")
                        }
                        FilledTonalButton(onClick = { actions.openApp(current.packageName) }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.open))
                        }
                        OutlinedButton(onClick = { showUninstallConfirm = true }) { IconDelete() }
                    }
                    isInstalled -> {
                        FilledTonalButton(onClick = { actions.openApp(current.packageName) }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.open))
                        }
                        OutlinedButton(onClick = { showUninstallConfirm = true }, modifier = Modifier.weight(1f)) {
                            IconDelete()
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.uninstall))
                        }
                    }
                    current.source == AppSource.PLAYSTORE -> {
                        Button(
                            onClick = { actions.downloadAndInstall(current) },
                            modifier = Modifier.weight(1f),
                            enabled = progress == null
                        ) {
                            IconDownload()
                            Spacer(Modifier.width(6.dp))
                            Text(if (progress != null) "Downloading ${(progress * 100).toInt()}%" else "Install")
                        }
                        OutlinedButton(onClick = { actions.openInPlayStore(current.packageName) }) {
                            IconShoppingCart()
                        }
                    }
                    else -> {
                        Button(
                            onClick = { actions.downloadAndInstall(current) },
                            modifier = Modifier.weight(1f),
                            enabled = progress == null
                        ) {
                            IconDownload()
                            Spacer(Modifier.width(6.dp))
                            Text(if (progress != null) "Downloading ${(progress * 100).toInt()}%" else "Install")
                        }
                    }
                }
                // Globe button remains for website/source when not already handling installed actions
                if (!isInstalled && (current.source == AppSource.FDROID || current.website != null)) {
                    OutlinedButton(onClick = {
                        actions.openInBrowser(current.website ?: current.sourceCode ?: "")
                    }) { IconGlobe() }
                }
            }

            // For Play installed, also show View in Play Store as secondary
            if (isInstalled && current.source == AppSource.PLAYSTORE) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { actions.openInPlayStore(current.packageName) }, modifier = Modifier.weight(1f)) {
                        IconShoppingCart()
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.view_in_play_store))
                    }
                    if (current.website != null || current.sourceCode != null) {
                        OutlinedButton(onClick = {
                            actions.openInBrowser(current.website ?: current.sourceCode ?: "")
                        }) { IconGlobe() }
                    }
                }
            }

            if (current.summary.isNotBlank()) {
                Text(current.summary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }

            if (current.description.isNotBlank()) {
                Text(current.description, style = MaterialTheme.typography.bodyMedium)
            }

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DetailRow("Package", current.packageName)
                current.versionName?.let { DetailRow("Version", it) }
                if (current.sizeBytes > 0) DetailRow("Size", formatSize(current.sizeBytes))
                current.license?.let { DetailRow("License", it) }
                if (current.categories.isNotEmpty()) DetailRow("Categories", current.categories.joinToString(", "))
                current.sourceCode?.let { DetailRow("Source", it) }
                current.website?.let { DetailRow("Website", it) }
                if (current.antiFeatures.isNotEmpty()) DetailRow("Anti-features", current.antiFeatures.joinToString(", "))
                current.whatsNew?.let { if (it.isNotBlank()) DetailRow("What's new", it) }
            }
        }
    }

    if (showUninstallConfirm) {
        AlertDialog(
            onDismissRequest = { showUninstallConfirm = false },
            title = { Text(stringResource(R.string.uninstall_3, current.name)) },
            text = { Text(stringResource(R.string.this_will_uninstall_you_can_reinstall_la, current.packageName)) },
            confirmButton = {
                Button(onClick = {
                    showUninstallConfirm = false
                    actions.uninstallApp(current.packageName)
                }) { Text(stringResource(R.string.uninstall)) }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallConfirm = false }) { Text(stringResource(UiR.string.cancel)) }
            }
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format("%.1f MB", mb)
}
