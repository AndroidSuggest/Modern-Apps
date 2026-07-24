package com.vayunmathur.calendar.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.Route
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.calendar.util.CalendarViewModel
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconDescription
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconGlobe
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.NavBackStack
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventScreen(viewModel: CalendarViewModel, instance: Instance, backStack: NavBackStack<Route>) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()

    val event = events.find { it.id == instance.eventID }
    if (event == null) {
        // simple empty state
        Text(stringResource(R.string.event_not_found))
        return
    }

    val calendar = calendars.find { it.id == event.calendarID }!!

    val context = LocalContext.current

    val isEditable = calendar.canModify
    var showDeleteMenu by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar({}, navigationIcon = {
            IconNavigation(backStack)
        }, actions = {
            if(isEditable) {
                IconButton({
                    backStack.add(Route.EditEvent(event.id))
                }) {
                    IconEdit()
                }
                Box {
                    IconButton({
                        if (instance.rrule != null) {
                            // Recurring event - show dropdown menu
                            showDeleteMenu = true
                        } else {
                            // Non-recurring event - delete directly
                            viewModel.deleteEventSeries(event.id!!)
                            backStack.pop()
                        }
                    }) {
                        IconDelete()
                    }
                    DropdownMenu(
                        expanded = showDeleteMenu,
                        onDismissRequest = { showDeleteMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete_this_event)) },
                            onClick = {
                                showDeleteMenu = false
                                viewModel.deleteEventInstance(event.id!!, instance.begin)
                                backStack.pop()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete_all_events)) },
                            onClick = {
                                showDeleteMenu = false
                                viewModel.deleteEventSeries(event.id!!)
                                backStack.pop()
                            }
                        )
                    }
                }
            }
        })
    }) { paddingValues ->
        Column(Modifier.padding(paddingValues)) {
            ListItem({
                Text(event.title, style = MaterialTheme.typography.titleLarge)
            }, supportingContent = {
                Column {
                    Text(calendar.displayName)
                    Text(dateRangeString(context,instance.startDateTimeDisplay.date, instance.endDateTimeDisplay.date, instance.startDateTimeDisplay.time, instance.endDateTimeDisplay.time, instance.allDay))
                    instance.rrule?.let { Text(it.describe(context)) }
                }
            }, leadingContent = {
                Box(Modifier.size(24.dp).background(Color(calendar.color), RoundedCornerShape(4.dp)))
            })
            if(event.description.isNotBlank()) ListItem({
                Text(com.vayunmathur.library.util.parseMarkdown(event.description, showMarkers = false))
            }, leadingContent = {
                IconDescription()
            })
            if(event.location.isNotBlank()) ListItem(
                { Text(event.location) },
                Modifier.clickable {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        // "geo:0,0?q=<text>" lets any installed maps/navigation
                        // app (Google Maps, Waze, our own maps app, etc.)
                        // resolve the address. Wrap with chooser so user can
                        // pick if multiple are installed.
                        "geo:0,0?q=${Uri.encode(event.location)}".toUri()
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    val chooser = Intent.createChooser(
                        intent,
                        context.getString(R.string.open_location_in_navigation)
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    try {
                        context.startActivity(chooser)
                    } catch (_: ActivityNotFoundException) {
                        // No nav app installed — silently drop; the text is
                        // still selectable elsewhere.
                    }
                },
                leadingContent = { IconGlobe() },
            )
        }
    }
}

fun dateRangeString(context: Context, startDate: LocalDate, endDate: LocalDate, startTime: LocalTime, endTime: LocalTime, allDay: Boolean, includeDate: Boolean = true): String {
    return if(allDay) {
        if(startDate.toEpochDays() + 1 == endDate.toEpochDays()) {
            if (includeDate) startDate.format(dateFormat) else context.getString(R.string.all_day)
        } else {
            context.getString(R.string.date_range_format, startDate.format(dateFormat), endDate.format(dateFormat))
        }
    } else {
        val timeFmt = if(DateFormat.is24HourFormat(context)) timeFormat24 else timeFormat12
        if(startDate == endDate) {
            if (includeDate) {
                context.getString(R.string.date_time_range_format, startDate.format(dateFormat), startTime.format(timeFmt), endTime.format(timeFmt))
            } else {
                context.getString(R.string.date_range_format, startTime.format(timeFmt), endTime.format(timeFmt))
            }
        } else {
            context.getString(R.string.full_date_time_range_format, startDate.format(dateFormat), startTime.format(timeFmt), endDate.format(dateFormat), endTime.format(timeFmt))
        }
    }
}
