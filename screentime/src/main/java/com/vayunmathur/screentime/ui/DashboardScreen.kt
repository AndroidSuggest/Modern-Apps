package com.vayunmathur.screentime.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.screentime.R
import com.vayunmathur.screentime.platform.ScreenTimeUiState
import com.vayunmathur.screentime.platform.TimedApp

/** Actions the dashboard can take. */
data class DashboardActions(
    val onRefresh: () -> Unit,
    val onTimerChange: (String, Int?) -> Unit,
)

/** Today's usage, most-used first, with per-app timers one tap away. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(state: ScreenTimeUiState, actions: DashboardActions) {
    var editing by remember { mutableStateOf<TimedApp?>(null) }

    LazyListScaffold(
        title = stringResource(R.string.app_name),
        horizontalPadding = 0.dp,
        scrollBehavior = appBarScrollBehavior(),
    ) {
        item {
            SettingsSection {
                SettingsRow(
                    title = stringResource(R.string.dashboard_today),
                    supportingText = formatMinutes(state.totalMinutes),
                )
            }
        }

        if (state.loading && state.apps.isEmpty()) {
            item { SettingsRow(title = stringResource(R.string.apps_loading)) }
        }

        items(state.apps.size, key = { state.apps[it].packageName }) { index ->
            val app = state.apps[index]
            SettingsRow(
                title = app.label,
                supportingText = timerSupportingText(app),
                onClick = { editing = app },
            )
        }
    }

    val target = editing
    if (target != null) {
        TimerPickerDialog(
            app = target,
            onDismiss = { editing = null },
            onPick = { minutes ->
                actions.onTimerChange(target.packageName, minutes)
                editing = null
            },
        )
    }
}

@Composable
private fun timerSupportingText(app: TimedApp): String {
    val used = formatMinutes(app.usedMinutes)
    val cap = app.timer?.dailyLimitMinutes
    return if (cap == null) used
    else "$used / ${formatMinutes(cap.toLong())}"
}

/**
 * A fixed set of durations rather than free entry.
 *
 * Same rationale as parental controls' limit picker: the platform rejects anything over 24 h
 * and anything negative, and a text field invites both.
 */
@Composable
private fun TimerPickerDialog(
    app: TimedApp,
    onDismiss: () -> Unit,
    onPick: (Int?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(app.label) },
        text = {
            SettingsSection {
                SettingsRow(
                    title = stringResource(R.string.timer_none),
                    onClick = { onPick(null) },
                )
                for (minutes in TIMER_CHOICES) {
                    SettingsRow(
                        title = formatMinutes(minutes.toLong()),
                        onClick = { onPick(minutes) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private val TIMER_CHOICES = listOf(15, 30, 45, 60, 90, 120, 180, 240)

private fun formatMinutes(minutes: Long): String =
    if (minutes < 60) {
        "$minutes min"
    } else {
        val hours = minutes / 60
        val rest = minutes % 60
        if (rest == 0L) "$hours h" else "$hours h $rest min"
    }
