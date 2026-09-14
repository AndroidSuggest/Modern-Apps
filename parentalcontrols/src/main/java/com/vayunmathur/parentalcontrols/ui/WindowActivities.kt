package com.vayunmathur.parentalcontrols.ui

import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.parentalcontrols.R
import com.vayunmathur.parentalcontrols.platform.SupervisionViewModel

/**
 * The daytime downtime window, reached from Settings > Parental controls.
 *
 * Inside the window only allow-listed apps open; everything else waits it out. Parent-gated
 * like every other imposed-enforcement surface.
 */
class DowntimeActivity : PinGatedActivity() {

    private val viewModel: SupervisionViewModel by viewModels()

    override fun onPinVerifiedContent() {
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                WindowScheduleScreen(
                    title = stringResource(R.string.downtime_title),
                    enableLabel = stringResource(R.string.downtime_enable),
                    enableHint = stringResource(R.string.downtime_enable_hint),
                    startLabel = stringResource(R.string.downtime_start),
                    endLabel = stringResource(R.string.downtime_end),
                    daysLabel = stringResource(R.string.downtime_days),
                    appsLabel = stringResource(R.string.downtime_apps),
                    dayInitials = stringResource(R.string.bedtime_day_initials),
                    enabled = state.downtime.enabled,
                    startMinute = state.downtime.startMinute,
                    endMinute = state.downtime.endMinute,
                    daysMask = state.downtime.daysMask,
                    apps = state.apps,
                    loading = state.loading,
                    actions = WindowScheduleActions(
                        onEnabledChange = viewModel::setDowntimeEnabled,
                        onStartChange = viewModel::setDowntimeStart,
                        onEndChange = viewModel::setDowntimeEnd,
                        onToggleDay = viewModel::toggleDowntimeDay,
                        onAppAllowChange = viewModel::setAllowedInDowntime,
                    ),
                )
            }
        }
    }
}

/**
 * The school-hours window, reached from Settings > Parental controls.
 *
 * Same mechanics as downtime with school-friendly defaults (weekdays 08:00-15:00); the
 * allow-list is shared, so approving a learning app covers both windows. Parent-gated.
 */
class SchoolTimeActivity : PinGatedActivity() {

    private val viewModel: SupervisionViewModel by viewModels()

    override fun onPinVerifiedContent() {
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                WindowScheduleScreen(
                    title = stringResource(R.string.school_title),
                    enableLabel = stringResource(R.string.school_enable),
                    enableHint = stringResource(R.string.school_enable_hint),
                    startLabel = stringResource(R.string.school_start),
                    endLabel = stringResource(R.string.school_end),
                    daysLabel = stringResource(R.string.school_days),
                    appsLabel = stringResource(R.string.school_apps),
                    dayInitials = stringResource(R.string.bedtime_day_initials),
                    enabled = state.schoolTime.enabled,
                    startMinute = state.schoolTime.startMinute,
                    endMinute = state.schoolTime.endMinute,
                    daysMask = state.schoolTime.daysMask,
                    apps = state.apps,
                    loading = state.loading,
                    actions = WindowScheduleActions(
                        onEnabledChange = viewModel::setSchoolTimeEnabled,
                        onStartChange = viewModel::setSchoolTimeStart,
                        onEndChange = viewModel::setSchoolTimeEnd,
                        onToggleDay = viewModel::toggleSchoolTimeDay,
                        onAppAllowChange = viewModel::setAllowedInDowntime,
                    ),
                )
            }
        }
    }
}

/**
 * The device-wide daily budget, reached from Settings > Parental controls.
 *
 * One cap for the whole device on top of the per-app caps in AppLimitsActivity. Parent-gated.
 */
class DailyLimitActivity : PinGatedActivity() {

    private val viewModel: SupervisionViewModel by viewModels()

    override fun onPinVerifiedContent() {
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                DailyLimitScreen(
                    currentMinutes = state.dailyLimitMinutes,
                    actions = DailyLimitActions(onLimitChange = viewModel::setDeviceDailyLimit),
                )
            }
        }
    }
}
