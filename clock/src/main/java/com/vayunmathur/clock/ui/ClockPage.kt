package com.vayunmathur.clock.ui

import com.vayunmathur.library.util.DateNameStyle
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.clock.R
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.mainPages
import com.vayunmathur.clock.util.ClockViewModel
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.toLocalDateTime
import com.vayunmathur.library.util.localizedDayOfWeekNames
import com.vayunmathur.library.util.localizedMonthNames

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClockPage(backStack: NavBackStack<Route>, ds: DataStoreUtils, clockViewModel: ClockViewModel) {
    val context = LocalContext.current
    val now by clockViewModel.now.collectAsState()
    val cities by clockViewModel.cities.collectAsState()
    val time = now.toLocalDateTime(TimeZone.currentSystemDefault())
    val timeZones by ds.stringSetFlow("time_zones").collectAsState(setOf())
    Scaffold(topBar = {
        TopAppBar({Text(stringResource(R.string.label_clock))})
    }, bottomBar = {
        BottomNavBar(backStack, mainPages(), Route.Clock)
    }, floatingActionButton = {
        FloatingActionButton({
            backStack.add(Route.SelectTimeZonesDialog)
        }) { IconAdd() }
    }) { paddingValues ->
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = paddingValues, verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            item {
                val is24h = DateFormat.is24HourFormat(context)
                val timeFormat = LocalTime.Format {
                    if (is24h) hour(Padding.ZERO) else amPmHour(Padding.NONE)
                    chars(":")
                    minute()
                    chars(":")
                    second()
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(time.time.format(timeFormat), style = MaterialTheme.typography.displayLarge)
                    if (!is24h) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (time.time.hour >= 12) stringResource(R.string.time_pm) else stringResource(R.string.time_am),
                            style = MaterialTheme.typography.displayMedium
                        )
                    }
                }
            }
            item {
                Text(time.date.format(LocalDate.Format {
                    dayOfWeek(DayOfWeekNames(localizedDayOfWeekNames(DateNameStyle.SHORT)))
                    chars(", ")
                    monthName(MonthNames(localizedMonthNames(DateNameStyle.SHORT)))
                    chars(" ")
                    day(Padding.NONE)
                }))
            }
            items(timeZones.toList()) {city ->
                val it = cities?.get(city) ?: return@items
                val timeHere = now.toLocalDateTime(TimeZone.of(it))
                val amPm = if(timeHere.time.hour >= 12) stringResource(R.string.time_pm) else stringResource(R.string.time_am)
                Card {
                    ListItem({Text(city)}, trailingContent = {
                        Text(timeHere.time.format(LocalTime.Format {
                            amPmHour(Padding.NONE)
                            chars(":")
                            minute()
                        }) + amPm)
                    }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                }
            }
        }
    }
}