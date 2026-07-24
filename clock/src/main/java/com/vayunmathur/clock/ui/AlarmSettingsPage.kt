package com.vayunmathur.clock.ui

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.R
import com.vayunmathur.clock.util.ClockViewModel
import com.vayunmathur.clock.util.RINGTONE_SILENT
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource

/** Snooze length choices, in minutes. */
val SNOOZE_OPTIONS = listOf(1, 5, 10, 15, 20, 30)

/** Gradually-increase-volume choices, in seconds (0 = off). */
val GRADUAL_OPTIONS = listOf(0, 5, 15, 30, 60)

fun gradualLabel(context: android.content.Context, seconds: Int): String = if (seconds <= 0) context.getString(R.string.gradual_off) else "${seconds}s"

/** Human-readable name for a stored ringtone value (null = default, [RINGTONE_SILENT] = silent). */
fun ringtoneTitle(context: android.content.Context, uriString: String?): String = when (uriString) {
    null -> context.getString(R.string.ringtone_default)
    RINGTONE_SILENT -> context.getString(R.string.ringtone_silent)
    else -> runCatching {
        RingtoneManager.getRingtone(context, uriString.toUri())?.getTitle(context)
    }.getOrNull() ?: context.getString(R.string.ringtone_custom)
}

/** Build a system ringtone-picker intent seeded with the current selection. */
fun ringtonePickerIntent(existing: String?): Intent =
    Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alarm sound")
        val current: Uri? = when (existing) {
            null -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            RINGTONE_SILENT -> null
            else -> existing.toUri()
        }
        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
    }

/** Extract the chosen ringtone from a picker result, mapping "none" → [RINGTONE_SILENT]. */
fun ringtonePickerResult(data: Intent?): String {
    val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
        data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
    }
    return uri?.toString() ?: RINGTONE_SILENT
}

/**
 * The four shared alarm options (sound, vibrate, snooze length, gradual volume),
 * reused for both a single alarm and the global defaults.
 */
@Composable
fun AlarmOptionControls(
    ringtoneUri: String?,
    vibrate: Boolean,
    snoozeMinutes: Int,
    gradualVolumeSeconds: Int,
    onRingtoneClick: () -> Unit,
    onVibrateChange: (Boolean) -> Unit,
    onSnoozeChange: (Int) -> Unit,
    onGradualChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OptionRow(label = "Sound") {
            TextButton(onClick = onRingtoneClick) { Text(ringtoneTitle(context, ringtoneUri)) }
        }
        OptionRow(label = "Vibrate") {
            Switch(checked = vibrate, onCheckedChange = onVibrateChange)
        }
        OptionRow(label = "Snooze length") {
            OptionDropdown(
                value = "$snoozeMinutes min",
                options = SNOOZE_OPTIONS.map { it to "$it min" },
                onSelect = onSnoozeChange,
            )
        }
        OptionRow(label = "Gradually increase volume") {
            OptionDropdown(
                value = gradualLabel(context, gradualVolumeSeconds),
                options = GRADUAL_OPTIONS.map { it to gradualLabel(context, it) },
                onSelect = onGradualChange,
            )
        }
    }
}

@Composable
private fun OptionRow(label: String, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        trailing()
    }
}

@Composable
private fun OptionDropdown(value: String, options: List<Pair<Int, String>>, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text(value) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelect(key)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmSettingsPage(backStack: NavBackStack<Route>, ds: DataStoreUtils) {
    val scope = rememberCoroutineScope()

    var ringtone by remember { mutableStateOf(ds.getString(ClockViewModel.KEY_DEFAULT_RINGTONE)) }
    var vibrate by remember { mutableStateOf(ds.getBoolean(ClockViewModel.KEY_DEFAULT_VIBRATE, true)) }
    var snooze by remember { mutableStateOf((ds.getLong(ClockViewModel.KEY_DEFAULT_SNOOZE) ?: 5L).toInt()) }
    var gradual by remember { mutableStateOf((ds.getLong(ClockViewModel.KEY_DEFAULT_GRADUAL) ?: 0L).toInt()) }

    val ringtoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val picked = ringtonePickerResult(result.data)
            ringtone = picked
            scope.launch { ds.setString(ClockViewModel.KEY_DEFAULT_RINGTONE, picked) }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.alarm_settings)) },
            navigationIcon = { IconButton(onClick = { backStack.pop() }) { IconBack() } },
        )
    }) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.defaults_for_new_alarms),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            AlarmOptionControls(
                ringtoneUri = ringtone,
                vibrate = vibrate,
                snoozeMinutes = snooze,
                gradualVolumeSeconds = gradual,
                onRingtoneClick = { ringtoneLauncher.launch(ringtonePickerIntent(ringtone)) },
                onVibrateChange = {
                    vibrate = it
                    scope.launch { ds.setBoolean(ClockViewModel.KEY_DEFAULT_VIBRATE, it) }
                },
                onSnoozeChange = {
                    snooze = it
                    scope.launch { ds.setLong(ClockViewModel.KEY_DEFAULT_SNOOZE, it.toLong()) }
                },
                onGradualChange = {
                    gradual = it
                    scope.launch { ds.setLong(ClockViewModel.KEY_DEFAULT_GRADUAL, it.toLong()) }
                },
            )
        }
    }
}
