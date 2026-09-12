package com.vayunmathur.fooddelivery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.fooddelivery.R
import com.vayunmathur.fooddelivery.data.MenuItem
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.fooddelivery.data.MerchantRewards

@Composable
internal fun MenuItemRow(item: MenuItem, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium)
            if (item.description.isNotEmpty()) {
                Text(item.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$%.2f".format(item.priceDollars), style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium)
                val ddPrice = item.doordashPriceDollars
                if (ddPrice != null && ddPrice > item.priceDollars) {
                    Text("$%.2f on DD".format(ddPrice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (item.modifierGroups.isNotEmpty()) {
                Text(stringResource(R.string.customizable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(8.dp))
        if (item.displayImage.isNotEmpty()) {
            AsyncImage(
                model = item.displayImage,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(4.dp))
        }
        IconButton(onClick = onAdd) {
            IconAdd(tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Per-merchant reward balance plus loyalty enrolment. Joining takes the merchant's invite
 * code; leaving removes the customer from that merchant's loyalty programme.
 */
@Composable
internal fun MerchantRewardsCard(
    rewards: MerchantRewards?,
    onJoin: (String) -> Unit,
    onLeave: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val enrolled = rewards != null

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.rewards), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            if (rewards != null && rewards.balance > 0) {
                Text(
                    stringResource(R.string.rewards_available,
                        "$%.2f".format(rewards.balanceDollars)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(stringResource(R.string.no_rewards_here_yet),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(8.dp))
            if (enrolled) {
                OutlinedButton(onClick = onLeave) { Text(stringResource(R.string.leave_loyalty)) }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text(stringResource(R.string.invite_code)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    com.vayunmathur.library.ui.Button(
                        onClick = { onJoin(code.trim()); code = "" },
                        enabled = code.isNotBlank(),
                    ) { Text(stringResource(R.string.join)) }
                }
            }
        }
    }
}
