package com.vayunmathur.euicc.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.BackHandler
import com.vayunmathur.euicc.Route
import com.vayunmathur.euicc.platform.DownloadState
import com.vayunmathur.euicc.ui.download.CompleteContent
import com.vayunmathur.euicc.ui.download.ConfirmCarrierContent
import com.vayunmathur.euicc.ui.download.ConfirmationCodeContent
import com.vayunmathur.euicc.ui.download.FailedContent
import com.vayunmathur.euicc.ui.download.InstallingContent
import com.vayunmathur.library.ui.SwappedContent
import com.vayunmathur.library.util.NavBackStack

/**
 * The download, from authentication to installed profile.
 *
 * Every phase lives on this one destination rather than on its own route, because they are
 * steps of a single eUICC session: going "back" from installing to the carrier
 * confirmation would leave the session running with nothing driving it. Back is suppressed
 * outright while the eUICC is being written to, which is the one point where interrupting
 * can leave a half-installed profile behind. Cancelling from Confirm (or from the
 * confirmation-code screen) frees the server session via `onCancelSession`.
 */
@Composable
fun DownloadScreen(
    activationCode: String,
    imei: String?,
    confirmationCode: String?,
    state: DownloadState,
    backStack: NavBackStack<Route>,
    onStart: (String, String?, String?) -> Unit,
    onConfirm: (String?) -> Unit,
    onSubmitCode: (String) -> Unit,
    onCancelSession: () -> Unit,
    onDone: () -> Unit,
) {
    LaunchedEffect(activationCode) {
        if (state is DownloadState.Idle) onStart(activationCode, imei, confirmationCode)
    }

    val busy = state is DownloadState.Preparing || state is DownloadState.Installing
    BackHandler(enabled = busy) {
        // Deliberately empty: swallow back while the eUICC is mid-write.
    }

    SwappedContent(state) { current ->
        when (current) {
            is DownloadState.Idle, is DownloadState.Preparing ->
                InstallingContent(progress = null)

            is DownloadState.Confirm ->
                ConfirmCarrierContent(
                    carrier = current.carrier,
                    profileName = current.profileName,
                    onConfirm = { onConfirm(null) },
                    onCancel = onCancelSession,
                )

            is DownloadState.AwaitingConfirmationCode ->
                ConfirmationCodeContent(
                    carrier = current.carrier,
                    error = current.error,
                    onSubmit = onSubmitCode,
                    onCancel = onCancelSession,
                )

            is DownloadState.Installing -> InstallingContent(progress = current.progress)

            is DownloadState.Complete ->
                CompleteContent(
                    carrier = current.carrier,
                    onDone = onDone,
                )

            is DownloadState.Failed ->
                FailedContent(
                    message = current.message,
                    onRetry = { onStart(activationCode, imei, confirmationCode) },
                    onCancel = onDone,
                )
        }
    }
}
