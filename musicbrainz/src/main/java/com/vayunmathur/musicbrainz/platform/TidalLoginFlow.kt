package com.vayunmathur.musicbrainz.platform

import com.vayunmathur.musicbrainz.data.tidal.TidalAuth
import com.vayunmathur.musicbrainz.data.tidal.TidalDeviceCode
import com.vayunmathur.musicbrainz.data.tidal.TidalPollResult
import com.vayunmathur.musicbrainz.data.tidal.TidalTokens
import kotlinx.coroutines.delay
import java.io.IOException

private const val MILLIS_PER_SECOND = 1_000L
private const val MIN_POLL_INTERVAL_SECONDS = 1

// Tidal answers `slow_down` when polled too eagerly; back off by a second each time.
internal const val SLOW_DOWN_STEP_MS = 1_000L

/** One step of the device-code poll: keep waiting, fail, or deliver tokens. */
internal sealed interface TidalLoginStep {
    data object KeepPolling : TidalLoginStep
    data class Failed(val message: String?) : TidalLoginStep
    data class Succeeded(val tokens: TidalTokens) : TidalLoginStep
}

/**
 * The device-code sign-in flow, extracted from the ViewModel so it stays testable
 * and the ViewModel stays under its function budget.
 */
internal class TidalLoginFlow(
    private val onState: (TidalLoginUiState) -> Unit,
    private val onTokens: suspend (TidalTokens) -> Unit,
) {
    private var intervalMs = 0L

    suspend fun run() {
        val code = requestCode() ?: return
        showCode(code)
        intervalMs = code.intervalSeconds.coerceAtLeast(MIN_POLL_INTERVAL_SECONDS) * MILLIS_PER_SECOND
        val deadline = System.currentTimeMillis() + code.expiresInSeconds * MILLIS_PER_SECOND
        pollUntilDone(code, deadline)
    }

    private suspend fun requestCode(): TidalDeviceCode? {
        val code = try {
            TidalAuth.requestDeviceCode()
        } catch (e: IOException) {
            fail(e.readableMessage())
            return null
        }
        return code
    }

    private fun showCode(code: TidalDeviceCode) {
        onState(
            TidalLoginUiState(
                status = TidalLoginStatus.AwaitingUser,
                userCode = code.userCode,
                verificationUri = code.verificationUri,
            ),
        )
    }

    private suspend fun pollUntilDone(code: TidalDeviceCode, deadline: Long) {
        while (System.currentTimeMillis() < deadline) {
            delay(intervalMs)
            when (val step = pollOnce(code)) {
                TidalLoginStep.KeepPolling -> Unit
                is TidalLoginStep.Failed -> {
                    fail(step.message)
                    return
                }
                is TidalLoginStep.Succeeded -> {
                    onTokens(step.tokens)
                    onState(TidalLoginUiState(status = TidalLoginStatus.Success))
                    return
                }
            }
        }
        fail(null)
    }

    private suspend fun pollOnce(code: TidalDeviceCode): TidalLoginStep =
        when (val result = TidalAuth.poll(code.deviceCode)) {
            TidalPollResult.Pending -> TidalLoginStep.KeepPolling
            TidalPollResult.SlowDown -> {
                intervalMs += SLOW_DOWN_STEP_MS
                TidalLoginStep.KeepPolling
            }
            TidalPollResult.Expired -> TidalLoginStep.Failed(null)
            is TidalPollResult.Error -> TidalLoginStep.Failed(result.message)
            is TidalPollResult.Success -> tokensStep(result.tokens)
        }

    private fun tokensStep(tokens: TidalTokens): TidalLoginStep {
        // A blank token would store an account that reports as signed in but
        // silently resolves nothing, so treat it as a failed sign-in.
        if (tokens.accessToken.isBlank()) return TidalLoginStep.Failed(null)
        return TidalLoginStep.Succeeded(tokens)
    }

    private fun fail(message: String?) {
        onState(
            TidalLoginUiState(
                status = TidalLoginStatus.Failed,
                error = message?.takeIf { it.isNotBlank() },
            ),
        )
    }
}
