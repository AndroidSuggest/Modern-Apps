package com.vayunmathur.library.util

import android.icu.number.NumberFormatter
import android.icu.number.Precision
import android.icu.util.LocaleData
import android.icu.util.MeasureUnit
import android.icu.util.ULocale
import android.os.Build
import java.text.NumberFormat
import java.util.Locale

fun Double.toStringDigits(digits: Int): String {
    return "%.${digits}f".format(this)
}

fun Long.toStringCommas(): String {
    return NumberFormat.getInstance(Locale.US).format(this)
}

fun Float.formatSpeed(locale: Locale = Locale.getDefault()): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        return NumberFormatter.with()
            .usage("road")
            .unit(MeasureUnit.METER_PER_SECOND)
            .locale(locale)
            .unitWidth(NumberFormatter.UnitWidth.SHORT)
            .precision(Precision.fixedFraction(1))
            .format(this.toDouble())
            .toString()
    }

    // Fallback for API < 33: use LocaleData to determine measurement system
    val measurementSystem = LocaleData.getMeasurementSystem(ULocale.forLocale(locale))
    val isImperial = measurementSystem == LocaleData.MeasurementSystem.US || 
                     measurementSystem == LocaleData.MeasurementSystem.UK

    val (unit, value) = if (isImperial) {
        MeasureUnit.MILE_PER_HOUR to (this * MPH_PER_MPS)
    } else {
        MeasureUnit.KILOMETER_PER_HOUR to (this * KPH_PER_MPS)
    }

    return NumberFormatter.with()
        .unit(unit)
        .locale(locale)
        .unitWidth(NumberFormatter.UnitWidth.SHORT)
        .precision(Precision.fixedFraction(1))
        .format(value.toDouble())
        .toString()
}

private const val MPH_PER_MPS = 2.23694f
private const val KPH_PER_MPS = 3.6f
