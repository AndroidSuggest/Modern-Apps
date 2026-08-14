package com.vayunmathur.flashcards.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.flashcards.R
import com.vayunmathur.flashcards.Route
import com.vayunmathur.flashcards.util.FlashcardsViewModel
import com.vayunmathur.flashcards.util.SettingsActions
import com.vayunmathur.flashcards.util.SettingsUiState
import com.vayunmathur.flashcards.util.ThemeMode
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.rememberTimePickerState
import com.vayunmathur.library.util.NavBackStack
import kotlin.math.roundToInt

/** Binds persisted settings to the stateless [SettingsScreen]. */
@Composable
fun SettingsPage(backStack: NavBackStack<Route>, viewModel: FlashcardsViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}

    val actions = object : SettingsActions {
        override fun back() { backStack.pop() }
        override fun setDesiredRetention(value: Double) { viewModel.setDesiredRetention(value) }
        override fun setNewPerDay(value: Int) { viewModel.setNewPerDay(value) }
        override fun setMaxReviews(value: Int) { viewModel.setMaxReviews(value) }
        override fun setThemeMode(mode: Int) { viewModel.setThemeMode(mode) }
        override fun setReminderEnabled(enabled: Boolean) {
            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            viewModel.setReminderEnabled(enabled)
        }
        override fun setReminderTime(hour: Int, minute: Int) {
            viewModel.setReminderTime(hour, minute)
        }
    }

    SettingsScreen(state = settings, actions = actions)
}

/** The settings screen. ViewModel-free so previews can render it. */
@Composable
fun SettingsScreen(state: SettingsUiState, actions: SettingsActions) {
    var showTimePicker by remember { mutableStateOf(false) }

    AppScaffold(
        title = stringResource(R.string.nav_settings),
        onNavigateBack = { actions.back() },
    ) { paddingValues ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(title = stringResource(R.string.settings_study)) {
                SliderRow(
                    title = stringResource(R.string.setting_retention),
                    value = "${(state.desiredRetention * 100).roundToInt()}%",
                    sliderValue = state.desiredRetention.toFloat(),
                    range = 0.70f..0.98f,
                    steps = 13,
                    onChange = { actions.setDesiredRetention(it.toDouble()) },
                )
                SliderRow(
                    title = stringResource(R.string.setting_new_per_day),
                    value = state.newPerDay.toString(),
                    sliderValue = state.newPerDay.toFloat(),
                    range = 0f..50f,
                    steps = 49,
                    onChange = { actions.setNewPerDay(it.roundToInt()) },
                )
                SliderRow(
                    title = stringResource(R.string.setting_max_reviews),
                    value = state.maxReviews.toString(),
                    sliderValue = state.maxReviews.toFloat(),
                    range = 0f..500f,
                    steps = 0,
                    onChange = { actions.setMaxReviews((it / 10).roundToInt() * 10) },
                )
            }

            SettingsSection(title = stringResource(R.string.settings_reminders)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.setting_reminder_enabled),
                    checked = state.reminderEnabled,
                    onCheckedChange = { actions.setReminderEnabled(it) },
                )
                SettingsRow(
                    title = stringResource(R.string.setting_reminder_time),
                    supportingText = "%02d:%02d".format(state.reminderHour, state.reminderMinute),
                    enabled = state.reminderEnabled,
                    onClick = { showTimePicker = true },
                )
            }

            SettingsSection(title = stringResource(R.string.settings_appearance)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemeChip(R.string.theme_system, ThemeMode.SYSTEM, state.themeMode, actions)
                    ThemeChip(R.string.theme_light, ThemeMode.LIGHT, state.themeMode, actions)
                    ThemeChip(R.string.theme_dark, ThemeMode.DARK, state.themeMode, actions)
                }
            }
        }
    }

    if (showTimePicker) {
        TimePickerDialog(
            initialHour = state.reminderHour,
            initialMinute = state.reminderMinute,
            onConfirm = { hour, minute ->
                actions.setReminderTime(hour, minute)
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false },
        )
    }
}

@Composable
private fun ThemeChip(labelRes: Int, mode: Int, selected: Int, actions: SettingsActions) {
    FilterChip(
        selected = selected == mode,
        onClick = { actions.setThemeMode(mode) },
        label = { Text(stringResource(labelRes)) },
    )
}

@Composable
private fun SliderRow(
    title: String,
    value: String,
    sliderValue: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title)
            Text(value)
        }
        Slider(
            value = sliderValue.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun TimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val timeState = rememberTimePickerState(initialHour = initialHour, initialMinute = initialMinute)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_reminder_time)) },
        text = { androidx.compose.material3.TimePicker(state = timeState) },
        confirmButton = {
            TextButton(onClick = { onConfirm(timeState.hour, timeState.minute) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
