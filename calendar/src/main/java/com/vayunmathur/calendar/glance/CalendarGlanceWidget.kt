package com.vayunmathur.calendar.glance

import com.vayunmathur.library.util.DateNameStyle
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.action.toParametersKey
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.components.TitleBar
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDefaults.defaultTextStyle
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.calendar.MainActivity
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.ui.atEndOfDayIn
import com.vayunmathur.calendar.ui.computePositionedEventsForDay
import com.vayunmathur.calendar.ui.dateFormat
import com.vayunmathur.calendar.ui.dateRangeString
import com.vayunmathur.library.widgets.DynamicThemeGlance
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.format
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import com.vayunmathur.library.util.localizedDayOfWeekNames
import com.vayunmathur.library.util.localizedMonthNames
import kotlinx.serialization.json.Json
import androidx.glance.LocalContext
import kotlin.time.Clock

class CalendarGlanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val today = now.date

        val nextMonth = today + DatePeriod(months = 1)
        val days = today..<nextMonth

        val instances = Instance.getInstances(context, today.atStartOfDayIn(TimeZone.currentSystemDefault()), nextMonth.atEndOfDayIn(
            TimeZone.currentSystemDefault()))
        val (allDay, notAllDay) = instances.partition { it.allDay }
        val notAllDayById = notAllDay.associateBy { it.id }

        val positionedEvents = days.associateWith { day ->
            computePositionedEventsForDay(
                notAllDay.filter { day in it.spanDays },
                day
            ).mapNotNull { posEvt -> notAllDayById[posEvt.instanceID] } + allDay.filter { day in it.spanDays }
        }

        provideContent {
            DynamicThemeGlance(context) {
                Content(context, positionedEvents)
            }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        try {
            provideContent {
                DynamicThemeGlance(context) {
                    CalendarPreviewContent()
                }
            }
        } catch (e: Throwable) {
            Log.e("CalendarWidget", "providePreview failed", e)
            try {
                provideContent {
                    DynamicThemeGlance(context) {
                        androidx.glance.layout.Box(
                            modifier = GlanceModifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Calendar",
                                style = defaultTextStyle.copy(color = GlanceTheme.colors.onBackground)
                            )
                        }
                    }
                }
            } catch (_: Throwable) {
                // last resort: avoid crashing the preview host
            }
        }
    }
}

@SuppressLint("RestrictedApi")
@Composable
private fun CalendarPreviewContent() {
    val dateFormatS = LocalDate.Format {
        dayOfWeek(DayOfWeekNames(localizedDayOfWeekNames(DateNameStyle.SHORT)))
        chars(", ")
        monthName(MonthNames(localizedMonthNames(DateNameStyle.SHORT)))
        chars(" ")
        day(Padding.NONE)
    }
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    val context = LocalContext.current

    Scaffold(titleBar = {
        TitleBar(ImageProvider(R.drawable.calendar_today_24px), today.format(dateFormatS))
    }) {
        Column(GlanceModifier.fillMaxSize()) {
            CalendarPreviewEvent(
                context.getString(R.string.widget_preview_event_1_title),
                context.getString(R.string.widget_preview_event_1_time),
                0xFF4285F4.toInt()
            )
            CalendarPreviewEvent(
                context.getString(R.string.widget_preview_event_2_title),
                context.getString(R.string.widget_preview_event_2_time),
                0xFF34A853.toInt()
            )
            CalendarPreviewEvent(
                context.getString(R.string.widget_preview_event_3_title),
                context.getString(R.string.widget_preview_event_3_time),
                0xFFEA4335.toInt()
            )
        }
    }
}

@SuppressLint("RestrictedApi")
@Composable
private fun CalendarPreviewEvent(title: String, time: String, color: Int) {
    Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(GlanceModifier.background(GlanceTheme.colors.primaryContainer).cornerRadius(6.dp)) {
            Box(GlanceModifier.background(ColorProvider(Color(color))).width(8.dp).fillMaxHeight()) {}
            Column(GlanceModifier.padding(4.dp).fillMaxWidth()) {
                Text(title, style = TextStyle(fontWeight = FontWeight.Bold))
                Text(time, style = defaultTextStyle.copy(color = GlanceTheme.colors.onPrimaryContainer))
            }
        }
    }
}
@SuppressLint("RestrictedApi")
@Composable
fun Content(context: Context, positionedEvents: Map<LocalDate, List<Instance>>) {
    val dateFormatS = LocalDate.Format {
        dayOfWeek(DayOfWeekNames(localizedDayOfWeekNames(DateNameStyle.SHORT)))
        chars(", ")
        monthName(MonthNames(localizedMonthNames(DateNameStyle.SHORT)))
        chars(" ")
        day(Padding.NONE)
    }

    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val today = now.date

    val nextMonth = today + DatePeriod(months = 1)
    val days = today..<nextMonth

    Scaffold(titleBar = {
        TitleBar(ImageProvider(R.drawable.calendar_today_24px), today.format(dateFormatS), modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()))
    }) {
        LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
            for(day in days) {
                if(positionedEvents[day]!!.isNotEmpty()) {
                    item {
                        Text(day.format(dateFormat), GlanceModifier.padding(vertical = 6.dp), style = defaultTextStyle.copy(color = GlanceTheme.colors.onSurface))
                    }
                }
                items(positionedEvents[day]!!) { instance ->
                    Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(GlanceModifier.background(GlanceTheme.colors.primaryContainer).cornerRadius(6.dp).clickable (actionStartActivity<MainActivity>(actionParametersOf(
                            stringPreferencesKey("instance").toParametersKey() to Json.encodeToString(instance))))) {
                            Box(GlanceModifier.background(ColorProvider(Color(instance.color))).width(8.dp).fillMaxHeight()) {}
                            Column(GlanceModifier.padding(4.dp).fillMaxWidth()) {
                                Text(
                                    instance.eventTitle,
                                    style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onPrimaryContainer)
                                )
                                Text(
                                    dateRangeString(
                                        context,
                                        instance.startDateTime.date,
                                        instance.endDateTime.date,
                                        instance.startDateTime.time,
                                        instance.endDateTime.time,
                                        instance.allDay,
                                        includeDate = false
                                    ),
                                    style = defaultTextStyle.copy(color = GlanceTheme.colors.onPrimaryContainer)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


