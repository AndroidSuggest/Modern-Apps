package com.vayunmathur.calculator.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.calculator.util.CalculatorActions
import com.vayunmathur.calculator.util.CalculatorUiState
import com.vayunmathur.calculator.util.CalculatorViewModel
import com.vayunmathur.library.ui.AppBarAlignment
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconRuler
import com.vayunmathur.library.ui.VerticalDivider
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.isExpandedWidth

/** Binds [CalculatorViewModel] to the stateless [CalculatorScreen]. */
@Composable
fun CalculatorPage(viewModel: CalculatorViewModel) {
    CalculatorScreen(state = viewModel.calculatorUiState, actions = viewModel)
}

/**
 * The keypad screen, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@Composable
fun CalculatorScreen(
    state: CalculatorUiState,
    actions: CalculatorActions,
    /**
     * Seeds for the screen's own UI-only state (which dialog is open, whether the "2nd"
     * modifier is latched). The app always takes the defaults; previews set them so a
     * given screen can be captured without driving the UI to get there.
     */
    initialShowHistory: Boolean = false,
    initialSecond: Boolean = false,
    initialShowUnitPicker: Boolean = false,
) {
    var showHistory by remember { mutableStateOf(initialShowHistory) }
    var second by remember { mutableStateOf(initialSecond) }
    var showUnitPicker by remember { mutableStateOf(initialShowUnitPicker) }
    var picker by remember { mutableStateOf(CalculatorPicker.None) }
    // The date chosen in the first step of the combined date-and-time flow (UTC midnight millis).
    var dtDateMillis by remember { mutableStateOf<Long?>(null) }

    val rows: List<List<Key>> = remember(state.angleMode) { buildCalculatorRows(state.angleMode) }

    AppScaffold(
        title = {},
        alignment = AppBarAlignment.Center,
        actions = {
            IconButton({ showUnitPicker = true }) { IconRuler() }
            IconButton({ showHistory = true }) { IconHistory() }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        // Sibling tools, never list-detail. On expanded windows the display sits
        // beside the keypad (fractional split, keypad capped so keys stay usable)
        // instead of stretching full-width.
        if (isExpandedWidth()) {
            Row(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                DisplayColumn(state, actions, Modifier.weight(1f).fillMaxSize())
                VerticalDivider()
                KeypadColumn(rows, actions, second, { second = !second }, picker, { picker = it }, Modifier.weight(1f).widthIn(max = 560.dp).fillMaxSize())
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                DisplayColumn(state, actions, Modifier.fillMaxWidth().weight(1f))
                HorizontalDivider()
                KeypadColumn(rows, actions, second, { second = !second }, picker, { picker = it }, Modifier.fillMaxWidth())
            }
        }
    }

    if (showHistory) HistoryDialog(state.history, actions) { showHistory = false }
    if (showUnitPicker) UnitPickerSheet(state, actions) { showUnitPicker = false }

    when (picker) {
        CalculatorPicker.Date -> DatePickerModal(onDismiss = { picker = CalculatorPicker.None }) { millis ->
            actions.insertInstant(localMidnightSeconds(millis))
            picker = CalculatorPicker.None
        }
        CalculatorPicker.DtDate -> DatePickerModal(onDismiss = { picker = CalculatorPicker.None }) { millis ->
            dtDateMillis = millis
            picker = CalculatorPicker.DtTime
        }
        CalculatorPicker.Time -> TimePickerModal(onDismiss = { picker = CalculatorPicker.None }) { hour, minute ->
            actions.insertDuration((hour * 3600 + minute * 60).toLong())
            picker = CalculatorPicker.None
        }
        CalculatorPicker.DtTime -> TimePickerModal(onDismiss = { picker = CalculatorPicker.None }) { hour, minute ->
            dtDateMillis?.let { actions.insertInstant(combineDateTimeSeconds(it, hour, minute)) }
            picker = CalculatorPicker.None
        }
        CalculatorPicker.None -> {}
    }
}
