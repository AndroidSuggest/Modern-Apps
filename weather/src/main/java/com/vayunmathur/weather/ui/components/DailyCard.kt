package com.vayunmathur.weather.ui.components

import com.vayunmathur.library.util.DateNameStyle
import kotlinx.datetime.isoDayNumber
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.IconCalendar
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.util.localizedDayOfWeekNames
import com.vayunmathur.weather.R
import com.vayunmathur.weather.network.Daily
import com.vayunmathur.weather.util.TemperatureUnit
import com.vayunmathur.weather.util.formatTemperatureCompact
import com.vayunmathur.weather.util.weatherConditionForCode
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/**
 * Direct port of WeatherMaster's `DailyCard`. `Surface(extraLarge, surface,
 * shadowElevation = 2.dp)` containing [CardsHeader] + a `LazyRow` of
 * 210 dp × 65 dp pill `DailyItem`s with 6 dp spacing. Each item shows
 * max/min temps on top, weather icon + precip% + weekday on the bottom.
 */
@Composable
fun DailyCard(
    daily: Daily,
    tempUnit: TemperatureUnit,
    selectedIsoDate: String? = null,
    onDaySelected: (String) -> Unit = {},
) {
    if (daily.time.isEmpty()) return

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            CardsHeader(text = stringResource(R.string.daily_forecast), icon = { m, c -> IconCalendar(m, c) })
            Spacer(Modifier.height(14.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(daily.time.size, key = { "${daily.time[it]}_$it" }) { index ->
                    val date = daily.time.getOrNull(index)
                    val hi = daily.temperatureMax.getOrNull(index) ?: 0.0
                    val lo = daily.temperatureMin.getOrNull(index) ?: 0.0
                    val code = daily.weatherCode.getOrNull(index) ?: 0
                    val precip = daily.precipitationProbabilityMax.getOrNull(index) ?: 0

                    if (index == 0) Spacer(Modifier.width(16.dp))

                    DailyItem(
                        weekday = if (index == 0) stringResource(R.string.today) else dayLabel(date),
                        maxTemp = hi,
                        minTemp = lo,
                        icon = weatherConditionForCode(code).iconContent(true),
                        precipitationProbability = precip,
                        tempUnit = tempUnit,
                        isSelected = date != null && date == selectedIsoDate,
                        onClick = { if (date != null) onDaySelected(date) },
                    )

                    if (index == daily.time.size - 1) Spacer(Modifier.width(16.dp))
                }
            }
        }
    }
}

@Composable
private fun DailyItem(
    weekday: String,
    maxTemp: Double,
    minTemp: Double,
    icon: @Composable (Modifier, Color) -> Unit,
    precipitationProbability: Int,
    tempUnit: TemperatureUnit,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        shape = CircleShape,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier
                .height(210.dp)
                .width(65.dp)
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    formatTemperatureCompact(maxTemp, tempUnit),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    formatTemperatureCompact(minTemp, tempUnit),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                WeatherIconBox(icon = icon, size = 38.dp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "${precipitationProbability}%",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    weekday,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun dayLabel(dateStr: String?): String {
    if (dateStr == null) return "-"
    val date = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: return dateStr
    val locale = LocalConfiguration.current.locales[0]
    return try {
        val isoNum = when (date.dayOfWeek) {
            kotlinx.datetime.DayOfWeek.MONDAY -> 1
            kotlinx.datetime.DayOfWeek.TUESDAY -> 2
            kotlinx.datetime.DayOfWeek.WEDNESDAY -> 3
            kotlinx.datetime.DayOfWeek.THURSDAY -> 4
            kotlinx.datetime.DayOfWeek.FRIDAY -> 5
            kotlinx.datetime.DayOfWeek.SATURDAY -> 6
            kotlinx.datetime.DayOfWeek.SUNDAY -> 7
        }
        localizedDayOfWeekNames(DateNameStyle.SHORT, locale)[isoNum - 1]
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    } catch (_: Exception) {
        localizedDayOfWeekNames(DateNameStyle.SHORT)[date.dayOfWeek.isoDayNumber - 1]
    }
}
