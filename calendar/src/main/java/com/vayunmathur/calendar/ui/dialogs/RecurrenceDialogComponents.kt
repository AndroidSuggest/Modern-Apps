package com.vayunmathur.calendar.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.calendar.R
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import kotlinx.datetime.LocalDate
import com.vayunmathur.library.ui.R as UiR

/**
 * The picked dates, newest edit last. The event's own start date is always the first occurrence and
 * cannot be removed here — moving it means changing the event's date.
 */
@Composable
internal fun ChosenDates(
    startDate: LocalDate,
    dates: List<LocalDate>,
    onRemove: (LocalDate) -> Unit,
    onAdd: () -> Unit,
) {
    Text(stringResource(R.string.repeat_on_dates))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.repeat_date_first, DateString.dateWeekday(startDate)),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        dates.forEach { date ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(DateString.dateWeekday(date), Modifier.weight(1f))
                Text(
                    stringResource(UiR.string.remove),
                    Modifier.clickable { onRemove(date) },
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    Button(onClick = onAdd) { Text(stringResource(R.string.repeat_add_date)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OnDropdown(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = options.getOrElse(selected) { options.firstOrNull() ?: "" },
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            trailingIcon = { Text("▼") },
            interactionSource = remember { MutableInteractionSource() }.also { src ->
                LaunchedEffect(src) {
                    src.interactions.collect { if (it is PressInteraction.Release) open = true }
                }
            }
        )
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, opt ->
                DropdownMenuItem({ Text(opt) }, onClick = {
                    onSelect(i)
                    open = false
                })
            }
        }
    }
}
