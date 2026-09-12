package com.vayunmathur.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.data.Calendar
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.sharedText
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlin.time.Clock
import kotlin.time.Instant

@Composable
fun AgendaView(
    context: android.content.Context,
    events: List<Event>,
    calendars: Map<Long, Calendar>,
    calendarVisibility: Map<Long, Boolean>,
    anchorDate: LocalDate,
    dateViewing: LocalDate,
    loadInstances: suspend (Instant, Instant) -> List<Instance>,
    onEventClick: (Instance) -> Unit,
    onDateViewingChanged: (LocalDate) -> Unit
) {
    val initialIndex = 50000
    val listState = rememberLazyListState(initialIndex)
    val vEventsByID = remember(events) { events.associateBy { it.id!! } }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            val date = anchorDate.plus(DatePeriod(days = index - initialIndex))
            if (date != dateViewing) {
                onDateViewingChanged(date)
            }
        }
    }

    LaunchedEffect(dateViewing) {
        if (!listState.isScrollInProgress) {
            val targetIndex = initialIndex + (dateViewing.toEpochDays() - anchorDate.toEpochDays()).toInt()
            if (listState.firstVisibleItemIndex != targetIndex) {
                listState.scrollToItem(targetIndex)
            }
        }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(100000) { index ->
            val date = anchorDate.plus(DatePeriod(days = index - initialIndex))
            val dayInstances by produceState(emptyList<Instance>(), date, events, calendarVisibility) {
                value = loadInstances(
                    date.atStartOfDayIn(TimeZone.currentSystemDefault()),
                    date.plus(DatePeriod(days = 1)).atStartOfDayIn(TimeZone.currentSystemDefault())
                ).sortedBy { it.startDateTime }
            }

            val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
            val isToday = date == today

            Column(Modifier.fillMaxWidth().then(if (isToday) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)) else Modifier)) {
                Text(
                    text = DateString.dateWeekday(date),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                dayInstances.filter { date in it.spanDays }.forEach { instance ->
                    val ev = vEventsByID[instance.eventID]!!
                    val titleKey = eventTitleMorphKey(instance, date)
                    ListItem(
                        content = {
                            Text(
                                ev.title.ifEmpty { context.getString(R.string.no_title) },
                                modifier = if (titleKey == null) Modifier else Modifier.sharedText(titleKey)
                            )
                        },
                        supportingContent = {
                            Text(dateRangeString(context, instance.startDateTimeDisplay.date, instance.endDateTimeDisplay.date, instance.startDateTimeDisplay.time, instance.endDateTimeDisplay.time, instance.allDay, includeDate = false))
                        },
                        leadingContent = {
                            Box(Modifier.size(16.dp).background(Color(ev.color ?: calendars[ev.calendarID]!!.color), CircleShape))
                        },
                        modifier = Modifier.clickable { onEventClick(instance) }
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}
