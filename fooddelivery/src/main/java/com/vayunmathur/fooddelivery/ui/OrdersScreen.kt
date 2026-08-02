package com.vayunmathur.fooddelivery.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.fooddelivery.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconPackage
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.fooddelivery.api.BitesApi
import com.vayunmathur.fooddelivery.data.FeedbackRequest
import com.vayunmathur.fooddelivery.data.Order
import java.text.SimpleDateFormat
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.TimeZone

@Composable
fun OrdersScreen(onTrackOrder: (Int) -> Unit = {}) {
    val isLoggedIn = remember { BitesApi.isLoggedIn() }

    var orders by remember { mutableStateOf<List<Order>>(emptyList()) }
    var loading by remember { mutableStateOf(isLoggedIn) }
    var ratingOrder by remember { mutableStateOf<Order?>(null) }
    val scope = rememberCoroutineScope()

    ratingOrder?.let { target ->
        FeedbackDialog(
            order = target,
            onDismiss = { ratingOrder = null },
            onSubmit = { rating, note, extraTipCents ->
                scope.launch {
                    target.uuid?.let {
                        BitesApi.submitFeedback(
                            it,
                            FeedbackRequest(
                                orderId = target.id,
                                rating = rating,
                                feedback = note.ifBlank { null },
                                tips = extraTipCents,
                            ),
                        )
                    }
                    ratingOrder = null
                }
            },
        )
    }

    LaunchedEffect(Unit) {
        if (isLoggedIn) {
            orders = BitesApi.getOrders()
            loading = false
        }
    }

    Scaffold { padding ->
        if (!isLoggedIn) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconPerson(modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.sign_in_to_view_your_orders), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.go_to_account_to_sign_in),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (orders.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconPackage(modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.no_orders_yet), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.your_order_history_will_appear_here),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(padding)
            ) {
                items(orders) { order ->
                    OrderCard(
                        order = order,
                        // Only in-flight deliveries have anything to track.
                        onTrack = if (!order.isDone && order.isDelivery) {
                            { onTrackOrder(order.id) }
                        } else null,
                        // Only finished orders can be rated; pickup orders that haven't been
                        // collected get the pick-up action instead.
                        onRate = if (order.isDone && order.uuid != null) {
                            { ratingOrder = order }
                        } else null,
                        onPickUp = if (!order.isDone && !order.isDelivery && order.uuid != null) {
                            {
                                scope.launch {
                                    if (BitesApi.pickUpOrder(order.uuid)) orders = BitesApi.getOrders()
                                }
                            }
                        } else null,
                    )
                }
            }
        }
    }
}

private fun formatDate(iso: String?): String {
    if (iso.isNullOrEmpty()) return ""
    return try {
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        parser.timeZone = TimeZone.getTimeZone("UTC")
        val date = parser.parse(iso.substringBefore(".").substringBefore("Z")) ?: return iso
        val formatter = SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US)
        formatter.format(date)
    } catch (_: Exception) { iso }
}

@Composable
private fun OrderCard(
    order: Order,
    onTrack: (() -> Unit)? = null,
    onRate: (() -> Unit)? = null,
    onPickUp: (() -> Unit)? = null,
) {
    val merchantName = order.merchant?.name ?: ""
    val merchantImage = order.merchant?.imageUrl ?: order.merchant?.logoUrl ?: ""

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    if (merchantImage.isNotEmpty()) {
                        AsyncImage(
                            model = merchantImage,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp).clip(CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Column {
                        Text(merchantName, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall)
                        Text(formatDate(order.createdAt), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                StatusChip(order.displayStatus)
            }
            Spacer(Modifier.height(8.dp))
            order.orderItems.forEach { item ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${item.quantity}x ${item.name ?: ""}",
                            style = MaterialTheme.typography.bodySmall)
                        if (item.modifiers.isNotEmpty()) {
                            Text(item.modifiers.mapNotNull { it.name }.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text("$%.2f".format(item.priceDollars * item.quantity),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.total), fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium)
                Text("$%.2f".format(order.displayTotal), fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium)
            }

            if (onRate != null || onPickUp != null || onTrack != null) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onTrack != null) {
                        Button(onClick = onTrack) { Text(stringResource(R.string.track_order)) }
                    }
                    if (onPickUp != null) {
                        Button(onClick = onPickUp) { Text(stringResource(R.string.mark_picked_up)) }
                    }
                    if (onRate != null) {
                        OutlinedButton(onClick = onRate) { Text(stringResource(R.string.rate_order)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val color = when (status) {
        "Delivered", "Picked up" -> MaterialTheme.colorScheme.primary
        "In progress" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            status,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/** Rate a completed order: 1-5 stars, an optional note, and an optional extra tip. */
@Composable
private fun FeedbackDialog(
    order: Order,
    onDismiss: () -> Unit,
    onSubmit: (rating: Int, note: String, extraTipCents: Int) -> Unit,
) {
    var rating by remember { mutableStateOf(5) }
    var note by remember { mutableStateOf("") }
    var extraTip by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rate_your_order)) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..5).forEach { star ->
                        IconButton(onClick = { rating = star }) {
                            IconStar(
                                modifier = Modifier.size(28.dp),
                                tint = if (star <= rating) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.feedback_optional)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.add_extra_tip), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 100, 200, 500).forEach { cents ->
                        FilterChip(
                            selected = extraTip == cents,
                            onClick = { extraTip = cents },
                            label = { Text(if (cents == 0) "None" else "$%.2f".format(cents / 100.0)) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSubmit(rating, note, extraTip) }) {
                Text(stringResource(R.string.submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
