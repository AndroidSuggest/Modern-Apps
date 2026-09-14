package com.vayunmathur.parentalcontrols.ui

import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.parentalcontrols.platform.SupervisionViewModel

/**
 * The bedtime schedule, reached from Settings > Parental controls.
 *
 * Launched by a dashboard tile rather than a launcher icon - see the manifest. This app still
 * has no entry in the launcher, and these screens are the only UI it owns. Parent-gated: only
 * a verified parent may see or change the schedule.
 */
class BedtimeActivity : PinGatedActivity() {

    private val viewModel: SupervisionViewModel by viewModels()

    override fun onPinVerifiedContent() {
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                BedtimeScreen(
                    state = state,
                    actions = BedtimeActions(
                        onEnabledChange = viewModel::setScheduleEnabled,
                        onStartChange = viewModel::setStart,
                        onEndChange = viewModel::setEnd,
                        onToggleDay = viewModel::toggleDay,
                        onAppBedtimeChange = viewModel::setBedtimeBlocked,
                    ),
                )
            }
        }
    }
}
