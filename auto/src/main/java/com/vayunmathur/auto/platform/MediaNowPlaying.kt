package com.vayunmathur.auto.platform

import android.graphics.drawable.Drawable

/**
 * Whatever the on-device media session is playing right now.
 *
 * Sourced from the phone's media browser service (MA Music's `PlaybackService`
 * or any declared `MediaBrowserService`) and shown in two places: the
 * now-playing card rendered into the ch2 video stream, and the phone status
 * screen. A snapshot, not a live player handle: the position is where playback
 * was when the session last reported, so progress may lag the music by a beat.
 *
 * Artwork/queue/prev-next mirror the gearhead `frag_dash_media` card shape
 * (`album_art`, `source_badge`, `media_button_row`): [artworkData] feeds the
 * full-bleed art (null = no art backend, art stays GONE), [displayIcon] feeds
 * the 24dp circular source badge (null = badge GONE), and [hasPrevious] /
 * [hasNext] gate the 88dp prev/next buttons. There is still no GAL 11/12
 * media-channel path -- this stays the Phase 4 phone-side replacement, never
 * speaking media channels.
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
    /** Raw artwork bytes from `MediaMetadata.artworkData`, or null when none. */
    val artworkData: ByteArray? = null,
    /** Source icon for the 24dp badge (session package icon), or null when none. */
    val displayIcon: Drawable? = null,
    /** True when the queue has a previous item (prev button enabled). */
    val hasPrevious: Boolean = false,
    /** True when the queue has a next item (next button enabled). */
    val hasNext: Boolean = false,
) {
    /**
     * Whether the car card shows for this snapshot: playing always shows, and
     * paused-with-a-title stays for instant resume. Idle (not playing, no
     * title) hides so the car display never shows an empty card.
     */
    fun shouldShowCard(): Boolean = playing || !title.isNullOrBlank()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NowPlayingInfo) return false
        if (title != other.title) return false
        if (artist != other.artist) return false
        if (playing != other.playing) return false
        if (positionMs != other.positionMs) return false
        if (durationMs != other.durationMs) return false
        if (hasPrevious != other.hasPrevious) return false
        if (hasNext != other.hasNext) return false
        if (displayIcon !== other.displayIcon) return false
        if (artworkData == null && other.artworkData == null) return true
        if (artworkData == null || other.artworkData == null) return false
        return artworkData.contentEquals(other.artworkData)
    }

    override fun hashCode(): Int {
        var result = title?.hashCode() ?: 0
        result = 31 * result + (artist?.hashCode() ?: 0)
        result = 31 * result + playing.hashCode()
        result = 31 * result + positionMs.hashCode()
        result = 31 * result + (durationMs?.hashCode() ?: 0)
        result = 31 * result + (artworkData?.contentHashCode() ?: 0)
        result = 31 * result + System.identityHashCode(displayIcon)
        result = 31 * result + hasPrevious.hashCode()
        result = 31 * result + hasNext.hashCode()
        return result
    }
}

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
