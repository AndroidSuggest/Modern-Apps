package com.vayunmathur.screentime.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.screentime.R
import com.vayunmathur.screentime.platform.ScreenTimeUiState
import com.vayunmathur.screentime.platform.ScreenTimeViewModel

/**
 * Focus mode, reached from Settings and the dashboard.
 *
 * Manual toggle plus an optional daily schedule over one shared paused set. Starting a
 * session pauses now; the schedule and the QS tile drive the same state through the
 * coordinator, so all three always agree.
 */
class FocusActivity : ComponentActivity() {

    private val viewModel: ScreenTimeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                FocusScreen(
                    state = state,
                    onToggle = { viewModel.setFocusActive(!state.focusRunning) },
                    onScheduleEnabled = { viewModel.setFocusSchedule(enabled = it) },
                    onStartChange = { h, m -> viewModel.setFocusSchedule(startMinute = h * 60 + m) },
                    onEndChange = { h, m -> viewModel.setFocusSchedule(endMinute = h * 60 + m) },
                    onToggleDay = { day ->
                        viewModel.setFocusSchedule(
                            daysMask = state.focus.daysMask xor (1 shl day),
                        )
                    },
                    onAppPausedChange = viewModel::setFocusPaused,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }
}

/** Actions the focus screen can take. */
data class FocusActions(
    val onToggle: () -> Unit,
    val onScheduleEnabled: (Boolean) -> Unit,
    val onStartChange: (Int, Int) -> Unit,
    val onEndChange: (Int, Int) -> Unit,
    val onToggleDay: (Int) -> Unit,
    val onAppPausedChange: (String, Boolean) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusScreen(
    state: ScreenTimeUiState,
    onToggle: () -> Unit,
    onScheduleEnabled: (Boolean) -> Unit,
    onStartChange: (Int, Int) -> Unit,
    onEndChange: (Int, Int) -> Unit,
    onToggleDay: (Int) -> Unit,
    onAppPausedChange: (String, Boolean) -> Unit,
) {
    var picking by remember { mutableStateOf<FocusPicking?>(null) }
    val focus = state.focus

    LazyListScaffold(
        title = stringResource(R.string.focus_title),
        horizontalPadding = 0.dp,
        scrollBehavior = appBarScrollBehavior(),
    ) {
        item {
            SettingsSection {
                SettingsSwitchRow(
                    title = stringResource(R.string.focus_now),
                    supportingText = stringResource(R.string.focus_now_hint),
                    checked = state.focusRunning,
                    onCheckedChange = { onToggle() },
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.focus_schedule)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.focus_schedule_enable),
                    checked = focus.scheduleEnabled,
                    onCheckedChange = onScheduleEnabled,
                )
                SettingsRow(
                    title = stringResource(R.string.focus_start),
                    supportingText = formatScheduleMinute(focus.startMinute),
                    enabled = focus.scheduleEnabled,
                    onClick = { picking = FocusPicking.Start },
                )
                SettingsRow(
                    title = stringResource(R.string.focus_end),
                    supportingText = formatScheduleMinute(focus.endMinute),
                    enabled = focus.scheduleEnabled,
                    onClick = { picking = FocusPicking.End },
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val labels = stringResource(R.string.focus_day_initials).split(",")
                    for (day in 0 until FOCUS_DAYS) {
                        FilterChip(
                            selected = (focus.daysMask shr day) and 1 == 1,
                            onClick = { onToggleDay(day) },
                            enabled = focus.scheduleEnabled,
                            label = { Text(labels.getOrElse(day) { "?" }) },
                        )
                    }
                }
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.focus_apps)) {
                if (state.loading && state.apps.isEmpty()) {
                    SettingsRow(title = stringResource(R.string.apps_loading))
                }
            }
        }

        items(state.apps.size, key = { state.apps[it].packageName }) { index ->
            val app = state.apps[index]
            SettingsSwitchRow(
                title = app.label,
                supportingText = formatStat(app.usedMillis),
                checked = app.packageName in focus.pausedPackages,
                onCheckedChange = { onAppPausedChange(app.packageName, it) },
            )
        }
    }

    val active = picking
    if (active != null) {
        val initial = if (active == FocusPicking.Start) focus.startMinute else focus.endMinute
        val timeState = rememberTimePickerState(
            initialHour = initial / 60,
            initialMinute = initial % 60,
        )
        AlertDialog(
            onDismissRequest = { picking = null },
            title = {
                Text(
                    stringResource(
                        if (active == FocusPicking.Start) R.string.focus_start else R.string.focus_end,
                    ),
                )
            },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    if (active == FocusPicking.Start) {
                        onStartChange(timeState.hour, timeState.minute)
                    } else {
                        onEndChange(timeState.hour, timeState.minute)
                    }
                    picking = null
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { picking = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private enum class FocusPicking { Start, End }

private const val FOCUS_DAYS = 7

private fun formatScheduleMinute(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
