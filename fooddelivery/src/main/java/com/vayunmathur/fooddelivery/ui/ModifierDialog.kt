package com.vayunmathur.fooddelivery.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.fooddelivery.R
import com.vayunmathur.fooddelivery.data.MenuItem
import com.vayunmathur.fooddelivery.data.SelectedModifier
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.RadioButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

/**
 * Pick modifiers for [item]. Pass [initialSelection] to reopen it over an existing choice
 * (editing a cart line) instead of starting empty.
 */
@Composable
fun ModifierDialog(
    item: MenuItem,
    onDismiss: () -> Unit,
    onConfirm: (List<SelectedModifier>) -> Unit,
    initialSelection: List<SelectedModifier> = emptyList(),
    confirmLabel: String? = null,
) {
    val selections = remember(item.id, initialSelection) {
        mutableStateMapOf<Int, MutableSet<Int>>().apply {
            initialSelection.forEach { sel ->
                getOrPut(sel.modifierGroupId) { mutableSetOf() }.add(sel.modifierId)
            }
            // Seeded here rather than written back during composition, which would dirty the
            // map it had just been read from and force another pass.
            item.modifierGroups.forEach { group -> getOrPut(group.id) { mutableSetOf() } }
        }
    }

    // Capture each pick together with the group it came from, so checkout never has to
    // reconstruct modifierGroupId by searching the menu (which silently fell back to 0).
    val allModifiers = item.modifierGroups.flatMap { group ->
        val selected = selections[group.id] ?: emptySet()
        group.modifiers.filter { it.id in selected }.map { mod ->
            SelectedModifier(
                modifierGroupId = group.id,
                modifierId = mod.id,
                name = mod.name,
                price = mod.price,
            )
        }
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
                            Text(stringResource(R.string.required), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (group.maxSelections > 1 || !group.required) {
                        Text(stringResource(R.string.select_up_to, group.maxSelections),
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
                Text(confirmLabel ?: "Add $%.2f".format(totalPrice))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
