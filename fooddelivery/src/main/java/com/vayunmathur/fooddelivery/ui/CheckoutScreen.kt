package com.vayunmathur.fooddelivery.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.rememberPaymentSheet
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconLocationOn
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.SegmentedButtonDefaults
import com.vayunmathur.library.ui.SegmentedButton
import com.vayunmathur.library.ui.SingleChoiceSegmentedButtonRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.fooddelivery.api.BitesApi
import com.vayunmathur.fooddelivery.data.AddressStore
import com.vayunmathur.fooddelivery.data.CartItem
import com.vayunmathur.fooddelivery.data.CheckoutAddress
import com.vayunmathur.fooddelivery.data.CheckoutCartItem
import com.vayunmathur.fooddelivery.data.CheckoutModifier
import com.vayunmathur.fooddelivery.data.CheckoutRequest
import com.vayunmathur.fooddelivery.data.CheckoutResponse
import android.util.Log
import kotlinx.coroutines.delay

@Composable
fun CheckoutScreen(
    items: List<CartItem>,
    onBack: () -> Unit,
    onOrderPlaced: () -> Unit,
) {
    val context = LocalContext.current

    var isPickup by remember { mutableStateOf(false) }
    var tipCents by remember { mutableIntStateOf(300) }
    var deliveryInstructions by remember { mutableStateOf(AddressStore.getDefault(context)?.deliveryInstructions ?: "") }
    var paying by remember { mutableStateOf(false) }
    var fetchingPrices by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var orderSuccess by remember { mutableStateOf(false) }
    var checkoutResponse by remember { mutableStateOf<CheckoutResponse?>(null) }

    val addresses = remember { AddressStore.getAll(context) }
    var selectedAddress by remember { mutableStateOf(AddressStore.getDefault(context)) }

    val subtotalCents = items.sumOf {
        (it.menuItem.price + it.selectedModifiers.sumOf { m -> m.price }) * it.quantity
    }
    val subtotal = subtotalCents / 100.0

    val confirmedOrder = checkoutResponse?.order
    val merchantId = items.firstOrNull()?.merchantId ?: 0
    val canFetch = items.isNotEmpty() && (isPickup || selectedAddress != null)

    LaunchedEffect(isPickup, tipCents, selectedAddress?.id) {
        if (!canFetch) return@LaunchedEffect
        checkoutResponse = null
        error = null
        fetchingPrices = true
        delay(400)
        val cartItems = items.map { item ->
            CheckoutCartItem(
                itemId = item.menuItem.id,
                quantity = item.quantity,
                modifiers = item.selectedModifiers.map { mod ->
                    val groupId = item.menuItem.modifierGroups
                        .firstOrNull { g -> g.modifiers.any { it.id == mod.id } }?.id ?: 0
                    CheckoutModifier(
                        modifierId = mod.id,
                        modifierGroupId = groupId,
                        name = mod.name,
                        price = mod.price,
                    )
                }
            )
        }
        val addr = if (!isPickup) selectedAddress?.let { a ->
            CheckoutAddress(
                addressStreet = a.addressStreet,
                addressCity = a.addressCity,
                addressState = a.addressState,
                addressZip = a.addressZip,
                addressUnit = a.aptUnit,
                latitude = a.latitude,
                longitude = a.longitude,
            )
        } else null
        val request = CheckoutRequest(
            cartItems = cartItems,
            address = addr,
            isPickup = isPickup,
            tips = tipCents,
            deliveryInstructions = deliveryInstructions.ifBlank { null },
            gateCode = selectedAddress?.gateCode?.ifBlank { null },
        )
        val response = BitesApi.checkout(merchantId, request)
        Log.d("Checkout", "response.order=${response?.order}")
        Log.d("Checkout", "response.clientSecret=${response?.clientSecret?.take(20)}")
        Log.d("Checkout", "response.serviceable=${response?.serviceable}")
        if (response?.order != null) {
            val o = response.order
            Log.d("Checkout", "order: foodTotal=${o.foodTotal} taxes=${o.taxes} deliveryFee=${o.deliveryFee} fees=${o.fees} tips=${o.tips} displayTotal=${o.displayTotal}")
        }
        if (response == null) {
            error = "Failed to load pricing. Please try again."
        } else if (!response.isServiceable) {
            error = "This address is not serviceable for delivery."
        } else {
            checkoutResponse = response
        }
        fetchingPrices = false
    }

    val paymentSheet = rememberPaymentSheet { result ->
        when (result) {
            is PaymentSheetResult.Completed -> {
                orderSuccess = true
                onOrderPlaced()
            }
            is PaymentSheetResult.Canceled -> {
                paying = false
            }
            is PaymentSheetResult.Failed -> {
                error = result.error.localizedMessage ?: "Payment failed"
                paying = false
            }
        }
    }

    if (orderSuccess) {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Order Confirmed") }) }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                IconCheck(modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text("Order placed!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Your order is being prepared.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Checkout") },
                navigationIcon = {
                    IconButton(onClick = onBack) { IconBack() }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                item {
                    Text("Order Summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                }

                items(items.size) { index ->
                    val item = items[index]
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("${item.quantity}x ${item.menuItem.name}",
                                style = MaterialTheme.typography.bodyMedium)
                            if (item.selectedModifiers.isNotEmpty()) {
                                Text(item.selectedModifiers.joinToString(", ") { it.name },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text("$%.2f".format(item.totalPrice), style = MaterialTheme.typography.bodyMedium)
                    }
                }

                item {
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    Text("Order Type", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !isPickup,
                            onClick = { isPickup = false },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                            label = { Text("Delivery") }
                        )
                        SegmentedButton(
                            selected = isPickup,
                            onClick = { isPickup = true },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                            label = { Text("Pickup") }
                        )
                    }
                }

                if (!isPickup) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        Text("Delivery Address", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))

                        if (addresses.isEmpty()) {
                            Text("No saved addresses. Add one in Account settings.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            addresses.forEach { addr ->
                                val isSelected = selectedAddress?.id == addr.id
                                Card(
                                    onClick = {
                                        selectedAddress = addr
                                        if (addr.deliveryInstructions.isNotEmpty()) {
                                            deliveryInstructions = addr.deliveryInstructions
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        IconLocationOn(
                                            modifier = Modifier.size(20.dp),
                                            tint = if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(addr.label.ifEmpty { "Address" },
                                                fontWeight = FontWeight.Medium,
                                                style = MaterialTheme.typography.bodyMedium)
                                            Text(addr.addressStreet,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            val cityStateZip = listOfNotNull(
                                                addr.addressCity.ifEmpty { null },
                                                addr.addressState.ifEmpty { null },
                                                addr.addressZip.ifEmpty { null },
                                            ).joinToString(", ")
                                            if (cityStateZip.isNotEmpty()) {
                                                Text(cityStateZip,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            if (addr.aptUnit.isNotEmpty()) {
                                                Text("Apt/Unit: ${addr.aptUnit}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            if (addr.gateCode.isNotEmpty()) {
                                                Text("Gate: ${addr.gateCode}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        if (isSelected) {
                                            IconCheck(modifier = Modifier.size(20.dp),
                                                tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = deliveryInstructions,
                            onValueChange = { deliveryInstructions = it },
                            label = { Text("Delivery instructions (optional)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                item {
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    Text("Tip", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 200, 300, 500).forEach { cents ->
                            val label = if (cents == 0) "None" else "$%.2f".format(cents / 100.0)
                            FilterChip(
                                selected = tipCents == cents,
                                onClick = { tipCents = cents },
                                label = { Text(label) }
                            )
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    PriceRow("Subtotal", subtotal)
                    if (fetchingPrices) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Calculating tax & fees…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else if (confirmedOrder != null) {
                        PriceRow("Tax", confirmedOrder.taxesDollars)
                        if (confirmedOrder.deliveryFee > 0) PriceRow("Delivery fee", confirmedOrder.deliveryFeeDollars)
                        if (confirmedOrder.fees != null && confirmedOrder.fees > 0) PriceRow("Service fees", confirmedOrder.fees / 100.0)
                        if (confirmedOrder.tips > 0) PriceRow("Tip", confirmedOrder.tipsDollars)
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Total", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("$%.2f".format(confirmedOrder.displayTotal), fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }

                if (error != null) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(error!!, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Button(
                        onClick = {
                            paying = true
                            paymentSheet.presentWithPaymentIntent(
                                checkoutResponse!!.clientSecret,
                                PaymentSheet.Configuration(
                                    merchantDisplayName = items.firstOrNull()?.merchantName ?: "Food Delivery",
                                )
                            )
                        },
                        enabled = checkoutResponse != null && !paying && !fetchingPrices,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (paying || fetchingPrices) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            val payTotal = confirmedOrder?.displayTotal
                            if (payTotal != null) {
                                Text("Place Order · $%.2f".format(payTotal))
                            } else {
                                Text("Place Order")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PriceRow(label: String, amount: Double) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$%.2f".format(amount), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
