package com.vayunmathur.setupwizard

import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.setupwizard.platform.DebugFlags
import com.vayunmathur.setupwizard.platform.SetupViewModel

/**
 * The whole wizard.
 *
 * One Activity rather than the nine the upstream app used, because the platform only resolves
 * one of them: this is the HOME activity at priority 999 that wins while the device is
 * unprovisioned, and the other eight were reachable only from it. The steps are destinations
 * in [Navigation] now.
 */
class MainActivity : MaActivity() {

    private val viewModel: SetupViewModel by viewModels()
    private var showWizard: Boolean = true

    override fun onCreateBeforeContent() {
        // HOME resolution does not stop pointing here the moment setup is done - the package is
        // disabled for that, and until it is, or if it is ever re-enabled, launching lands
        // back on step one. Bail straight to completion instead of running the flow again.
        if (viewModel.system.isUserSetupComplete() &&
            DebugFlags.getBool(this, "allowLaunchAfterSetupCompleted") != true
        ) {
            viewModel.finishSetup(this, disableOemUnlocking = false)
            showWizard = false
        }
    }

    @Composable
    override fun Content() {
        if (showWizard) Navigation(viewModel) else Box(Modifier.fillMaxSize())
    }
}
