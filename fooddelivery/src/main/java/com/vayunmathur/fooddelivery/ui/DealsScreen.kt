package com.vayunmathur.fooddelivery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.IconEmojiEvents
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.fooddelivery.R
import com.vayunmathur.fooddelivery.api.BitesApi
import com.vayunmathur.fooddelivery.data.Deal
import com.vayunmathur.fooddelivery.data.DealProgress

/**
 * Browse every active deal on the platform. Tapping one opens its merchant so the deal
 * can be applied at checkout, where the chosen `dealId` is sent with the order.
 */
@Composable
fun DealsScreen(onMerchantClick: (Int) -> Unit) {
    var deals by remember { mutableStateOf<List<Deal>>(emptyList()) }
    var progress by remember { mutableStateOf<Map<Int, DealProgress>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        deals = BitesApi.getAllDeals().filter { it.isActive }
        loading = false
        // Progress is per-deal; fetch after the list so the deals render immediately.
        progress = deals.mapNotNull { d -> BitesApi.getDealProgress(d.id)?.let { d.id to it } }.toMap()
    }

    Scaffold { padding ->
        when {
            loading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            deals.isEmpty() -> EmptyState(
                title = stringResource(R.string.no_deals_yet),
                message = stringResource(R.string.deals_will_appear_here),
                icon = { IconEmojiEvents() },
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(padding),
            ) {
                items(deals) { deal ->
                    DealCard(
                        deal = deal,
                        progress = progress[deal.id],
                        onClick = { if (deal.merchantId != 0) onMerchantClick(deal.merchantId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DealCard(deal: Deal, progress: DealProgress?, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column {
            if (deal.image.isNotEmpty()) {
                AsyncImage(
                    model = deal.image,
                    contentDescription = deal.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
            }
            Column(Modifier.padding(12.dp)) {
                Text(deal.title, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold)
                if (deal.merchantName.isNotEmpty()) {
                    Text(deal.merchantName, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (deal.description.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(deal.description, style = MaterialTheme.typography.bodyMedium)
                }

                val savings = when {
                    deal.discountAmount > 0 -> "$%.2f off".format(deal.discountAmountDollars)
                    deal.discountPercent > 0 -> "%.0f%% off".format(deal.discountPercent)
                    else -> null
                }
                if (savings != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(savings, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold)
                }

                // The threshold comes from the deal, not the progress payload.
                val p = progress
                val needed = deal.minOrderCount
                if (p != null && needed > 0) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { p.fraction(needed) },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (p.isUnlocked(needed)) stringResource(R.string.deal_unlocked)
                        else stringResource(R.string.deal_progress, p.ordersCount, needed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
