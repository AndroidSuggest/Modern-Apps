package com.vayunmathur.screentime.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.screentime.platform.ScreenTimeViewModel

/**
 * The screen-time dashboard, reached from Settings (no launcher icon).
 *
 * Self-managed screen time lives under Settings > Digital Wellbeing; this activity answers the
 * homepage row registered in the manifest rather than a launcher intent.
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
                        onSetPeriod = viewModel::setPeriod,
                        onStep = viewModel::stepAnchor,
                        onOpenApp = { packageName ->
                            startActivity(
                                Intent(this, AppDetailsActivity::class.java)
                                    .putExtra(AppDetailsActivity.EXTRA_DETAILS_PACKAGE, packageName),
                            )
                        },
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
