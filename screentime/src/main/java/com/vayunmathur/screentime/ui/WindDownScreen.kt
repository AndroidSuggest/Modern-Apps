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
 * Overnight wind-down, reached from Settings (`BEDTIME_SETTINGS`) and the dashboard.
 *
 * Nothing is blocked here - the screen just goes quiet at the scheduled hour: grayscale
 * first, Do Not Disturb alongside when configured. Both halves apply and clear together on
 * every reconcile, so a mid-window edit takes effect without waiting for a boundary.
 */
class WindDownActivity : ComponentActivity() {

    private val viewModel: ScreenTimeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                WindDownScreen(
                    state = state,
                    onEnabledChange = { viewModel.setWindDown(enabled = it) },
                    onStartChange = { h, m -> viewModel.setWindDown(startMinute = h * 60 + m) },
                    onEndChange = { h, m -> viewModel.setWindDown(endMinute = h * 60 + m) },
                    onToggleDay = { day ->
                        viewModel.setWindDown(
                            daysMask = state.windDown.daysMask xor (1 shl day),
                        )
                    },
                    onGrayscaleChange = { viewModel.setWindDown(grayscale = it) },
                    onDndChange = { viewModel.setWindDown(doNotDisturb = it) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WindDownScreen(
    state: ScreenTimeUiState,
    onEnabledChange: (Boolean) -> Unit,
    onStartChange: (Int, Int) -> Unit,
    onEndChange: (Int, Int) -> Unit,
    onToggleDay: (Int) -> Unit,
    onGrayscaleChange: (Boolean) -> Unit,
    onDndChange: (Boolean) -> Unit,
) {
    var picking by remember { mutableStateOf<WindDownPicking?>(null) }
    val schedule = state.windDown

    LazyListScaffold(
        title = stringResource(R.string.winddown_title),
        horizontalPadding = 0.dp,
        scrollBehavior = appBarScrollBehavior(),
    ) {
        item {
            SettingsSection {
                SettingsSwitchRow(
                    title = stringResource(R.string.winddown_enable),
                    supportingText = stringResource(R.string.winddown_enable_hint),
                    checked = schedule.enabled,
                    onCheckedChange = onEnabledChange,
                )
                SettingsRow(
                    title = stringResource(R.string.winddown_start),
                    supportingText = formatWindDownMinute(schedule.startMinute),
                    enabled = schedule.enabled,
                    onClick = { picking = WindDownPicking.Start },
                )
                SettingsRow(
                    title = stringResource(R.string.winddown_end),
                    supportingText = formatWindDownMinute(schedule.endMinute),
                    enabled = schedule.enabled,
                    onClick = { picking = WindDownPicking.End },
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val labels = stringResource(R.string.focus_day_initials).split(",")
                    for (day in 0 until WINDDOWN_DAYS) {
                        FilterChip(
                            selected = (schedule.daysMask shr day) and 1 == 1,
                            onClick = { onToggleDay(day) },
                            enabled = schedule.enabled,
                            label = { Text(labels.getOrElse(day) { "?" }) },
                        )
                    }
                }
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.winddown_effects)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.winddown_grayscale),
                    supportingText = stringResource(R.string.winddown_grayscale_hint),
                    checked = schedule.grayscale,
                    enabled = schedule.enabled,
                    onCheckedChange = onGrayscaleChange,
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.winddown_dnd),
                    supportingText = stringResource(R.string.winddown_dnd_hint),
                    checked = schedule.doNotDisturb,
                    enabled = schedule.enabled,
                    onCheckedChange = onDndChange,
                )
            }
        }
    }

    val active = picking
    if (active != null) {
        val initial = if (active == WindDownPicking.Start) schedule.startMinute else schedule.endMinute
        val timeState = rememberTimePickerState(
            initialHour = initial / 60,
            initialMinute = initial % 60,
        )
        AlertDialog(
            onDismissRequest = { picking = null },
            title = {
                Text(
                    stringResource(
                        if (active == WindDownPicking.Start) R.string.winddown_start else R.string.winddown_end,
                    ),
                )
            },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    if (active == WindDownPicking.Start) {
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

private enum class WindDownPicking { Start, End }

private const val WINDDOWN_DAYS = 7

private fun formatWindDownMinute(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
