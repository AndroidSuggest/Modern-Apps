package com.vayunmathur.taxi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.taxi.R
import com.vayunmathur.taxi.data.ActiveRide
import com.vayunmathur.taxi.data.RideStatus

@Composable
internal fun StatusHeader(ride: ActiveRide, ended: Boolean) {
    val over = ended || ride.status.isTerminal
    Column {
        Text(
            stringResource(statusLabel(ride.status, ended)),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        if (!over) {
            val etaSeconds = ride.pickupEtaSeconds
            val etaText = when {
                ride.status == RideStatus.ARRIVED -> stringResource(R.string.status_arrived)
                ride.status == RideStatus.PICKED_UP -> stringResource(R.string.status_on_trip)
                etaSeconds != null && etaSeconds > 0 ->
                    stringResource(R.string.eta_minutes, (etaSeconds + 59) / 60)
                ride.status.isPrePickup -> stringResource(R.string.driver_arriving_now)
                else -> null
            }
            if (etaText != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    etaText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { statusProgress(ride.status, over) },
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
        )
    }
}

@Composable
internal fun DriverCard(ride: ActiveRide, onCall: (String) -> Unit) {
    val driver = ride.driver
    val vehicle = ride.vehicle
    if (driver == null && vehicle == null) return

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val image = driver?.imageUrl?.takeIf { it.isNotBlank() }
                if (image != null) {
                    AsyncImage(
                        model = image,
                        contentDescription = driver.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(CircleShape),
                    )
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = CircleShape,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) { IconPerson() }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.your_driver),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val name = driver?.displayName?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.driver_label)
                    Text(
                        name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    driver?.rating?.let { rating ->
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconStar(Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "%.1f".format(rating),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                driver?.phoneNumber?.takeIf { it.isNotBlank() }?.let { phone ->
                    OutlinedButton(onClick = { onCall(phone) }) {
                        Text(stringResource(R.string.call))
                    }
                }
            }

            vehicle?.let { v ->
                val desc = v.description
                if (desc.isNotBlank() || v.licensePlate != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            if (desc.isNotBlank()) {
                                Text(desc, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        v.licensePlate?.let { plate ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Text(
                                    plate,
                                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun statusLabel(status: RideStatus, ended: Boolean): Int = when {
    ended && status != RideStatus.CANCELED && !status.isTerminal -> R.string.ride_ended
    status == RideStatus.CANCELED -> R.string.ride_ended
    status.isTerminal -> R.string.ride_complete
    status == RideStatus.ARRIVED -> R.string.status_arrived
    status == RideStatus.PICKED_UP -> R.string.status_on_trip
    status == RideStatus.ACCEPTED || status == RideStatus.APPROACHING -> R.string.status_en_route
    else -> R.string.status_finding_driver
}

internal fun statusProgress(status: RideStatus, over: Boolean): Float = when {
    over -> 1f
    status == RideStatus.PENDING -> 0.15f
    status == RideStatus.ACCEPTED -> 0.35f
    status == RideStatus.APPROACHING -> 0.55f
    status == RideStatus.ARRIVED -> 0.7f
    status == RideStatus.PICKED_UP -> 0.9f
    else -> 0.1f
}
