package com.vayunmathur.calendar.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.calendar.R
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.IconSchedule
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.R as UiR

@Composable
internal fun EditEventReminders(
    reminders: List<Int>,
    onReminders: (List<Int>) -> Unit,
) {
    val context = LocalContext.current
    // Reminders
    reminders.forEach { minutes ->
        Item(
            { IconSchedule() },
            { Text(reminderLabel(context, minutes)) },
            { Text(stringResource(UiR.string.remove), Modifier.clickable { onReminders(reminders - minutes) }) },
        )
    }
    var addReminderExpanded by remember { mutableStateOf(false) }
    val available = REMINDER_PRESETS.filter { it !in reminders }
    if (available.isNotEmpty()) {
        Item(
            { IconSchedule() },
            {
                Box {
                    Text(stringResource(R.string.add_reminder), Modifier.clickable { addReminderExpanded = true })
                    DropdownMenu(addReminderExpanded, { addReminderExpanded = false }) {
                        available.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(reminderLabel(context, m)) },
                                onClick = {
                                    addReminderExpanded = false
                                    onReminders((reminders + m).sorted())
                                },
                            )
                        }
                    }
                }
            },
        )
    }
}
