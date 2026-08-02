package com.vayunmathur.calendar.ui.dialogs
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DatePicker
import com.vayunmathur.library.ui.DatePickerDialog
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Text
import androidx.compose.ui.res.stringResource
import com.vayunmathur.calendar.R
import com.vayunmathur.library.ui.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.calendar.Route
import com.vayunmathur.library.util.LocalNavResultRegistry
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarSetDateDialog(backStack: NavBackStack<Route>, dateViewingEpochDays: kotlinx.datetime.LocalDate) {
    val registry = LocalNavResultRegistry.current
    val state = rememberDatePickerState(dateViewingEpochDays.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds())
    val scope = rememberCoroutineScope()
    DatePickerDialog(
        onDismissRequest = { backStack.pop() },
        confirmButton = {
            Button(onClick = {
                val result = Instant.fromEpochMilliseconds(state.selectedDateMillis!!)
                    .toLocalDateTime(TimeZone.UTC).date
                scope.launch { registry.dispatchResult("GotoDate", result) }
                backStack.pop()
            }, enabled = state.selectedDateMillis != null) {
                Text(stringResource(R.string.go_to_date))
            }
        },
        dismissButton = {
            Button(onClick = { backStack.pop() }) { Text(stringResource(UiR.string.cancel)) }
        }
    ) {
        DatePicker(state)
    }
}
