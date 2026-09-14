package com.vayunmathur.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.data.Calendar
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.contentColorOn
import com.vayunmathur.library.util.DateNameStyle
import com.vayunmathur.library.util.localizedDayOfWeekNames
import com.vayunmathur.library.util.sharedText
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.todayIn
import kotlin.time.Clock

@Composable
internal fun WeekHeader(weekDays: List<LocalDate>, leadingGutter: Boolean) {
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
    Row(modifier = Modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp)) {
        if (leadingGutter) Spacer(Modifier.width(HourGutterWidth))
        weekDays.forEach { d ->
            val isToday = d == today
            Column(
                Modifier
                    .weight(1f)
                    .then(
                        if (isToday) Modifier.background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
                        ) else Modifier
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    localizedDayOfWeekNames(DateNameStyle.SHORT)[d.dayOfWeek.isoDayNumber - 1],
                    Modifier,
                    if (isToday) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Text(
                    d.day.toString(),
                    fontWeight = FontWeight.Bold,
                    color = if (isToday) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
internal fun AllDayRow(
    allDayByDate: Map<LocalDate, List<Instance>>,
    events: Map<Long, Event>,
    calendars: Map<Long, Calendar>,
    weekDays: List<LocalDate>,
    onEventClick: (Instance) -> Unit
) {
    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp)) {
        Spacer(Modifier.width(HourGutterWidth))
        weekDays.forEach { d ->
            val instances = allDayByDate[d].orEmpty()
            Column(Modifier.weight(1f)) {
                if (instances.isEmpty()) {
                    Box(modifier = Modifier
                        .height(32.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {}
                } else {
                    Column {
                        instances.forEach { instance ->
                            val ev = events[instance.eventID]!!
                            val eventColor = Color(ev.color ?: calendars[ev.calendarID]!!.color)
                            val titleKey = eventTitleMorphKey(instance, d)
                            Box(
                                Modifier
                                    .padding(bottom = 4.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(eventColor)
                                    .height(28.dp)
                                    .clickable { onEventClick(instance) }
                                    .fillMaxWidth()
                            ) {
                                Text(
                                    ev.title.ifEmpty { stringResource(R.string.no_title) },
                                    Modifier
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                        .then(if (titleKey == null) Modifier else Modifier.sharedText(titleKey)),
                                    contentColorOn(eventColor),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
