package com.vayunmathur.health.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ExposedDropdownMenuBox
import com.vayunmathur.library.ui.ExposedDropdownMenuDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ExposedDropdownMenuAnchorType
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.data.Ingredient
import com.vayunmathur.health.data.Recipe
import com.vayunmathur.health.util.HealthViewModel
import java.time.Instant

enum class HydrationUnit(@StringRes val displayName: Int, val toLiters: Double) {
    Liters(R.string.hydration_liters, 1.0),
    Milliliters(R.string.hydration_milliliters, 0.001),
    Ounces(R.string.hydration_ounces, 0.0295735),
    Cups(R.string.hydration_cups, 0.236588)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogHydrationDialog(viewModel: HealthViewModel, initialTime: Instant? = null, onDismiss: () -> Unit) {
    var quantityStr by remember { mutableStateOf("") }
    var selectedUnit by remember { mutableStateOf(HydrationUnit.Liters) }
    var unitExpanded by remember { mutableStateOf(false) }

    val quantity = quantityStr.toDoubleOrNull()
    val isValid = quantity != null && quantity > 0

    AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.log_hydration)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                            value = quantityStr,
                            onValueChange = { quantityStr = it },
                            label = { Text(stringResource(R.string.quantity)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                ExposedDropdownMenuBox(
                                        expanded = unitExpanded,
                                        onExpandedChange = { unitExpanded = !unitExpanded }
                                ) {
                                    Row(
                                            modifier =
                                                    Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                                            .clickable { unitExpanded = true }
                                                            .padding(end = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                                stringResource(selectedUnit.displayName),
                                                style = MaterialTheme.typography.bodyMedium
                                        )
                                        ExposedDropdownMenuDefaults.TrailingIcon(
                                                expanded = unitExpanded
                                        )
                                    }
                                    ExposedDropdownMenu(
                                            expanded = unitExpanded,
                                            onDismissRequest = { unitExpanded = false }
                                    ) {
                                        HydrationUnit.entries.forEach { unit ->
                                            DropdownMenuItem(
                                                    text = { Text(stringResource(unit.displayName)) },
                                                    onClick = {
                                                        selectedUnit = unit
                                                        unitExpanded = false
                                                    }
                                            )
                                        }
                                    }
                                }
                            }
                    )
                }
            },
            confirmButton = {
                Button(
                        enabled = isValid,
                        onClick = {
                            if (quantity != null) {
                                val time = initialTime ?: Instant.now()
                                viewModel.logHydration(quantity * selectedUnit.toLiters, time)
                                onDismiss()
                            }
                        }
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

sealed class Loggable {
    abstract val id: String
    abstract val name: String

    data class RecipeWrapper(val recipe: Recipe) : Loggable() {
        override val id = recipe.id
        override val name = recipe.name
    }

    data class IngredientWrapper(val ingredient: Ingredient) : Loggable() {
        override val id = ingredient.id
        override val name = ingredient.displayName
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogMealDialog(viewModel: HealthViewModel, initialTime: Instant? = null, onDismiss: () -> Unit) {
    val recipes by viewModel.allRecipes.collectAsState(emptyList())
    val ingredientRecipes by viewModel.ingredientsAsRecipes.collectAsState(emptyList())

    val allLoggables =
            remember(recipes, ingredientRecipes) {
                recipes.map { Loggable.RecipeWrapper(it) } +
                        ingredientRecipes.map { Loggable.IngredientWrapper(it) }
            }

    var selectedLoggable by remember { mutableStateOf<Loggable?>(null) }
    var quantityStr by remember { mutableStateOf("1") }
    var expanded by remember { mutableStateOf(false) }

    val quantity = quantityStr.toDoubleOrNull()
    val isValid = selectedLoggable != null && quantity != null && quantity > 0

    AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.log_meal)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = !expanded }
                    ) {
                        OutlinedTextField(
                                value = selectedLoggable?.name ?: stringResource(R.string.select_recipe),
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                                },
                                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                        ) {
                            allLoggables.sortedBy { it.name }.forEach { loggable ->
                                DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(loggable.name)
                                                if (loggable is Loggable.IngredientWrapper) {
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(
                                                            stringResource(R.string.ingredient),
                                                            style =
                                                                    MaterialTheme.typography
                                                                            .bodySmall,
                                                            color =
                                                                    MaterialTheme.colorScheme
                                                                            .outline
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            selectedLoggable = loggable
                                            expanded = false
                                        }
                                )
                            }
                        }
                    }

                    val labelText = if (selectedLoggable is Loggable.IngredientWrapper) {
                        stringResource(R.string.quantity_x_100g)
                    } else {
                        stringResource(R.string.servings)
                    }
                    OutlinedTextField(
                            value = quantityStr,
                            onValueChange = { quantityStr = it },
                            label = { Text(labelText) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                        enabled = isValid,
                        onClick = {
                            val q = quantity
                            val target = selectedLoggable
                            if (q != null && target != null) {
                                val time = initialTime ?: Instant.now()
                                val logTarget = when (target) {
                                    is Loggable.RecipeWrapper ->
                                        HealthViewModel.LogMealTarget.FromRecipe(target.recipe.id, target.recipe.name)
                                    is Loggable.IngredientWrapper ->
                                        HealthViewModel.LogMealTarget.FromIngredient(target.ingredient)
                                }
                                viewModel.logMeal(logTarget, q, time)
                                onDismiss()
                            }
                        }
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
