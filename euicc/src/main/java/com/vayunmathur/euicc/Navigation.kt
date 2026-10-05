package com.vayunmathur.euicc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.vayunmathur.euicc.platform.EuiccViewModel
import com.vayunmathur.euicc.ui.ActivationCodeScreen
import com.vayunmathur.euicc.ui.AddSimScreen
import com.vayunmathur.euicc.ui.DeviceInfoScreen
import com.vayunmathur.euicc.ui.DownloadScreen
import com.vayunmathur.euicc.ui.EuiccHomeScreen
import com.vayunmathur.euicc.ui.ProfileDetailScreen
import com.vayunmathur.euicc.ui.QrScannerScreen
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.rememberNavBackStack

@Composable
fun Navigation(viewModel: EuiccViewModel, start: Route? = null) {
    // A cross-app handoff (e.g. camera's "Add eSIM") lands mid-flow: seed Home underneath
    // so back from the entry point reaches the profile list instead of an empty stack.
    // Null means a plain launch — start on Home exactly as before.
    val initial = remember(start) {
        if (start == null || start == Route.Home) arrayOf<Route>(Route.Home)
        else arrayOf(Route.Home, start)
    }
    val backStack = rememberNavBackStack(*initial)

    /**
     * Leaving the activation flow: drop every step of it at once rather than popping back
     * through the scanner and the code entry, and reset the download so re-entering starts
     * clean instead of re-showing the last result.
     */
    fun finishActivation() {
        viewModel.clearDownload()
        backStack.reset(Route.Home)
    }

    MainNavigation(backStack) {
        entry<Route.Home>(metadata = ListPage()) {
            EuiccHomeScreen(
                state = viewModel.state,
                backStack = backStack,
                onReload = viewModel::reload,
                onAddSim = { backStack.add(Route.AddSim) },
            )
        }
        // Morph: the tapped row's profile name travels up into the app bar here.
        entry<Route.ProfileDetail>(metadata = ListDetailPage() + MorphPage()) { route ->
            ProfileDetailScreen(
                iccid = route.iccid,
                state = viewModel.state,
                backStack = backStack,
                onEnable = { viewModel.enable(it.iccid) },
                onDisable = { viewModel.disable(it.iccid) },
                onErase = { viewModel.delete(it.iccid) },
                onRename = { profile, name -> viewModel.rename(profile.iccid, name) },
            )
        }
        entry<Route.DeviceInfo>(metadata = ListDetailPage()) {
            DeviceInfoScreen(
                state = viewModel.state,
                backStack = backStack,
                onRemoveNotification = viewModel::removeNotification,
            )
        }
        entry<Route.AddSim> { AddSimScreen(backStack = backStack) }
        entry<Route.ScanQr> {
            QrScannerScreen(
                backStack = backStack,
                onResult = { backStack.add(Route.Download(it)) },
            )
        }
        entry<Route.ActivationCode> { ActivationCodeScreen(backStack = backStack) }
        entry<Route.Download> { route ->
            DownloadScreen(
                activationCode = route.activationCode,
                imei = route.imei,
                confirmationCode = route.confirmationCode,
                state = viewModel.download,
                onStart = viewModel::startDownload,
                onConfirm = viewModel::confirmDownload,
                onSubmitCode = viewModel::submitConfirmationCode,
                onCancelSession = {
                    viewModel.cancelDownload()
                    finishActivation()
                },
                onDone = ::finishActivation,
            )
        }
    }
}
