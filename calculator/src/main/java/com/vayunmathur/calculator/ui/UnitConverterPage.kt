package com.vayunmathur.calculator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.vayunmathur.calculator.R
import com.vayunmathur.calculator.util.CalculatorViewModel
import com.vayunmathur.calculator.util.UnitConverterActions
import com.vayunmathur.calculator.util.UnitConverterUiState
import com.vayunmathur.calculator.util.UnitDef
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CenterAlignedTopAppBar
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.FilledTonalIconButton
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconSwapLanguages
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.PrimaryScrollableTabRow
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Tab
import com.vayunmathur.library.ui.Text

/** Binds [CalculatorViewModel] to the stateless [UnitConverterScreen]. */
@Composable
fun UnitConverterPage(viewModel: CalculatorViewModel) {
    UnitConverterScreen(state = viewModel.unitConverterUiState, actions = viewModel)
}

/**
 * The units tab: a plain from/to converter with no equations, one category per tab. Stateless
 * so it can be rendered from a `@Preview` — see `src/screenshotTest`.
 */
@Composable
fun UnitConverterScreen(state: UnitConverterUiState, actions: UnitConverterActions) {
    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text(stringResource(R.string.units)) }) },
    ) { padding ->
        val category = state.categories.getOrNull(state.selectedCategoryIndex)
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryScrollableTabRow(selectedTabIndex = state.selectedCategoryIndex) {
                state.categories.forEachIndexed { index, cat ->
                    Tab(
                        selected = index == state.selectedCategoryIndex,
                        onClick = { actions.selectCategory(index) },
                        text = { Text(cat.name) },
                    )
                }
            }
            if (category != null) {
                Column(
                    Modifier.fillMaxWidth().padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    OutlinedTextField(
                        value = state.inputText,
                        onValueChange = actions::setConverterInput,
                        label = { Text(stringResource(R.string.converter_value)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        LabeledUnitDropdown(
                            label = stringResource(R.string.converter_from),
                            units = category.units,
                            selectedToken = state.fromToken,
                            onSelect = actions::setFrom,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalIconButton(
                            onClick = actions::swapUnits,
                            modifier = Modifier.padding(horizontal = Spacing.sm),
                        ) { IconSwapLanguages() }
                        LabeledUnitDropdown(
                            label = stringResource(R.string.converter_to),
                            units = category.units,
                            selectedToken = state.toToken,
                            onSelect = actions::setTo,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    val toSymbol = category.units.firstOrNull { it.token == state.toToken }?.symbol.orEmpty()
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.lg)) {
                            Text(
                                stringResource(R.string.converter_result),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp,
                            )
                            Text(
                                if (state.outputText.isEmpty()) "—" else "${state.outputText}\u202F$toSymbol",
                                fontSize = 28.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledUnitDropdown(
    label: String,
    units: List<UnitDef>,
    selectedToken: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        UnitDropdown(units, selectedToken, onSelect, Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

/**
 * A unit selector built from [OutlinedButton] + [DropdownMenu] rather than the library's
 * `ExposedDropdownMenu` wrapper, which currently recurses into itself.
 */
@Composable
private fun UnitDropdown(
    units: List<UnitDef>,
    selectedToken: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = units.firstOrNull { it.token == selectedToken }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                selected?.symbol.orEmpty(),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
                maxLines = 1,
            )
            IconArrowDropDown()
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            units.forEach { unit ->
                DropdownMenuItem(
                    text = { Text("${unit.symbol} — ${unit.name}") },
                    onClick = {
                        onSelect(unit.token)
                        expanded = false
                    },
                )
            }
        }
    }
}
