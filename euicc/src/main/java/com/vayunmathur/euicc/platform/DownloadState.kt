package com.vayunmathur.euicc.platform

/**
 * Where a profile download has got to.
 *
 * Modelled as a state machine rather than a set of booleans because the steps are ordered
 * and mutually exclusive, and because the eUICC session behind them is too: there is
 * exactly one download in flight at a time, and the screen has to show whichever phase it
 * is actually in.
 *
 * [Confirm] slows the flow after AuthenticateClient so the user accepts the
 * carrier the SM-DP+ named before anything is written; [AwaitingConfirmationCode]
 * collects the second secret for `ccRequiredFlag` profiles.
 */
sealed interface DownloadState {
    /** No download in flight. */
    data object Idle : DownloadState

    /** Authenticating with the SM-DP+; nothing has touched the eUICC's profile store yet. */
    data object Preparing : DownloadState

    /**
     * The SM-DP+ has named the profile and is waiting for the user to accept it.
     * Reached after AuthenticateClient parses ProfileMetadata; cancelling here
     * frees the server session.
     */
    data class Confirm(val carrier: String?, val profileName: String?) : DownloadState

    /**
     * The profile is confirmation-code protected. [error] is set after a rejected attempt.
     * Reached when the activation code or the SM-DP+ sets `ccRequiredFlag`.
     */
    data class AwaitingConfirmationCode(
        val carrier: String?,
        val error: Boolean = false,
    ) : DownloadState

    /**
     * The profile is being written to the eUICC. [progress] is null during
     * authentication, then 0..1 across the BPP segment stream.
     */
    data class Installing(val progress: Float? = null) : DownloadState

    /** Installed. The profile still has to be enabled, which is Settings' job. */
    data class Complete(val carrier: String?) : DownloadState

    /** Gave up. [message] is the native core's or the server's own wording where there is one. */
    data class Failed(val message: String?) : DownloadState
}
