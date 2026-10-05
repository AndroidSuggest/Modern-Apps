package com.vayunmathur.setupwizard

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.setupwizard.platform.SetupFlow
import com.vayunmathur.setupwizard.platform.SetupIntents
import com.vayunmathur.setupwizard.platform.SetupUiState
import com.vayunmathur.setupwizard.platform.SetupViewModel
import com.vayunmathur.setupwizard.ui.FinishScreen
import com.vayunmathur.setupwizard.ui.GesturesScreen
import com.vayunmathur.setupwizard.ui.HandoffScreen
import com.vayunmathur.setupwizard.ui.LocationScreen
import com.vayunmathur.setupwizard.ui.MigrationScreen
import com.vayunmathur.setupwizard.ui.OemUnlockScreen
import com.vayunmathur.setupwizard.ui.WelcomeScreen

@Composable
fun Navigation(viewModel: SetupViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Welcome)
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state = viewModel.state

    /** Moves on from a step the user can come back to. */
    fun advance(from: Route) {
        SetupFlow.next(state.isPrimaryUser, from)?.let(backStack::add)
    }

    /**
     * Moves on from a step that is finished with: the entry is replaced rather than pushed, so
     * a step that handed off to another app and came back does not sit on the stack waiting to
     * hand off a second time when the user presses back.
     */
    fun advanceReplacing(from: Route) {
        SetupFlow.next(state.isPrimaryUser, from)?.let(backStack::setLast)
    }

    /** Opens something outside the wizard that the flow does not wait on. */
    fun open(intent: Intent) {
        try {
            (activity ?: context).startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w("SetupNavigation", "nothing handled ${intent.action}", e)
        }
    }

    // Setup is not a place the user can back out of: there is no other HOME activity while the
    // device is unprovisioned, so letting back finish this Activity leaves a black screen.
    BackHandler(enabled = backStack.backStack.size <= 1) {}

    MainNavigation(backStack) {
        entry<Route.Welcome> {
            WelcomeStep(
                viewModel = viewModel,
                state = state,
                onOpen = { open(it) },
                onNextOemUnlock = { backStack.add(Route.OemUnlock) },
                onNext = { advance(Route.Welcome) },
            )
        }
        entry<Route.OemUnlock> {
            OemUnlockStep(viewModel, state) { advanceReplacing(Route.Welcome) }
        }
        entry<Route.Wifi> {
            WifiStep(onCancelled = backStack::pop, onDone = { advanceReplacing(Route.Wifi) })
        }
        entry<Route.Location> {
            LocationStep(viewModel, state) { advance(Route.Location) }
        }
        entry<Route.Security> {
            SecurityStep(
                viewModel = viewModel,
                state = state,
                onCancelled = backStack::pop,
                onDone = { advanceReplacing(Route.Security) },
            )
        }
        entry<Route.Migration> {
            MigrationStep(
                context,
                onAdvance = { advance(it) },
                onAdvanceReplacing = { advanceReplacing(it) },
            )
        }
        entry<Route.Gestures> {
            GesturesStep(onDone = { advance(it) })
        }
        entry<Route.Finish> {
            FinishStep(viewModel, state, activity)
        }
    }
}

@Composable
private fun WelcomeStep(
    viewModel: SetupViewModel,
    state: SetupUiState,
    onOpen: (Intent) -> Unit,
    onNextOemUnlock: () -> Unit,
    onNext: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.onEnterWelcome() }
    val canCall = context.packageManager
        .hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING)
    WelcomeScreen(
        state = state,
        languages = viewModel::availableLanguages,
        onLanguageSelected = viewModel::setLanguage,
        onAccessibility = { onOpen(SetupIntents.accessibilitySettings()) },
        onEmergencyCall = if (canCall) {
            { onOpen(viewModel.system.emergencyDialerIntent()) }
        } else {
            null
        },
        onNext = {
            if (viewModel.welcomeLeadsToBootloaderWarning()) onNextOemUnlock() else onNext()
        },
    )
}

@Composable
private fun OemUnlockStep(
    viewModel: SetupViewModel,
    state: SetupUiState,
    onContinue: () -> Unit,
) {
    OemUnlockScreen(
        state = state,
        onStartAckTimer = viewModel::startBootloaderAckTimer,
        onRebootToBootloader = viewModel::rebootToBootloader,
        // Not a step of its own, so continuing resumes where the welcome step would
        // have gone rather than looking for whatever follows this screen.
        onContinue = onContinue,
    )
}

@Composable
private fun WifiStep(onCancelled: () -> Unit, onDone: () -> Unit) {
    val title = stringResource(R.string.connect_to_wi_fi)
    val description = stringResource(R.string.select_a_network)
    val skip = stringResource(R.string.set_up_without_wi_fi)
    HandoffScreen(
        intent = { SetupIntents.setupInternet(title, description, skip) },
        onCancelled = onCancelled,
        onCompleted = { onDone() },
        onUnavailable = { onDone() },
    )
}

@Composable
private fun LocationStep(
    viewModel: SetupViewModel,
    state: SetupUiState,
    onNext: () -> Unit,
) {
    LocationScreen(
        state = state,
        onLocationEnabled = viewModel::setLocationEnabled,
        onWifiScanningEnabled = viewModel::setWifiScanningEnabled,
        onNext = onNext,
    )
}

@Composable
private fun SecurityStep(
    viewModel: SetupViewModel,
    state: SetupUiState,
    onCancelled: () -> Unit,
    onDone: () -> Unit,
) {
    HandoffScreen(
        // A device that already has a lock screen has nothing to enrol, so the step
        // reports itself unavailable rather than opening an empty enrolment flow.
        intent = { if (state.deviceSecure) null else SetupIntents.biometricEnroll() },
        onCancelled = { viewModel.refreshSecurity(); onCancelled() },
        onCompleted = { viewModel.refreshSecurity(); onDone() },
        onUnavailable = { onDone() },
    )
}

@Composable
private fun MigrationStep(
    context: Context,
    onAdvance: (Route) -> Unit,
    onAdvanceReplacing: (Route) -> Unit,
) {
    // Resolved once rather than on every recomposition: this is a PackageManager query.
    val restore = remember { SetupIntents.restoreBackup(context) }
    // Nothing restores backups on this image yet, so the step is not shown at all
    // rather than offering a button that opens nothing. See SetupIntents.
    if (restore == null) {
        LaunchedEffect(Unit) { onAdvanceReplacing(Route.Migration) }
    } else {
        val launcher = rememberLauncherForActivityResult(StartActivityForResult()) {
            // Backing out of the restore leaves the user here, with Skip still
            // available; anything else means a restore was started and the step is done.
            if (it.resultCode != Activity.RESULT_CANCELED) onAdvance(Route.Migration)
        }
        MigrationScreen(
            onRestore = { launcher.launch(restore) },
            onSkip = { onAdvance(Route.Migration) },
        )
    }
}

@Composable
private fun GesturesStep(onDone: (Route) -> Unit) {
    val tutorial = remember { SetupIntents.gestureTutorial() }
    val launcher = rememberLauncherForActivityResult(StartActivityForResult()) {
        if (it.resultCode != Activity.RESULT_CANCELED) onDone(Route.Gestures)
    }
    GesturesScreen(
        onTryIt = {
            try {
                launcher.launch(tutorial)
            } catch (e: ActivityNotFoundException) {
                Log.w("SetupNavigation", "no gesture tutorial on this image", e)
                onDone(Route.Gestures)
            }
        },
        onSkip = { onDone(Route.Gestures) },
    )
}

@Composable
private fun FinishStep(
    viewModel: SetupViewModel,
    state: SetupUiState,
    activity: Activity?,
) {
    FinishScreen(
        state = state,
        onFinish = { disableOemUnlocking ->
            activity?.let { viewModel.finishSetup(it, disableOemUnlocking) }
        },
    )
}
