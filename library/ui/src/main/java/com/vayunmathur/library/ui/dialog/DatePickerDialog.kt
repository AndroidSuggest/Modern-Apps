package com.vayunmathur.library.ui.dialog

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.LocalNavResultRegistry
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T: NavKey> DatePickerDialog(backStack: NavBackStack<T>, resultKey: String, initialDate: LocalDate, minDate: LocalDate? = null, maxDate: LocalDate? = null) {
    val registry = LocalNavResultRegistry.current
    val state = rememberDatePickerState(initialDate.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
        selectableDates = object: SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val date = Instant.fromEpochMilliseconds(utcTimeMillis).toLocalDateTime(TimeZone.UTC).date
                if(minDate != null && date < minDate) return false
                if(maxDate != null && date > maxDate) return false
                return true
            }

            override fun isSelectableYear(year: Int): Boolean {
                if(minDate != null && year < minDate.year) return false
                if(maxDate != null && year > maxDate.year) return false
                return true
            }
        })
    val scope = rememberCoroutineScope()
    DatePickerDialog(
        onDismissRequest = { backStack.pop() },
        confirmButton = {
            TextButton(onClick = {
                val selectedMs = state.selectedDateMillis!!
                val result = Instant.fromEpochMilliseconds(selectedMs)
                    .toLocalDateTime(TimeZone.UTC).date
                scope.launch { registry.dispatchResult(resultKey, result) }
                backStack.pop()
            }, enabled = state.selectedDateMillis != null) {
                Text(stringResource(R.string.dialog_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = { backStack.pop() }) { Text(stringResource(R.string.link_action_cancel)) }
        }
    ) {
        DatePicker(state)
    }
}
