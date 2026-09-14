package com.vayunmathur.calendar.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import android.text.format.DateFormat
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.data.Calendar
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.calendar.util.CalendarViewModel
import com.vayunmathur.library.ui.contentColorOn
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.DateNameStyle
import com.vayunmathur.library.util.localizedDayOfWeekNames
import com.vayunmathur.library.util.localeFirstDayOfWeek
import com.vayunmathur.library.util.sharedText
import androidx.compose.foundation.clickable
import com.vayunmathur.library.ui.VerticalDivider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.Color
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Composable
fun CalendarPagerView(
    context: android.content.Context,
    currentLayout: CalendarViewModel.CalendarLayout,
    anchorDate: LocalDate,
    dateViewing: LocalDate,
    events: List<Event>,
    calendars: Map<Long, Calendar>,
    calendarVisibility: Map<Long, Boolean>,
    verticalState: ScrollState,
    loadInstances: suspend (Instant, Instant) -> List<Instance>,
    onEventClick: (Instance) -> Unit,
    onDateViewingChanged: (LocalDate) -> Unit
) {
    val daysToShow = when (currentLayout) {
        CalendarViewModel.CalendarLayout.Day -> 1
        CalendarViewModel.CalendarLayout.WorkWeek,
        CalendarViewModel.CalendarLayout.WorkWeekSummary,
        CalendarViewModel.CalendarLayout.WorkWeekCompact -> 5
        else -> 7
    }
    val isSummary = currentLayout == CalendarViewModel.CalendarLayout.WorkWeekSummary ||
                    currentLayout == CalendarViewModel.CalendarLayout.FullWeekSummary
    val isCompact = currentLayout == CalendarViewModel.CalendarLayout.WorkWeekCompact ||
                    currentLayout == CalendarViewModel.CalendarLayout.FullWeekCompact

    val pagerState = rememberPagerState(initialPage = 5000) { 10000 }
    // Track whether the pager is being programmatically scrolled to avoid feedback loops
    var programmaticScroll by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState.currentPage, currentLayout) {
        if (programmaticScroll) return@LaunchedEffect
        val delta = pagerState.currentPage - 5000
        val currentStart = if (daysToShow == 1) {
            anchorDate.plus(DatePeriod(days = delta))
        } else {
            anchorDate.plus(DatePeriod(days = delta * 7))
        }
        onDateViewingChanged(currentStart)
    }

    LaunchedEffect(dateViewing) {
        if (!pagerState.isScrollInProgress) {
            val delta = if (daysToShow == 1) {
                dateViewing.toEpochDays() - anchorDate.toEpochDays()
            } else {
                (dateViewing.toEpochDays() - anchorDate.toEpochDays()) / 7
            }
            val targetPage = 5000 + delta.toInt()
            if (pagerState.currentPage != targetPage) {
                programmaticScroll = true
                pagerState.scrollToPage(targetPage)
                programmaticScroll = false
            }
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = 1
    ) { page ->
        val delta = page - 5000
        val pageStartDate = if (daysToShow == 1) {
            anchorDate.plus(DatePeriod(days = delta))
        } else {
            anchorDate.plus(DatePeriod(days = delta * 7))
        }

        val startDay = when (currentLayout) {
            CalendarViewModel.CalendarLayout.Day -> pageStartDate
            CalendarViewModel.CalendarLayout.WorkWeek,
            CalendarViewModel.CalendarLayout.WorkWeekSummary,
            CalendarViewModel.CalendarLayout.WorkWeekCompact ->
                pageStartDate.minus(DatePeriod(days = (pageStartDate.dayOfWeek.isoDayNumber - 1) % 7))
            else -> {
                val locale = context.resources.configuration.locales[0]
                pageStartDate.minus(DatePeriod(days = firstDayOfWeekOffset(pageStartDate, locale)))
            }
        }

        val weekDays = (0 until daysToShow).map { startDay.plus(DatePeriod(days = it)) }
        val vEventsByID = remember(events) { events.associateBy { it.id!! } }

        val weekInstances by produceState(emptyList<Instance>(), events, calendarVisibility, startDay, daysToShow) {
            value = loadInstances(
                weekDays.first().atStartOfDayIn(TimeZone.currentSystemDefault()),
                weekDays.last().atEndOfDayIn(TimeZone.currentSystemDefault())
            )
        }

        Column(Modifier.fillMaxSize()) {
            WeekHeader(weekDays, leadingGutter = !isSummary)

            if (isSummary) {
                SummaryGrid(context, weekInstances, vEventsByID, calendars, weekDays, onEventClick)
            } else {
                val (allDay, notAllDay) = weekInstances.partition { it.allDay }
                val allDayByDate = weekDays.associateWith { d -> allDay.filter { d in it.spanDays } }
                val timedByDateHour = weekDays.associateWith { d ->
                    notAllDay.filter { d in it.spanDays }.groupBy { it.startDateTime.hour }
                }

                AllDayRow(allDayByDate, vEventsByID, calendars, weekDays, onEventClick)
                HourlyGrid(
                    context,
                    timedByDateHour,
                    weekDays,
                    verticalState,
                    shrinkEmptyHours = isCompact,
                    onEventClick = onEventClick,
                    innerPadding = PaddingValues(0.dp)
                )
            }
        }
    }
}

@Composable
internal fun HourlyGrid(
    context: android.content.Context,
    timedByDateHour: Map<LocalDate, Map<Int, List<Instance>>>,
    weekDays: List<LocalDate>,
    verticalState: ScrollState,
    shrinkEmptyHours: Boolean,
    onEventClick: (Instance) -> Unit,
    innerPadding: PaddingValues
) {
    val minEventHeight = 18.dp
    val minEventWidth = 56.dp

    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
    var now by remember { mutableStateOf(Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())) }

    LaunchedEffect(Unit) {
        while (true) {
            now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            kotlinx.coroutines.delay(60.seconds)
        }
    }

    val eventsByDay = weekDays.associateWith { d ->
        timedByDateHour[d]?.values?.flatten().orEmpty().distinctBy { it.id }
    }
    val positionedByDay = eventsByDay.mapValues { (d, events) -> computePositionedEventsForDay(events, d) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bottomPadding = innerPadding.calculateBottomPadding() + 4.dp
        // One scale for the whole page: the columns share the hour gutter, so a shrunk hour has to
        // be shrunk on every day at once.
        val scale = if (shrinkEmptyHours) {
            HourScale.collapsingEmptyHours(busyHours(positionedByDay.values.flatten()), maxHeight - bottomPadding)
        } else {
            HourScale.uniform()
        }

        Row(
            Modifier
                .fillMaxSize()
                .verticalScroll(verticalState)
                .padding(bottom = bottomPadding), Arrangement.spacedBy(4.dp)
        ) {
            HourLabelColumn(context, scale)

            // create 7 equal columns with weight so all 7 fit on screen
            for (d in weekDays) {
                val isToday = d == today

                Box(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(if (isToday) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))) {
                    // background hourly grid — fixed 24 rows with faint hour separators
                    Column {
                        for (hour in 0..23) {
                            Box(
                                Modifier
                                    .height(scale.heightOf(hour))
                                    .fillMaxWidth()
                            ) {
                                if (hour != 0) {
                                    HorizontalDivider(
                                        Modifier.align(Alignment.TopStart),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                    }

                    if (isToday) {
                        val yOffset = scale.offsetOf(now.hour * 60 + now.minute)
                        Box(
                            Modifier
                                .offset(y = yOffset - 1.dp)
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(Color.Red)
                                .zIndex(10f)
                        )
                    }

                    val positioned = positionedByDay.getValue(d)
                    val eventsForDay = eventsByDay.getValue(d)
                    val instancesById = remember(eventsForDay) { eventsForDay.associateBy { it.id } }

                    // overlay event segments positioned by their time within the day and column
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val columnWidth = this.maxWidth

                        positioned.forEach { ev ->
                            val instance = instancesById.getValue(ev.instanceID)
                            // compute vertical position and height
                            val yOffset = scale.offsetOf(ev.startMinutes)
                            var heightDp = scale.offsetOf(ev.endMinutes) - yOffset
                            if (heightDp < minEventHeight) heightDp = minEventHeight

                            // compute horizontal position and size
                            val widthFraction = 1f / ev.totalColumns.toFloat()
                            val xFraction = ev.columnIndex * widthFraction
                            val xOffsetDp = columnWidth * xFraction
                            val widthDp = (columnWidth * widthFraction).coerceAtLeast(minEventWidth)

                            Box(
                                Modifier
                                    .offset(xOffsetDp, yOffset)
                                    .size(widthDp, heightDp)
                                    .padding(2.dp)
                                    .zIndex(1f + ev.columnIndex * 0.01f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(ev.color))
                                    .clickable { onEventClick(instance) }
                            ) {
                                val titleKey = eventTitleMorphKey(instance, d)
                                Text(
                                    ev.title.ifEmpty { stringResource(R.string.no_title) },
                                    Modifier
                                        .padding(6.dp)
                                        .then(if (titleKey == null) Modifier else Modifier.sharedText(titleKey)),
                                    contentColorOn(Color(ev.color)),
                                    maxLines = 2,
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

/**
 * The hour gutter. It lives inside the grid's scroll container so it stays pinned to the rows
 * it labels even when [scale] gives them different heights.
 */
@Composable
internal fun HourLabelColumn(context: android.content.Context, scale: HourScale) {
    Column {
        for (hour in 0..23) {
            Box(
                Modifier
                    .height(scale.heightOf(hour))
                    .width(HourGutterWidth)
                    .clipToBounds()
            ) {
                val hourString = DateString.hourLabel(hour, DateFormat.is24HourFormat(context))
                Text(
                    text = hourString,
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
