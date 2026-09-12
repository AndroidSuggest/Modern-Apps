@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.health.ui

import kotlin.uuid.Uuid
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.data.*
import com.vayunmathur.health.util.HealthViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IngredientQuantityDialog(
    viewModel: HealthViewModel,
    ingredient: Ingredient,
    initialQuantity: Double,
    initialUnit: ServingUnit,
    onDismiss: () -> Unit,
    onConfirm: (Double, ServingUnit) -> Unit
) {
    var quantityStr by remember { mutableStateOf(initialQuantity.toString()) }
    var selectedUnit by remember { mutableStateOf(initialUnit) }
    var unitExpanded by remember { mutableStateOf(false) }
    var availableUnits by remember { mutableStateOf(listOf<ServingUnit>()) }

    LaunchedEffect(ingredient.id) {
        val units = viewModel.getUnitsForIngredient(ingredient.id)
        // Ensure the default unit or current unit is in the list
        val unitsWithCurrent = if (units.none { it.name == initialUnit.name }) {
            units + initialUnit
        } else units

        // Add "g" if not present at all
        val finalUnits = if (unitsWithCurrent.none { it.name == "g" }) {
            unitsWithCurrent + ServingUnit(id = Uuid.random().toString(), ingredientId = ingredient.id, name = "g", grams = 1.0)
        } else unitsWithCurrent

        availableUnits = finalUnits
        selectedUnit = finalUnits.find { it.name == initialUnit.name } ?: initialUnit
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_quantity_for_format, ingredient.displayName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = quantityStr,
                    onValueChange = { quantityStr = it },
                    label = { Text(stringResource(R.string.quantity)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Box(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = selectedUnit.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.unit)) },
                        trailingIcon = {
                            IconButton(onClick = { unitExpanded = true }) {
                                IconArrowDropDown()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = unitExpanded,
                        onDismissRequest = { unitExpanded = false }
                    ) {
                        availableUnits.forEach { unit ->
                            SelectableDropdownMenuItem(
                                selected = unit == selectedUnit,
                                onClick = {
                                    selectedUnit = unit
                                    unitExpanded = false
                                },
                                text = { Text(unit.name) },
                                selectedLeadingIcon = { IconCheck() },
                            )
                        }
                    }
                    Box(Modifier.matchParentSize().clickable { unitExpanded = true })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val q = quantityStr.toDoubleOrNull() ?: 0.0
                    onConfirm(q, selectedUnit)
                },
                enabled = quantityStr.toDoubleOrNull() != null
            ) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) }
        }
    )
}
