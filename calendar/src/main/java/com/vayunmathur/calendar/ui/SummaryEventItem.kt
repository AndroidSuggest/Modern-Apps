package com.vayunmathur.calendar.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.data.Calendar
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.contentColorOn
import com.vayunmathur.library.util.sharedText
import androidx.compose.foundation.layout.fillMaxWidth

@Composable
fun SummaryEventItem(
    context: android.content.Context,
    instance: Instance,
    ev: Event,
    calendars: Map<Long, Calendar>,
    onEventClick: (Instance) -> Unit,
    titleSharedKey: Any? = null
) {
    val eventColor = Color(ev.color ?: calendars[ev.calendarID]!!.color)
    val onEventColor = contentColorOn(eventColor)
    Box(
        Modifier
            .padding(bottom = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(eventColor)
            .fillMaxWidth()
            .clickable { onEventClick(instance) }
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Column {
            Text(
                ev.title.ifEmpty { context.getString(R.string.no_title) },
                color = onEventColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 12.sp,
                modifier = if (titleSharedKey == null) Modifier else Modifier.sharedText(titleSharedKey)
            )
            if (!instance.allDay) {
                val is24 = DateFormat.is24HourFormat(context)
                Text(
                    "${DateString.time(instance.startDateTime.time, is24)} - ${DateString.time(instance.endDateTime.time, is24)}",
                    color = onEventColor.copy(alpha = 0.8f),
                    fontSize = 9.sp,
                    lineHeight = 10.sp
                )
            }
        }
    }
}
