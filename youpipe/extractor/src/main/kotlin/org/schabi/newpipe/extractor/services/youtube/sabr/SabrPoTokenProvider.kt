package org.schabi.newpipe.extractor.services.youtube.sabr

import org.schabi.newpipe.extractor.exceptions.ExtractionException
import java.io.IOException

/**
 * Supplies raw WEB PO token bytes for experimental SABR requests.
 */
fun interface SabrPoTokenProvider {
    /**
     * Returns raw PO token bytes for the current SABR session, or `null` if unavailable.
     */
    @Throws(IOException::class, ExtractionException::class)
    fun getPoToken(info: YoutubeSabrInfo, streamState: YoutubeSabrStreamState): ByteArray?

    /**
     * Like [getPoToken], but `forceRefresh` drops the cached token and mints a fresh
     * one. For when the server rejects a token that died mid-playback. Default impl ignores the flag.
     */
    @Throws(IOException::class, ExtractionException::class)
    fun getPoToken(
        info: YoutubeSabrInfo,
        streamState: YoutubeSabrStreamState,
        forceRefresh: Boolean
    ): ByteArray? {
        return getPoToken(info, streamState)
    }
}
