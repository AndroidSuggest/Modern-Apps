package com.vayunmathur.screentime.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.vayunmathur.screentime.platform.ScreenTimeViewModel

/**
 * Per-app drill-down: today's usage, the timer, and focus membership.
 *
 * Also answers `ACTION_SHOW_SUSPENDED_APP_DETAILS`: when the user taps a paused app, the
 * platform routes the details button here, so the "why is this paused" question lands on the
 * screen that can answer (and lift) it. The suspended package arrives as the intent data.
 */
class AppDetailsActivity : ComponentActivity() {

    private val viewModel: ScreenTimeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val pkg = suspendedPackage() ?: state.apps.firstOrNull()?.packageName
                val app = state.apps.firstOrNull { it.packageName == pkg }
                AppDetailsScreen(
                    packageName = pkg,
                    appLabel = app?.label,
                    usedMinutes = app?.usedMinutes,
                    timerMinutes = app?.timer?.dailyLimitMinutes,
                    pausedInFocus = app?.packageName in state.focus.pausedPackages,
                    focusRunning = state.focusRunning,
                    onTimerChange = { minutes ->
                        pkg?.let { viewModel.setTimer(it, minutes) }
                    },
                    onFocusPausedChange = { paused ->
                        pkg?.let { viewModel.setFocusPaused(it, paused) }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun suspendedPackage(): String? {
        // String literal, not Intent.ACTION_SHOW_SUSPENDED_APP_DETAILS: the constant is absent
        // from this SDK's Intent, and the action string is stable platform surface.
        if (intent.action == ACTION_SHOW_SUSPENDED_APP_DETAILS) {
            intent.data?.schemeSpecificPart?.removePrefix("package:")?.let { return it }
        }
        return intent.getStringExtra(EXTRA_DETAILS_PACKAGE)
    }

    companion object {
        const val EXTRA_DETAILS_PACKAGE = "com.vayunmathur.screentime.extra.DETAILS_PACKAGE"

        private const val ACTION_SHOW_SUSPENDED_APP_DETAILS =
            "android.intent.action.SHOW_SUSPENDED_APP_DETAILS"
    }
}

/** Actions the details screen can take. */
data class DetailsActions(
    val onTimerChange: (Int?) -> Unit,
    val onFocusPausedChange: (Boolean) -> Unit,
)

@Composable
fun AppDetailsScreen(
    packageName: String?,
    appLabel: String?,
    usedMinutes: Long?,
    timerMinutes: Int?,
    pausedInFocus: Boolean,
    focusRunning: Boolean,
    onTimerChange: (Int?) -> Unit,
    onFocusPausedChange: (Boolean) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }

    LazyListScaffold(
        title = appLabel ?: stringResource(R.string.details_title),
        horizontalPadding = 0.dp,
        scrollBehavior = appBarScrollBehavior(),
    ) {
        item {
            SettingsSection(title = stringResource(R.string.details_usage)) {
                SettingsRow(
                    title = stringResource(R.string.details_today),
                    supportingText = formatDetailsMinutes(usedMinutes ?: 0),
                )
                SettingsRow(
                    title = stringResource(R.string.details_timer),
                    supportingText = timerMinutes?.let { formatDetailsMinutes(it.toLong()) }
                        ?: stringResource(R.string.timer_none),
                    onClick = { picking = true },
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.focus_title)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.details_pause_in_focus),
                    supportingText =
                        if (focusRunning) stringResource(R.string.details_focus_running)
                        else stringResource(R.string.details_pause_in_focus_hint),
                    checked = pausedInFocus,
                    onCheckedChange = onFocusPausedChange,
                )
            }
        }

        if (packageName == null) {
            item { SettingsRow(title = stringResource(R.string.details_no_app)) }
        }
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(appLabel ?: stringResource(R.string.details_title)) },
            text = {
                SettingsSection {
                    SettingsRow(
                        title = stringResource(R.string.timer_none),
                        onClick = { onTimerChange(null); picking = false },
                    )
                    for (minutes in DETAILS_CHOICES) {
                        SettingsRow(
                            title = formatDetailsMinutes(minutes.toLong()),
                            onClick = { onTimerChange(minutes); picking = false },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { picking = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private val DETAILS_CHOICES = listOf(15, 30, 45, 60, 90, 120, 180, 240)

private fun formatDetailsMinutes(minutes: Long): String =
    if (minutes < 60) {
        "$minutes min"
    } else {
        val hours = minutes / 60
        val rest = minutes % 60
        if (rest == 0L) "$hours h" else "$hours h $rest min"
    }
