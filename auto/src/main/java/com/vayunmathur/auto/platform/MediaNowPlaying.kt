package com.vayunmathur.auto.platform

/**
 * Whatever the on-device media session is playing right now.
 *
 * Sourced from the phone's media browser service (MA Music's `PlaybackService`
 * or any declared `MediaBrowserService`) and shown in two places: the
 * now-playing card rendered into the ch2 video stream, and the phone status
 * screen. A snapshot, not a live player handle: the position is where playback
 * was when the session last reported, so progress may lag the music by a beat.
 */
data class NowPlayingInfo(
    /** Track title, or null when the session reports none. */
    val title: String?,
    /** Track artist, or null when the session reports none. */
    val artist: String?,
    /** True while audio is actually coming out. */
    val playing: Boolean,
    /** Position in millis at snapshot time, clamped at zero. */
    val positionMs: Long,
    /** Track length in millis, or null when the session does not know it. */
    val durationMs: Long?,
)

/**
 * Something the media monitor observed, forwarded to [AutoSessionState] for the phone UI.
 *
 * Streaming never waits on these: they are fire-and-forget observations, mirroring
 * [VideoEvent].
 */
sealed interface MediaEvent {
    /** The media session reported a new snapshot. */
    data class NowPlayingChanged(val info: NowPlayingInfo) : MediaEvent
}
