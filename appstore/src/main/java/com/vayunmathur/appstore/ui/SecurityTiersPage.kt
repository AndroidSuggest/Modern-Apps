package com.vayunmathur.appstore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.security.ApkCertificates
import com.vayunmathur.appstore.data.security.SecurityTier
import com.vayunmathur.appstore.data.security.StoreGuarantees
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CenterAlignedTopAppBar
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text

/**
 * What the tier badges mean. Reached by tapping any "Tier N" chip.
 *
 * The framing throughout is deliberately "who has to be compromised", because that is
 * the only property that survives contact with reality once an app is installed: review,
 * scanning and store policy are filters that reduce the odds of a bad app, while the
 * signing key decides who is *able* to replace a good one.
 */
@Composable
fun SecurityTiersPage(
    ownSigningCertificates: Set<String>,
    onBack: () -> Unit,
    /**
     * Seed for the list's own scroll position. The app always takes the default; the store
     * listing previews set it so a tier card can be captured without driving a scroll
     * gesture, which a `@Preview` cannot do.
     */
    initialFirstVisibleItem: Int = 0,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.security_tiers)) },
                navigationIcon = { IconButton(onClick = onBack) { IconBack() } },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            state = rememberLazyListState(initialFirstVisibleItemIndex = initialFirstVisibleItem),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.security_tiers_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            stringResource(R.string.security_tiers_all_tiers),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(R.string.security_tiers_all_tiers_detail),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        StoreGuarantees.rules.forEach { rule ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    stringResource(rule.title),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    stringResource(rule.detail, *rule.detailArgs.toTypedArray()),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }

            items(SecurityTier.entries.toList()) { tier -> TierCard(tier) }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.security_tiers_own_key_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(R.string.security_tiers_own_key_detail),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (ownSigningCertificates.isEmpty()) {
                            Text(
                                stringResource(R.string.security_tiers_own_key_unreadable),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            ownSigningCertificates.forEach {
                                Text(
                                    ApkCertificates.abbreviate(it),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }

        }
    }
}

@Composable
private fun TierCard(tier: SecurityTier) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SecurityTierBadge(tier)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(tier.title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(stringResource(tier.summary), style = MaterialTheme.typography.bodyMedium)

            HorizontalDivider()
            Text(
                stringResource(R.string.security_tiers_threat_heading),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(tier.threatModel), style = MaterialTheme.typography.bodySmall)

            Text(
                stringResource(R.string.security_tiers_checks_heading),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            tier.additionalChecks.forEach {
                Text(
                    stringResource(R.string.security_tiers_bullet, stringResource(it)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text(
                stringResource(R.string.security_tiers_tradeoff_heading),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            // Every tier has a trade-off, so this is informational rather than a warning.
            Text(
                stringResource(tier.caveat),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
