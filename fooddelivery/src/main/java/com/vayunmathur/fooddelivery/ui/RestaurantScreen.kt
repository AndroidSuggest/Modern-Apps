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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.RadioButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.fooddelivery.api.BitesApi
import com.vayunmathur.fooddelivery.data.CartItem
import com.vayunmathur.fooddelivery.data.MerchantDetail
import com.vayunmathur.fooddelivery.data.MenuItem
import com.vayunmathur.fooddelivery.data.Modifier as DataModifier

@Composable
fun RestaurantScreen(
    merchantId: Int,
    onBack: () -> Unit,
    onAddToCart: (CartItem) -> Unit,
) {
    var merchant by remember { mutableStateOf<MerchantDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var customizeItem by remember { mutableStateOf<MenuItem?>(null) }
    var customizeMerchantName by remember { mutableStateOf("") }

    LaunchedEffect(merchantId) {
        merchant = BitesApi.getMerchantDetail(merchantId)
        loading = false
    }

    customizeItem?.let { item ->
        ModifierDialog(
            item = item,
            onDismiss = { customizeItem = null },
            onConfirm = { selectedModifiers ->
                onAddToCart(CartItem(
                    menuItem = item,
                    merchantId = merchantId,
                    merchantName = customizeMerchantName,
                    selectedModifiers = selectedModifiers
                ))
                customizeItem = null
            }
        )
    }

    Scaffold { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (merchant == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Restaurant not found")
            }
        } else {
            val m = merchant!!
            val itemsById = m.items.associateBy { it.id }

            LazyColumn(
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.padding(padding)
            ) {
                if (m.imageUrl.isNotEmpty()) {
                    item {
                        Box {
                            AsyncImage(
                                model = m.imageUrl,
                                contentDescription = m.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                            )
                            IconButton(onClick = onBack,
                                modifier = Modifier.padding(4.dp)) {
                                IconBack()
                            }
                        }
                    }
                } else {
                    item {
                        IconButton(onClick = onBack,
                            modifier = Modifier.padding(4.dp)) {
                            IconBack()
                        }
                    }
                }
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text(m.name, style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold)
                        if (m.merchantTags.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(m.merchantTags.joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            if ((m.averageRating ?: 0.0) > 0) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconStar(Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(2.dp))
                                    Text("%.1f (%d)".format(m.averageRating, m.totalRatings ?: 0),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (m.nextOpenWindow.isNotEmpty()) {
                                Text(m.nextOpenWindow, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            if ((m.rewardsPercentage ?: 0.0) > 0) {
                                Text("${m.rewardsPercentage?.toInt()}% back",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }

                val activeCategories = m.categories
                    .filter { it.isActive }
                    .sortedBy { it.sortOrder }

                activeCategories.forEach { category ->
                    val categoryItems = category.itemIds.mapNotNull { itemsById[it] }
                        .filter { it.isAvailable && it.isInStock }
                    if (categoryItems.isNotEmpty()) {
                        item {
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(category.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp))
                            Spacer(Modifier.height(8.dp))
                        }
                        items(categoryItems) { menuItem ->
                            MenuItemRow(menuItem) {
                                if (menuItem.modifierGroups.isNotEmpty()) {
                                    customizeMerchantName = m.name
                                    customizeItem = menuItem
                                } else {
                                    onAddToCart(CartItem(
                                        menuItem = menuItem,
                                        merchantId = merchantId,
                                        merchantName = m.name
                                    ))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModifierDialog(
    item: MenuItem,
    onDismiss: () -> Unit,
    onConfirm: (List<DataModifier>) -> Unit,
) {
    val selections = remember { mutableStateMapOf<Int, MutableSet<Int>>() }

    item.modifierGroups.forEach { group ->
        if (group.id !in selections) {
            selections[group.id] = mutableSetOf()
        }
    }

    val allModifiers = item.modifierGroups.flatMap { group ->
        val selected = selections[group.id] ?: emptySet()
        group.modifiers.filter { it.id in selected }
    }
    val extrasTotal = allModifiers.sumOf { it.priceDollars }
    val totalPrice = item.priceDollars + extrasTotal

    val requiredMet = item.modifierGroups.all { group ->
        !group.required || (selections[group.id]?.size ?: 0) >= group.minSelections
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (item.description.isNotEmpty()) {
                    Text(item.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                Text("$%.2f".format(item.priceDollars),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium)
                item.modifierGroups.forEach { group ->
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Text(group.name, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall)
                        if (group.required) {
                            Spacer(Modifier.width(4.dp))
                            Text("Required", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (group.maxSelections > 1 || !group.required) {
                        Text("Select up to ${group.maxSelections}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(4.dp))
                    val selected = selections[group.id] ?: mutableSetOf()
                    val isSingleSelect = group.maxSelections == 1

                    group.modifiers.forEach { mod ->
                        val isSelected = mod.id in selected
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isSingleSelect) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        selections[group.id] = mutableSetOf(mod.id)
                                    }
                                )
                            } else {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        val set = selections.getOrPut(group.id) { mutableSetOf() }
                                        if (checked && set.size < group.maxSelections) {
                                            set.add(mod.id)
                                        } else {
                                            set.remove(mod.id)
                                        }
                                        selections[group.id] = set.toMutableSet()
                                    }
                                )
                            }
                            Text(mod.name, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f))
                            if (mod.price > 0) {
                                Text("+$%.2f".format(mod.priceDollars),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(allModifiers) },
                enabled = requiredMet
            ) {
                Text("Add $%.2f".format(totalPrice))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun MenuItemRow(item: MenuItem, onAdd: () -> Unit) {
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
                Text("Customizable",
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
