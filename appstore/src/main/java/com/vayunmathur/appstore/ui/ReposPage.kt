package com.vayunmathur.appstore.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.appstore.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.appstore.data.DefaultRepos
import com.vayunmathur.appstore.data.FDroidRepository
import com.vayunmathur.appstore.data.ModernAppsRepo
import com.vayunmathur.appstore.data.security.ApkCertificates
import com.vayunmathur.appstore.data.security.SecurityTier
import com.vayunmathur.appstore.util.AppStoreViewModel
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar

/**
 * Sources are fixed and not user-editable — see [DefaultRepos] for why. This page shows
 * what each source is pinned to, so the pins can be compared against their published
 * values rather than taken on faith.
 */
@Composable
fun ReposPage(viewModel: AppStoreViewModel, onOpenTiers: () -> Unit = {}) {
    val repos by viewModel.repos.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val syncMessage by viewModel.syncMessage.collectAsState()
    val fdroid = repos.find { it.url == DefaultRepos.FDROID_MAIN }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.repositories)) }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(stringResource(R.string.sources_are_fixed), style = MaterialTheme.typography.bodySmall)
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.syncRepos() },
                        enabled = !isSyncing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(if (isSyncing) R.string.repos_syncing else R.string.repos_sync_sources))
                    }
                    Button(onClick = onOpenTiers, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.security_tiers))
                    }
                }
            }
            if (syncMessage.isNotBlank()) {
                item {
                    Text(
                        syncMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

            item {
                SourceCard(
                    title = stringResource(R.string.source_modern_apps),
                    subtitle = ModernAppsRepo.PROJECT_URL,
                    tier = SecurityTier.FIRST_PARTY,
                    pinLabel = stringResource(R.string.source_modern_apps_pin),
                    pins = viewModel.ownSigningCertificates,
                    lastSync = 0L,
                )
            }
            item {
                SourceCard(
                    title = stringResource(R.string.source_fdroid),
                    subtitle = DefaultRepos.FDROID_MAIN,
                    tier = SecurityTier.REPRODUCIBLE,
                    pinLabel = stringResource(R.string.source_fdroid_pin),
                    pins = setOfNotNull(
                        fdroid?.fingerprint ?: FDroidRepository.FDROID_SIGNING_CERT_SHA256
                    ),
                    lastSync = fdroid?.lastSync ?: 0L,
                )
            }
            item {
                SourceCard(
                    title = stringResource(R.string.source_play),
                    subtitle = "play.google.com",
                    tier = SecurityTier.GOOGLE_PLAY,
                    pinLabel = stringResource(R.string.source_play_pin),
                    pins = emptySet(),
                    lastSync = 0L,
                )
            }
        }
    }
}

@Composable
private fun SourceCard(
    title: String,
    subtitle: String,
    tier: SecurityTier,
    pinLabel: String,
    pins: Set<String>,
    lastSync: Long,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SecurityTierBadge(tier)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Text(subtitle, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                pinLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            pins.forEach {
                Text(
                    ApkCertificates.abbreviate(it),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (lastSync > 0) {
                Text(
                    stringResource(
                        R.string.last_sync,
                        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(lastSync)),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
