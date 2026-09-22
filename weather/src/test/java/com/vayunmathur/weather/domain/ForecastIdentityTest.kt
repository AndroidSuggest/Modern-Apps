package com.vayunmathur.weather.domain

import com.vayunmathur.weather.network.Daily
import com.vayunmathur.weather.network.ForecastResponse
import kotlin.test.Test
import kotlin.test.assertEquals

class ForecastIdentityTest {

    private val daily = Daily(
        time = listOf(
            "2026-07-08", "2026-07-09", "2026-07-10", "2026-07-11",
            "2026-07-12", "2026-07-13", "2026-07-14",
            "2026-07-15", "2026-07-16", "2026-07-17",
        ),
    )

    @Test
    fun todayIndex_matchesCurrentTimeDate() {
        assertEquals(7, todayIndex(daily, "2026-07-15T14:30"))
    }

    @Test
    fun todayIndex_fallsBackToZeroForUnknownDate() {
        assertEquals(0, todayIndex(daily, "2026-07-30T14:30"))
    }

    @Test
    fun todayIndex_emptyDaily_returnsZero() {
        assertEquals(0, todayIndex(Daily(), "2026-07-15T14:30"))
        assertEquals(0, todayIndex(null, "2026-07-15T14:30"))
    }

    @Test
    fun nowIndex_matchesCurrentHour() {
        val hourly = com.vayunmathur.weather.network.Hourly(
            time = listOf("2026-07-15T13:00", "2026-07-15T14:00", "2026-07-15T15:00"),
        )
        assertEquals(1, nowIndex(hourly, "2026-07-15T14:30"))
    }

    @Test
    fun nowIndex_emptyHourly_returnsZero() {
        assertEquals(0, nowIndex(com.vayunmathur.weather.network.Hourly(), "2026-07-15T14:30"))
        assertEquals(0, nowIndex(null, "2026-07-15T14:30"))
    }

    @Test
    fun todayIsoDate_prefersCurrentTime() {
        val forecast = ForecastResponse(
            latitude = 0.0,
            longitude = 0.0,
            current = com.vayunmathur.weather.network.Current(
                time = "2026-07-15T14:30",
                temperature = 0.0,
                apparentTemperature = 0.0,
                relativeHumidity = 0,
                weatherCode = 0,
                windSpeed = 0.0,
                windDirection = 0,
            ),
        )
        assertEquals("2026-07-15", todayIsoDate(forecast))
    }
}
