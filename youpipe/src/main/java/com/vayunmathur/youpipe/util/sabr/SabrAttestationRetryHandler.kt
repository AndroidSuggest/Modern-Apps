package com.vayunmathur.youpipe.util.sabr

import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrSession
import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrAttestationException

/**
 * Maintains the PO-token recovery budget for one SABR media acquisition. On a rejected attestation
 * identity it mints a fresh PO token and injects it into the session.
 *
 * The [tokenMinter] is invoked with `force=true`: [LocalDomPoTokenProvider] then tears down the
 * burned BotGuard session and re-bootstraps from a fresh home page before minting, so every
 * retry comes from a new attestation identity. Because each retry refreshes the global session,
 * the identity is always fresh again after a rejection episode, including for the next
 * acquisition — no separate exhaustion hook is needed.
 */
internal class SabrAttestationRetryHandler(
    private val videoId: String,
    private val tokenMinter: ((Boolean) -> ByteArray?)?,
) {
    private var retriesRemaining = MAX_RETRIES

    @Synchronized
    @Throws(SabrAttestationException::class)
    fun prepareRetry(session: YoutubeSabrSession, rejectedTokenError: SabrAttestationException) {
        if (retriesRemaining == 0) {
            throw SabrAttestationException(
                "SABR PO token was rejected after $MAX_RETRIES attestation recovery retries " +
                    "for video=$videoId",
                rejectedTokenError
            )
        }
        val minter = tokenMinter
            ?: throw SabrAttestationException(
                "SABR attestation failed and no PO token minter is available for video=$videoId",
                rejectedTokenError
            )
        val retryNumber = MAX_RETRIES - retriesRemaining + 1
        retriesRemaining--
        val token = try {
            minter(true)
        } catch (error: Exception) {
            throw SabrAttestationException(
                "SABR PO token recovery failed on retry $retryNumber of $MAX_RETRIES " +
                    "for video=$videoId: ${error.message}",
                error
            )
        }
        if (token == null || token.isEmpty()) {
            throw SabrAttestationException(
                "SABR PO token recovery returned no token on retry $retryNumber of $MAX_RETRIES " +
                    "for video=$videoId",
                rejectedTokenError
            )
        }
        session.setPoToken(token)
    }

    /** A media payload proves the current token is usable and restores a fresh budget. */
    @Synchronized
    fun onMediaReceived() {
        retriesRemaining = MAX_RETRIES
    }

    private companion object {
        private const val MAX_RETRIES = 3
    }
}
