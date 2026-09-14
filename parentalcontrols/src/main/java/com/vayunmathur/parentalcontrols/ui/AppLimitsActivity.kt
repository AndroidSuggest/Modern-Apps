package com.vayunmathur.parentalcontrols.ui

import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.parentalcontrols.platform.SupervisionViewModel

/** Per-app daily caps, reached from Settings > Parental controls. Parent-gated. */
class AppLimitsActivity : PinGatedActivity() {

    private val viewModel: SupervisionViewModel by viewModels()

    override fun onPinVerifiedContent() {
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                AppLimitsScreen(
                    state = state,
                    actions = AppLimitsActions(onLimitChange = viewModel::setDailyLimit),
                )
            }
        }
    }
}
