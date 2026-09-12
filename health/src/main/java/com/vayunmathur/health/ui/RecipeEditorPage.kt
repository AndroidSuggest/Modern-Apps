@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.health.ui

import kotlin.uuid.Uuid
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.*
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.R as UiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeEditorPage(backStack: NavBackStack<Route>, viewModel: HealthViewModel, recipeId: String? = null) {
    var recipeName by remember { mutableStateOf("") }
    var recipeIngredients by remember { mutableStateOf(listOf<RecipeIngredientData>()) }
    var showSearch by remember { mutableStateOf(false) }
    var editingIngredientData by remember { mutableStateOf<RecipeIngredientData?>(null) }
    var isAddingNewIngredient by remember { mutableStateOf(false) }
    // Guards against repeated taps launching multiple saves (which created
    // duplicate recipes and popped the back stack more than once).
    var saving by remember { mutableStateOf(false) }

    // Load existing recipe if editing
    LaunchedEffect(recipeId) {
        if (recipeId != null) {
            val loaded = viewModel.loadRecipeForEdit(recipeId)
            if (loaded != null) {
                recipeName = loaded.name
                recipeIngredients = loaded.ingredients.map { RecipeIngredientData(it.ingredient, it.unit, it.quantity) }
            }
        }
    }

    if (showSearch) {
        IngredientSearchDialog(
            viewModel = viewModel,
            includeLocal = true,
            onDismiss = { showSearch = false },
            onIngredientSelected = { ingredient ->
                showSearch = false
                // Default to 100g
                editingIngredientData = RecipeIngredientData(
                    ingredient,
                    ServingUnit(id = Uuid.random().toString(), ingredientId = ingredient.id, name = "g", grams = 1.0),
                    100.0
                )
                isAddingNewIngredient = true
            }
        )
    }

    if (editingIngredientData != null) {
        IngredientQuantityDialog(
            viewModel = viewModel,
            ingredient = editingIngredientData!!.ingredient,
            initialQuantity = editingIngredientData!!.quantity,
            initialUnit = editingIngredientData!!.unit,
            onDismiss = {
                editingIngredientData = null
                isAddingNewIngredient = false
            },
            onConfirm = { quantity, unit ->
                if (isAddingNewIngredient) {
                    recipeIngredients = recipeIngredients + RecipeIngredientData(editingIngredientData!!.ingredient, unit, quantity)
                } else {
                    recipeIngredients = recipeIngredients.map {
                        if (it === editingIngredientData) {
                            RecipeIngredientData(it.ingredient, unit, quantity)
                        } else it
                    }
                }
                editingIngredientData = null
                isAddingNewIngredient = false
            }
        )
    }

    AppScaffold(
        title = if (recipeId == null) stringResource(R.string.create_recipe) else stringResource(R.string.edit_recipe),
        backStack = backStack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = recipeName,
                onValueChange = { recipeName = it },
                label = { Text(stringResource(R.string.recipe_name)) },
                modifier = Modifier.fillMaxWidth()
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ingredients), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { showSearch = true }) {
                    IconAdd()
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(UiR.string.add))
                }
            }

            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recipeIngredients, key = { it.ingredient.id }) { riData ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            editingIngredientData = riData
                            isAddingNewIngredient = false
                        }
                    ) {
                        Row(modifier = Modifier.padding(8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(riData.ingredient.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text("${riData.quantity} ${riData.unit.name}", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = {
                                recipeIngredients = recipeIngredients - riData
                            }) {
                                IconDelete()
                            }
                        }
                    }
                }
            }

            Button(
                onClick = {
                    if (saving) return@Button
                    saving = true
                    val items = recipeIngredients.map {
                        HealthViewModel.RecipeIngredientLoad(it.ingredient, it.unit, it.quantity)
                    }
                    viewModel.saveRecipe(recipeId, recipeName, items) {
                        backStack.pop()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = recipeName.isNotBlank() && recipeIngredients.isNotEmpty() && !saving
            ) {
                Text(stringResource(R.string.save_recipe))
            }
        }
    }
}

data class RecipeIngredientData(
    val ingredient: Ingredient,
    val unit: ServingUnit,
    val quantity: Double
)
