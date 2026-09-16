package com.vayunmathur.parentalcontrols.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.parentalcontrols.auth.ParentPin
import com.vayunmathur.parentalcontrols.platform.SupervisionViewModel

/**
 * The top-level "Parental controls" page, launched from Settings (MAOS redirects the platform
 * supervision entry here - see the Settings patch in vendor/modern-apps).
 *
 * The page itself is read-only, so a child may see the current setup, but every change is behind
 * the parent PIN: flipping the master switch, managing the PIN, and each control's own editor
 * (which re-gate themselves via [PinGatedActivity]).
 */
class DashboardActivity : ComponentActivity() {

    private val viewModel: SupervisionViewModel by viewModels()
    private var pendingControls: Boolean? = null

    /** Master-switch change: verify the PIN, then apply the pending value. */
    private val controlsGate =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == PinGateActivity.RESULT_VERIFIED) {
                pendingControls?.let { viewModel.setControlsEnabled(it) }
            }
            pendingControls = null
        }

    /** "Manage your PIN": verify the current PIN before allowing it to be replaced. */
    private val pinManageGate =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == PinGateActivity.RESULT_VERIFIED) {
                startActivity(Intent(this, PinSetupActivity::class.java))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                DashboardScreen(
                    state = state,
                    actions = DashboardActions(
                        onSetControls = ::requestControls,
                        onOpenDailyLimit = { open(DailyLimitActivity::class.java) },
                        onOpenAppLimits = { open(AppLimitsActivity::class.java) },
                        onOpenDowntime = { open(DowntimeActivity::class.java) },
                        onOpenWebFilters = { open(WebContentFiltersActivity::class.java) },
                        onOpenPin = ::requestManagePin,
                    ),
                )
            }
        }
    }

    private fun open(activity: Class<*>) = startActivity(Intent(this, activity))

    private fun requestControls(desired: Boolean) {
        if (!ParentPin.get(this).isSet()) {
            // No PIN yet: send the parent to create one first; they can toggle afterwards.
            startActivity(Intent(this, PinSetupActivity::class.java))
            return
        }
        pendingControls = desired
        controlsGate.launch(Intent(this, PinGateActivity::class.java))
    }

    private fun requestManagePin() {
        if (!ParentPin.get(this).isSet()) {
            startActivity(Intent(this, PinSetupActivity::class.java))
        } else {
            pinManageGate.launch(Intent(this, PinGateActivity::class.java))
        }
    }
}
