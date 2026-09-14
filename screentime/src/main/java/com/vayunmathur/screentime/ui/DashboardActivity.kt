package com.vayunmathur.screentime.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.screentime.platform.ScreenTimeViewModel

/**
 * The screen-time dashboard, reached from the launcher.
 *
 * Unlike parental controls - which has no launcher entry and lives in Settings - self-managed
 * screen time is something the user opens deliberately, so this is a normal launcher activity.
 */
class DashboardActivity : ComponentActivity() {

    private val viewModel: ScreenTimeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                DashboardScreen(
                    state = state,
                    actions = DashboardActions(
                        onRefresh = viewModel::refresh,
                        onTimerChange = viewModel::setTimer,
                    ),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }
}
