package com.vayunmathur.youpipe.util.sabr

import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrStreamState
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Immutable metadata needed to construct a SABR MediaSource without owning a live session. */
class SabrSourceSpec internal constructor(
    val videoId: String,
    val info: YoutubeSabrInfo,
    val audioFormat: YoutubeSabrFormat,
    val videoFormat: YoutubeSabrFormat,
    internal val localization: Localization,
    audioInitializationData: ByteArray,
    videoInitializationData: ByteArray,
    preparedSession: YoutubeSabrSession?
) {
    internal val sourceId: Long = NEXT_SOURCE_ID.incrementAndGet()

    private val audioInitializationData: ByteArray = audioInitializationData.clone()
    private val videoInitializationData: ByteArray = videoInitializationData.clone()
    private val preparedSession = AtomicReference(preparedSession)

    constructor(
        videoId: String,
        info: YoutubeSabrInfo,
        audioFormat: YoutubeSabrFormat,
        videoFormat: YoutubeSabrFormat,
        localization: Localization,
        audioInitializationData: ByteArray,
        videoInitializationData: ByteArray
    ) : this(
        videoId, info, audioFormat, videoFormat, localization,
        audioInitializationData, videoInitializationData, null
    )

    internal fun getInitializationData(itag: Int): ByteArray? = when (itag) {
        audioFormat.itag -> audioInitializationData.clone()
        videoFormat.itag -> videoInitializationData.clone()
        else -> null
    }

    internal fun getDurationMs(): Long =
        maxOf(audioFormat.approxDurationMs, videoFormat.approxDurationMs)

    internal fun newStreamState(): YoutubeSabrStreamState {
        val state = YoutubeSabrStreamState(audioFormat, videoFormat)
        state.ingestInitializationData(audioFormat, audioInitializationData)
        state.ingestInitializationData(videoFormat, videoInitializationData)
        return state
    }

    internal fun takePreparedSession(): YoutubeSabrSession? = preparedSession.getAndSet(null)

    internal fun discardPreparedSession() {
        preparedSession.getAndSet(null)?.clearCache()
    }

    private companion object {
        private val NEXT_SOURCE_ID = AtomicLong()
    }
}
