package com.vayunmathur.photos.ui

import com.vayunmathur.library.util.DateNameStyle
import com.vayunmathur.library.util.localizedMonthNames
import com.vayunmathur.photos.R
import com.vayunmathur.photos.data.Photo
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

internal fun groupPhotosByMonth(
    photos: List<Photo>,
    resources: android.content.res.Resources,
): Map<String, List<Photo>> {
    val monthNames = localizedMonthNames(DateNameStyle.SHORT)
    // No per-group sort: PhotoDao.getAllFlow already returns rows newest-first
    // (ORDER BY date DESC, served by index_Photo_date) and groupBy preserves that
    // order within each group, so re-sorting every month was sorting sorted data.
    return photos.groupBy {
        val date = Instant.fromEpochMilliseconds(it.date).toLocalDateTime(TimeZone.currentSystemDefault())
        LocalDate(date.year, date.month, 1)
    }.toSortedMap(compareByDescending { it }).mapKeys {
        resources.getString(R.string.month_year_format, monthNames[it.key.month.ordinal], it.key.year)
    }
}
