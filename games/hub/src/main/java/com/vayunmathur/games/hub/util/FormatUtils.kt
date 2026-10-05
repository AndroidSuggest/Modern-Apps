package com.vayunmathur.games.hub.util

import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.ui.graphics.Color

fun formatPlaytime(ms: Long): String {
    if (ms <= 0) return "0m"
    val hours = ms.milliseconds.inWholeHours
    val minutes = ms.milliseconds.inWholeMinutes % MINUTES_PER_HOUR
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "<1m"
    }
}

fun formatDurationMs(ms: Long): String {
    if (ms <= 0) return "0s"
    val seconds = ms.milliseconds.inWholeSeconds
    val minutes = seconds / MINUTES_PER_HOUR
    val hours = minutes / MINUTES_PER_HOUR
    return when {
        hours > 0 -> "${hours}h ${minutes % MINUTES_PER_HOUR}m"
        minutes > 0 -> "${minutes}m ${seconds % MINUTES_PER_HOUR}s"
        else -> "${seconds}s"
    }
}

fun formatRelativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val diff = now - timestamp
    if (diff < 0) return "now"
    val minutes = diff.milliseconds.inWholeMinutes
    val hours = diff.milliseconds.inWholeHours
    val days = diff.milliseconds.inWholeDays
    return when {
        minutes < 1 -> "just now"
        minutes < MINUTES_PER_HOUR -> "${minutes}m ago"
        hours < HOURS_PER_DAY -> "${hours}h ago"
        days < DAYS_PER_WEEK -> "${days}d ago"
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
    }
}

fun tierColor(tier: String): Color = when (tier.uppercase()) {
    "PLATINUM" -> Color(PLATINUM_ARGB)
    "GOLD" -> Color(GOLD_ARGB)
    "SILVER" -> Color(SILVER_ARGB)
    else -> Color(BRONZE_ARGB)
}

private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24
private const val DAYS_PER_WEEK = 7

private const val PLATINUM_ARGB = 0xFF9C27B0
private const val GOLD_ARGB = 0xFFFFD700
private const val SILVER_ARGB = 0xFF9E9E9E
private const val BRONZE_ARGB = 0xFFCD7F32
