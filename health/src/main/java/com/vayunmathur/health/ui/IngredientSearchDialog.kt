package com.vayunmathur.health.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.data.*
import com.vayunmathur.health.util.HealthViewModel
import kotlinx.coroutines.launch

@Composable
fun IngredientSearchDialog(
    viewModel: HealthViewModel,
    includeLocal: Boolean = false,
    onDismiss: () -> Unit,
    onIngredientSelected: (Ingredient) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var remoteResults by remember { mutableStateOf(listOf<com.vayunmathur.health.util.FoodSearchAPI.SearchResult>()) }
    var localResults by remember { mutableStateOf(listOf<Ingredient>()) }
    var isSearching by remember { mutableStateOf(false) }
    var isFetchingData by remember { mutableStateOf(false) }
    val foodDbStatus by viewModel.foodDatabaseStatus.collectAsState()
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.search_ingredient)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().height(400.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.search)) },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        isSearching = true
                        scope.launch {
                            val results = viewModel.searchIngredients(query, includeLocal)
                            remoteResults = results.remote
                            localResults = results.local
                            isSearching = false
                        }
                    }) {
                        Text(stringResource(R.string.search))
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (isSearching || isFetchingData) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        // Until the database is unpacked there is nothing to
                        // search but the user's own ingredients; say so here.
                        if (foodDbStatus.installed == null) {
                            item {
                                FoodDatabaseCard(viewModel, modifier = Modifier.padding(vertical = 8.dp))
                            }
                        }

                        // Show Local results first if included
                        if (includeLocal && localResults.isNotEmpty()) {
                            item {
                                Text(stringResource(R.string.saved_locally), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(8.dp))
                            }
                            items(localResults, key = { "local-${it.id}" }) { ingredient ->
                                ListItem(
                                    content = { Text(ingredient.displayName) },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    modifier = Modifier.clickable { onIngredientSelected(ingredient) }
                                )
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }

                        // Show matches from the downloaded food database
                        if (remoteResults.isNotEmpty()) {
                            item {
                                Text(stringResource(R.string.food_database), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(8.dp))
                            }
                            items(remoteResults, key = { "remote-${it.id}" }) { result ->
                                // Skip if already in local results to avoid duplicates
                                if (includeLocal && localResults.any { it.id == result.id.toString() }) return@items

                                ListItem(
                                    content = { Text(result.displayName) },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    modifier = Modifier.clickable {
                                        isFetchingData = true
                                        scope.launch {
                                            val ingredient = com.vayunmathur.health.util.FoodSearchAPI.getIngredientData(result.id, result.displayName)
                                            isFetchingData = false
                                            if (ingredient != null) {
                                                onIngredientSelected(ingredient)
                                            }
                                        }
                                    }
                                )
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }

                        // The card above already explains an empty result set
                        // when the database isn't installed.
                        if (!isSearching && !isFetchingData && remoteResults.isEmpty() && localResults.isEmpty() &&
                            query.isNotBlank() && foodDbStatus.installed != null
                        ) {
                            item {
                                EmptyState(
                                    title = stringResource(R.string.no_results_found),
                                    icon = { IconSearch() },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }

                // ODbL requires attribution wherever this data is shown.
                Text(
                    stringResource(R.string.food_database_attribution),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) }
        }
    )
}
