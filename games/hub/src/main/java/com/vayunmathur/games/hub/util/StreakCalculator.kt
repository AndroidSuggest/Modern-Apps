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
        val sortedDays = qualifyingDays(sessions, minSessionMs)
        if (sortedDays.isEmpty()) return StreakResult(0, 0)
        return StreakResult(
            currentStreak = currentStreak(sortedDays, now),
            longestStreak = longestStreak(sortedDays),
        )
    }

    private fun qualifyingDays(sessions: List<PlaySessionEntity>, minSessionMs: Long): List<Long> {
        val qualifyingDays = mutableSetOf<Long>()
        for (s in sessions) {
            if (isQualifyingSession(s, minSessionMs)) qualifyingDays.add(dayStart(s.startTime))
        }
        return qualifyingDays.sorted()
    }

    private fun isQualifyingSession(s: PlaySessionEntity, minSessionMs: Long): Boolean = when {
        s.durationMs != null -> s.durationMs >= minSessionMs
        s.endTime != null -> true
        else -> false
    }

    private fun longestStreak(sortedDays: List<Long>): Int {
        var maxStreak = 1
        var curRun = 1
        for (i in 1 until sortedDays.size) {
            if (sortedDays[i] - sortedDays[i - 1] == DAY_MILLIS) {
                curRun++
                if (curRun > maxStreak) maxStreak = curRun
            } else {
                curRun = 1
            }
        }
        return maxStreak
    }

    private fun currentStreak(sortedDays: List<Long>, now: Long): Int {
        val todayStart = dayStart(now)
        val yesterdayStart = todayStart - DAY_MILLIS
        val lastDay = sortedDays.last()
        if (lastDay != todayStart && lastDay != yesterdayStart) return 0
        var streak = 1
        var idx = sortedDays.lastIndex - 1
        var expectedDay = lastDay - DAY_MILLIS
        while (idx >= 0 && sortedDays[idx] >= expectedDay) {
            if (sortedDays[idx] == expectedDay) {
                streak++
                expectedDay -= DAY_MILLIS
            }
            idx--
        }
        return streak
    }

    private val DAY_MILLIS = 1.days.inWholeMilliseconds

    private fun dayStart(millis: Long): Long {
        val tz = TimeZone.currentSystemDefault()
        return Instant.fromEpochMilliseconds(millis)
            .toLocalDateTime(tz)
            .date
            .atStartOfDayIn(tz)
            .toEpochMilliseconds()
    }
}
