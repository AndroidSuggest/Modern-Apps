package com.vayunmathur.education.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.AssistChip
import com.vayunmathur.library.ui.AssistChipDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.education.content.Band
import com.vayunmathur.library.ui.IconFire
import com.vayunmathur.library.ui.IconStar
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.vayunmathur.education.R

/** A row of up to [max] stars, [stars] of them filled. */
@Composable
fun StarRow(stars: Int, max: Int = 3, modifier: Modifier = Modifier) {
    val filled = MaterialTheme.colorScheme.tertiary
    val empty = MaterialTheme.colorScheme.outlineVariant
    Row(modifier) {
        repeat(max) { i ->
            IconStar(tint = if (i < stars) filled else empty)
        }
    }
}

/** Streak indicator ("🔥 N"). */
@Composable
fun StreakChip(count: Int) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(pluralStringResource(R.plurals.day_count, count, count)) },
        leadingIcon = { IconFire(tint = MaterialTheme.colorScheme.tertiary) },
        colors = AssistChipDefaults.assistChipColors(
            disabledLabelColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

/** Total stars indicator ("⭐ N"). */
@Composable
fun StarsChip(count: Int) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(count.toString()) },
        leadingIcon = { IconStar(tint = MaterialTheme.colorScheme.tertiary) },
        colors = AssistChipDefaults.assistChipColors(
            disabledLabelColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

/**
 * A parent-set deadline surfaced as a gentle, non-punitive chip. Overdue is
 * shown in the error color but phrased softly by callers.
 */
@Composable
fun DeadlineChip(dueEpochDay: Long, modifier: Modifier = Modifier) {
    val today = LocalDate.now().toEpochDay()
    val diff = dueEpochDay - today
    val (label, color) = when {
        diff < 0 -> "Past due" to MaterialTheme.colorScheme.error
        diff == 0L -> "Due today" to MaterialTheme.colorScheme.tertiary
        diff == 1L -> "Due tomorrow" to MaterialTheme.colorScheme.primary
        else -> "Due in $diff days" to MaterialTheme.colorScheme.primary
    }
    AssistChip(
        onClick = {},
        enabled = false,
        modifier = modifier,
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(disabledLabelColor = color),
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

fun bandLabel(band: Band): String = when (band) {
    Band.K2 -> "K-2 · Guided Playground"
    Band.ELEMENTARY -> "3-5 · Explorer"
    Band.SCHOLAR -> "6-12 · Scholar"
}

fun formatEpochDay(day: Long): String =
    LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/** Integer-average stars (0-3) across a set of skills, unpracticed == 0. */
fun averageStars(
    skillIds: Collection<String>,
    progress: Map<String, com.vayunmathur.education.data.SkillProgress>,
): Int {
    if (skillIds.isEmpty()) return 0
    return skillIds.sumOf { progress[it]?.stars ?: 0 } / skillIds.size
}
