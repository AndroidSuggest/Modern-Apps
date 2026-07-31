package com.vayunmathur.games.hub.util

import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import com.vayunmathur.games.hub.data.entities.PlaySessionEntity

object StreakCalculator {

    data class StreakResult(
        val currentStreak: Int,
        val longestStreak: Int
    )

    fun calculate(
        sessions: List<PlaySessionEntity>,
        minSessionMs: Long = 60_000L,
        now: Long = System.currentTimeMillis()
    ): StreakResult {
        if (sessions.isEmpty()) return StreakResult(0, 0)

        val qualifyingDays = mutableSetOf<Long>()
        for (s in sessions) {
            val qualifies = when {
                s.durationMs != null -> s.durationMs >= minSessionMs
                s.endTime != null -> true
                else -> false
            }
            if (!qualifies) continue
            qualifyingDays.add(dayStart(s.startTime))
        }

        if (qualifyingDays.isEmpty()) return StreakResult(0, 0)

        val sortedDays = qualifyingDays.sorted()

        var maxStreak = 1
        var curRun = 1
        for (i in 1 until sortedDays.size) {
            if (sortedDays[i] - sortedDays[i - 1] == 1.days.inWholeMilliseconds) {
                curRun++
                if (curRun > maxStreak) maxStreak = curRun
            } else {
                curRun = 1
            }
        }

        val todayStart = dayStart(now)
        val yesterdayStart = todayStart - 1.days.inWholeMilliseconds

        var currentStreak = 0
        val lastDay = sortedDays.last()
        if (lastDay == todayStart || lastDay == yesterdayStart) {
            currentStreak = 1
            var idx = sortedDays.lastIndex - 1
            var expectedDay = lastDay - 1.days.inWholeMilliseconds
            while (idx >= 0) {
                if (sortedDays[idx] == expectedDay) {
                    currentStreak++
                    expectedDay -= 1.days.inWholeMilliseconds
                    idx--
                } else if (sortedDays[idx] < expectedDay) {
                    break
                } else {
                    idx--
                }
            }
        }

        return StreakResult(currentStreak, maxStreak)
    }

    private fun dayStart(millis: Long): Long {
        val tz = TimeZone.currentSystemDefault()
        return Instant.fromEpochMilliseconds(millis)
            .toLocalDateTime(tz)
            .date
            .atStartOfDayIn(tz)
            .toEpochMilliseconds()
    }
}
