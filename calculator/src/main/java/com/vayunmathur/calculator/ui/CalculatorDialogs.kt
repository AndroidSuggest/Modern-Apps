package com.vayunmathur.calculator.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.calculator.R
import com.vayunmathur.calculator.util.CalculatorActions
import com.vayunmathur.calculator.util.CalculatorUiState
import com.vayunmathur.calculator.util.HistoryEntry
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AssistChip
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ModalBottomSheet
import com.vayunmathur.library.ui.PrimaryScrollableTabRow
import com.vayunmathur.library.ui.Tab
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatePickerModal(onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    val dateState = rememberDatePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { dateState.selectedDateMillis?.let(onConfirm) ?: onDismiss() }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(UiR.string.cancel)) } },
    ) {
        DatePicker(state = dateState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimePickerModal(onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    val timeState = rememberTimePickerState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_time)) },
        text = { TimePicker(state = timeState) },
        confirmButton = {
            TextButton(onClick = { onConfirm(timeState.hour, timeState.minute) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(UiR.string.cancel)) } },
    )
}

/** The system date picker returns UTC midnight of the chosen day; reinterpret that calendar day
 * at local midnight so the stored instant reads back as that date. */
internal fun localMidnightSeconds(utcDateMillis: Long): Long =
    Instant.ofEpochMilli(utcDateMillis).atZone(ZoneOffset.UTC).toLocalDate()
        .atStartOfDay(ZoneId.systemDefault()).toEpochSecond()

/** Combine a picked calendar day (UTC midnight millis) with a local time-of-day into an instant. */
internal fun combineDateTimeSeconds(utcDateMillis: Long, hour: Int, minute: Int): Long =
    Instant.ofEpochMilli(utcDateMillis).atZone(ZoneOffset.UTC).toLocalDate()
        .atTime(hour, minute).atZone(ZoneId.systemDefault()).toEpochSecond()

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UnitPickerSheet(
    state: CalculatorUiState,
    actions: CalculatorActions,
    onDismiss: () -> Unit,
) {
    val categories = state.unitCategories.filter { it.inEquations }
    var categoryIndex by remember { mutableStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.insert_unit),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            fontSize = 20.sp,
        )
        if (categories.isNotEmpty()) {
            PrimaryScrollableTabRow(selectedTabIndex = categoryIndex) {
                categories.forEachIndexed { index, category ->
                    Tab(
                        selected = index == categoryIndex,
                        onClick = { categoryIndex = index },
                        text = { Text(category.name) },
                    )
                }
            }
            categories.getOrNull(categoryIndex)?.let { category ->
                FlowRow(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    category.units.forEach { unit ->
                        AssistChip(
                            onClick = { actions.append(unit.token); onDismiss() },
                            label = { Text(unit.symbol) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun HistoryDialog(
    history: List<HistoryEntry>,
    actions: CalculatorActions,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history)) },
        text = {
            if (history.isEmpty()) {
                Text(stringResource(R.string.no_calculations_yet))
            } else {
                LazyColumn(Modifier.height(320.dp)) {
                    items(history) { entry ->
                        Card(
                            onClick = { actions.useHistory(entry); onDismiss() },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    entry.expression,
                                    color = com.vayunmathur.library.ui.MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 14.sp,
                                )
                                Text("= ${entry.result}", fontSize = 20.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(UiR.string.close)) } },
        dismissButton = {
            if (history.isNotEmpty()) {
                TextButton({ actions.clearHistory(); onDismiss() }) { Text(stringResource(UiR.string.clear)) }
            }
        },
    )
}
