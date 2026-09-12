@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.health.ui

import kotlin.uuid.Uuid
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.*
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.health.util.FoodDatabase
import com.vayunmathur.health.util.FoodSearchAPI
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.*
import com.vayunmathur.library.ui.BackupButtons
import kotlinx.coroutines.launch
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeManagementPage(backStack: NavBackStack<Route>, viewModel: HealthViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showIngredientSearch by remember { mutableStateOf(false) }

    val recipes by viewModel.allRecipes.collectAsState(emptyList())
    val ingredients by viewModel.allIngredients.collectAsState(emptyList())

    val isListEmpty = if (selectedTab == 0) recipes.isEmpty() else ingredients.isEmpty()

    if (showIngredientSearch) {
        IngredientSearchDialog(
            viewModel = viewModel,
            onDismiss = { showIngredientSearch = false },
            onIngredientSelected = { ingredient ->
                viewModel.insertIngredient(ingredient)
                showIngredientSearch = false
            }
        )
    }

    AppScaffold(
        title = stringResource(R.string.recipes),
        backStack = backStack,
        actions = { BackupButtons() },
        floatingActionButton = {
            if (!isListEmpty) {
                FloatingActionButton(onClick = { 
                    if (selectedTab == 0) {
                        backStack.add(Route.RecipeEditor()) 
                    } else {
                        showIngredientSearch = true
                    }
                }) {
                    IconAdd()
                }
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                contentColor = HealthColors.Nutrition,
            ) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, selectedContentColor = HealthColors.Nutrition, text = { Text(stringResource(R.string.recipes)) })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, selectedContentColor = HealthColors.Nutrition, text = { Text(stringResource(R.string.ingredients)) })
            }

            // Ingredient search depends on the bundled food database, so its
            // first-run state belongs on the Ingredients tab.
            if (selectedTab == 1) {
                FoodDatabaseCard(viewModel, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }

            if (isListEmpty) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = {
                        if (selectedTab == 0) {
                            backStack.add(Route.RecipeEditor())
                        } else {
                            showIngredientSearch = true
                        }
                    }) {
                        IconAdd()
                        Spacer(Modifier.width(8.dp))
                        Text(if (selectedTab == 0) stringResource(R.string.create_your_first_recipe) else stringResource(R.string.add_your_first_ingredient))
                    }
                }
            } else {
                if (selectedTab == 0) {
                    RecipesList(recipes, viewModel) { recipeId -> backStack.add(Route.RecipeEditor(recipeId)) }
                } else {
                    IngredientsList(ingredients, viewModel)
                }
            }
        }
    }
}

@Composable
private fun RecipesList(recipes: List<Recipe>, viewModel: HealthViewModel, onRecipeClick: (String) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(recipes, key = { it.id }) { recipe ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onRecipeClick(recipe.id) }
            ) {
                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(recipe.name, style = MaterialTheme.typography.titleMedium, color = HealthColors.Nutrition)
                    IconButton(onClick = { viewModel.deleteRecipe(recipe) }) {
                        IconDelete()
                    }
                }
            }
        }
    }
}

@Composable
private fun IngredientsList(ingredients: List<Ingredient>, viewModel: HealthViewModel) {
    var editingIngredient by remember { mutableStateOf<Ingredient?>(null) }
    
    if (editingIngredient != null) {
        var customName by remember { mutableStateOf(editingIngredient!!.customName ?: "") }
        AlertDialog(
            onDismissRequest = { editingIngredient = null },
            title = { Text(stringResource(R.string.rename_ingredient)) },
            text = {
                Column {
                    Text(stringResource(R.string.original_1, editingIngredient!!.originalName), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text(stringResource(R.string.custom_name)) }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val newIng = editingIngredient!!.copy(customName = customName.ifBlank { null })
                    viewModel.updateIngredient(newIng)
                    editingIngredient = null
                }) { Text(stringResource(UiR.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { editingIngredient = null }) { Text(stringResource(UiR.string.cancel)) }
            }
        )
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ingredients, key = { it.id }) { ingredient ->
            Card(modifier = Modifier.fillMaxWidth().clickable { editingIngredient = ingredient }) {
                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(ingredient.displayName, style = MaterialTheme.typography.titleMedium)
                        if (ingredient.customName != null) {
                            Text(stringResource(R.string.original_1, ingredient.originalName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            viewModel.updateIngredient(ingredient.copy(isRecipe = !ingredient.isRecipe))
                        }) {
                            if (ingredient.isRecipe) IconFire() else IconFire(tint = MaterialTheme.colorScheme.outline)
                        }
                        IconButton(onClick = { viewModel.deleteIngredient(ingredient) }) {
                            IconDelete()
                        }
                    }
                }
            }
        }
    }
}
