package com.vayunmathur.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vayunmathur.calendar.data.Calendar
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.VerticalDivider
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

@Composable
fun SummaryGrid(
    context: android.content.Context,
    instances: List<Instance>,
    vEventsByID: Map<Long, Event>,
    calendars: Map<Long, Calendar>,
    weekDays: List<LocalDate>,
    onEventClick: (Instance) -> Unit
) {
    Row(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 8.dp)
    ) {
        val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
        weekDays.forEachIndexed { index, day ->
            val dayInstances = instances.filter { day in it.spanDays }.sortedBy { it.startDateTime }
            val isToday = day == today
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (isToday) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent)
                    .padding(2.dp)
            ) {
                dayInstances.forEach { instance ->
                    val ev = vEventsByID[instance.eventID]!!
                    SummaryEventItem(context, instance, ev, calendars, onEventClick, eventTitleMorphKey(instance, day))
                }
            }
            if (index < weekDays.size - 1) {
                VerticalDivider(modifier = Modifier.fillMaxHeight())
            }
        }
    }
}
