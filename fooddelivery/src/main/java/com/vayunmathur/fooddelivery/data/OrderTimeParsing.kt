package com.vayunmathur.fooddelivery.data

/**
 * Parse an ISO-8601 timestamp to epoch millis, tolerating the `Z` suffix and fractional
 * seconds the API returns. Returns null for null/blank/malformed input.
 *
 * The common `yyyy-MM-ddTHH:mm:ss[.fff][Z]` shape is parsed by hand so a well-formed
 * timestamp never goes through exception-driven control flow; anything else falls back to
 * java.time.
 */
internal fun parseIsoMillis(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    fastParseIsoMillis(iso)?.let { return it }
    return runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrNull()
        ?: runCatching {
            java.time.LocalDateTime.parse(iso.substringBefore('Z'))
                .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
}

/** `yyyy-MM-ddTHH:mm:ss[.fff][Z]`, UTC, with no exceptions on the way through. */
private fun fastParseIsoMillis(iso: String): Long? {
    val dateTime = parseDateTimePrefix(iso) ?: return null
    val rest = parseFraction(iso, dateTime) ?: return null
    if (rest.isNotEmpty() && rest != ISO_UTC_SUFFIX) return null
    return dateTime.toEpochMillis()
}

/** The fixed-shape `yyyy-MM-ddTHH:mm:ss` prefix: positions, separators and ranges. */
private fun parseDateTimePrefix(iso: String): IsoDateTime? {
    if (iso.length < ISO_DATETIME_LENGTH || !hasIsoSeparators(iso)) return null
    val n = parseDateTimeNumbers(iso) ?: return null
    val year = n[YEAR_INDEX]
    val month = n[MONTH_INDEX]
    val day = n[DAY_INDEX]
    val hour = n[HOUR_INDEX]
    val minute = n[MINUTE_INDEX]
    val second = n[SECOND_INDEX]
    if (!isValidTime(year, month, hour, minute, second) || !isValidDay(year, month, day)) {
        return null
    }
    return IsoDateTime(year, month, day, hour, minute, second)
}

private fun parseDateTimeNumbers(iso: String): IntArray? {
    val out = IntArray(DATE_TIME_PART_COUNT)
    for (i in DATE_TIME_RANGES.indices) {
        val range = DATE_TIME_RANGES[i]
        out[i] = iso.digits(range.first, range.second) ?: return null
    }
    return out
}

private fun hasIsoSeparators(iso: String): Boolean =
    hasDateSeparators(iso) && hasTimeSeparators(iso) && iso[DATE_TIME_SEP_POS] == ISO_DATE_TIME_SEP

private fun hasDateSeparators(iso: String): Boolean =
    iso[YEAR_SEP_POS] == ISO_DATE_SEP && iso[MONTH_SEP_POS] == ISO_DATE_SEP

private fun hasTimeSeparators(iso: String): Boolean =
    iso[HOUR_SEP_POS] == ISO_TIME_SEP && iso[MINUTE_SEP_POS] == ISO_TIME_SEP

private fun isValidTime(year: Int, month: Int, hour: Int, minute: Int, second: Int): Boolean =
    month in 1..MONTHS_PER_YEAR && isValidClock(hour, minute, second) && year >= 0

private fun isValidClock(hour: Int, minute: Int, second: Int): Boolean =
    hour in 0..HOURS_PER_DAY_LAST && minute in 0..MINUTES_PER_HOUR_LAST &&
        second in 0..SECONDS_PER_MINUTE_LAST

private fun isValidDay(year: Int, month: Int, day: Int): Boolean =
    day in 1..daysInMonth(year, month)

private fun daysInMonth(year: Int, month: Int): Int {
    if (month == FEBRUARY && isLeapYear(year)) return FEBRUARY_LEAP_DAYS
    return DAYS_IN_MONTH[month - 1]
}

private fun isLeapYear(year: Int): Boolean =
    year % LEAP_CYCLE == 0 && (year % CENTURY != 0 || year % LEAP_CENTURY == 0)

/**
 * The optional `.fff` fractional part. Returns the unconsumed tail, or null when a `.`
 * is present but no digits follow it.
 */
private fun parseFraction(iso: String, dateTime: IsoDateTime): String? {
    var rest = iso.substring(ISO_DATETIME_LENGTH)
    if (!rest.startsWith('.')) return rest
    val fraction = rest.drop(1).takeWhile { it in '0'..'9' }
    if (fraction.isEmpty()) return null
    dateTime.millis = fraction.take(MAX_FRACTION_DIGITS).padEnd(MAX_FRACTION_DIGITS, '0').toInt()
    return rest.drop(1 + fraction.length)
}

private data class IsoDateTime(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    var millis: Int = 0,
) {
    fun toEpochMillis(): Long {
        val epochDay = java.time.LocalDate.of(year, month, day).toEpochDay()
        return (epochDay * SECONDS_PER_DAY + hour * SECONDS_PER_HOUR +
            minute * SECONDS_PER_MINUTE + second) * MILLIS_PER_SECOND + millis
    }
}

private const val ISO_DATETIME_LENGTH = 19
private const val ISO_DATE_SEP = '-'
private const val ISO_DATE_TIME_SEP = 'T'
private const val ISO_TIME_SEP = ':'
private const val ISO_UTC_SUFFIX = "Z"
private const val MONTHS_PER_YEAR = 12
private const val HOURS_PER_DAY_LAST = 23
private const val MINUTES_PER_HOUR_LAST = 59
private const val SECONDS_PER_MINUTE_LAST = 59
private const val FEBRUARY = 2
private const val FEBRUARY_LEAP_DAYS = 29
private const val LEAP_CYCLE = 4
private const val CENTURY = 100
private const val LEAP_CENTURY = 400
private const val MAX_FRACTION_DIGITS = 3
private const val SECONDS_PER_DAY = 86_400L
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_MINUTE = 60L
private const val MILLIS_PER_SECOND = 1_000L
private const val DATE_TIME_PART_COUNT = 6
private const val YEAR_INDEX = 0
private const val MONTH_INDEX = 1
private const val DAY_INDEX = 2
private const val HOUR_INDEX = 3
private const val MINUTE_INDEX = 4
private const val SECOND_INDEX = 5
private const val YEAR_SEP_POS = 4
private const val MONTH_SEP_POS = 7
private const val DATE_TIME_SEP_POS = 10
private const val HOUR_SEP_POS = 13
private const val MINUTE_SEP_POS = 16
private const val DECIMAL_RADIX = 10
private val DATE_TIME_RANGES = arrayOf(
    0 to 4,
    5 to 7,
    8 to 10,
    11 to 13,
    14 to 16,
    17 to 19,
)
private val DAYS_IN_MONTH = intArrayOf(
    31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31,
)

private fun String.digits(from: Int, to: Int): Int? {
    var value = 0
    for (i in from until to) {
        val c = this[i]
        if (c < '0' || c > '9') return null
        value = value * DECIMAL_RADIX + (c - '0')
    }
    return value
}
