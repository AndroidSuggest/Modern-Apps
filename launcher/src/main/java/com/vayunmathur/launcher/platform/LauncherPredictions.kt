package com.vayunmathur.launcher.platform

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * The drawer's predictions row and the launch counts behind it, split out so
 * LauncherViewModel.kt stays under the FileLength limit.
 *
 * Implemented as `internal` extensions on [LauncherViewModel]; the members they
 * touch are `internal` on the ViewModel for exactly this reason.
 */

/** A single row of the drawer's grid, which is all a predictions row should ever be. */
internal const val PREDICTION_COUNT = 5

internal const val KEY_LAUNCH_COUNTS = "launcher_launch_counts"

/**
 * The most-launched apps, as the predictions row.
 *
 * A launch count kept in the DataStore rather than the platform's `AppPredictionManager`, which
 * needs a system signature. Crude next to the real predictor, but it needs no permission, it is
 * right about the top few apps within a day of use, and it is the same shape of answer - so the
 * privileged predictor can replace the source without the row changing.
 */
internal fun LauncherViewModel.predictedApps(): List<DrawerApp> {
    if (launchCounts.isEmpty()) return emptyList()
    val byKey = allApps.associateBy { it.key.componentName.flattenToShortString() }
    return launchCounts.entries
        .sortedByDescending { it.value }
        .mapNotNull { byKey[it.key] }
        .filterNot { it.isWorkProfile }
        .take(PREDICTION_COUNT)
}

/**
 * Counts a launch, and remembers it.
 *
 * Flattened into one preference string rather than a row per app: it is a handful of counters
 * read whole and written whole, and a Room table for it would be a migration for nothing.
 */
internal fun LauncherViewModel.countLaunch(key: ComponentKey) {
    val flattened = key.componentName.flattenToShortString()
    launchCounts[flattened] = (launchCounts[flattened] ?: 0) + 1
    _drawer.value = _drawer.value.copy(predictions = predictedApps())
    viewModelScope.launch {
        ds.setString(
            KEY_LAUNCH_COUNTS,
            launchCounts.entries.joinToString("\n") { "${it.key}\t${it.value}" },
        )
    }
}

internal fun LauncherViewModel.loadLaunchCounts() {
    val stored = ds.getString(KEY_LAUNCH_COUNTS).orEmpty()
    stored.lineSequence().forEach { line ->
        val (flattened, count) = line.split('\t').takeIf { it.size == 2 } ?: return@forEach
        count.toLongOrNull()?.let { launchCounts[flattened] = it }
    }
}

internal fun LauncherViewModel.applyDrawerQuery(query: String) {
    val trimmed = query.trim()
    val matches = if (trimmed.isEmpty()) {
        allApps
    } else {
        // Prefix match first, then anywhere: typing "ca" should offer Calendar before
        // Vacation Planner.
        val (prefix, contains) = allApps
            .filter { it.label.contains(trimmed, ignoreCase = true) }
            .partition { it.label.startsWith(trimmed, ignoreCase = true) }
        prefix + contains
    }
    _drawer.value = _drawer.value.copy(
        query = query,
        apps = matches,
        predictions = predictedApps(),
        loading = false,
    )
}
