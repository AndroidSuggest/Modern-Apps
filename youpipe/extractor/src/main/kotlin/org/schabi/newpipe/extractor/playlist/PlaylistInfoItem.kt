package org.schabi.newpipe.extractor.playlist

import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.stream.Description

open class PlaylistInfoItem(
    serviceId: Int,
    url: String,
    name: String
) : InfoItem(InfoType.PLAYLIST, serviceId, url, name) {

    private var uploaderName: String? = null
    private var uploaderUrl: String? = null
    private var uploaderVerified: Boolean = false

    /**
     * How many streams this playlist have
     */
    private var streamCount: Long = 0
    private var description: Description? = null
    private var playlistType: PlaylistInfo.PlaylistType? = null

    fun getUploaderName(): String? = uploaderName
    fun setUploaderName(uploaderName: String?) {
        this.uploaderName = uploaderName
    }

    fun getUploaderUrl(): String? = uploaderUrl
    fun setUploaderUrl(uploaderUrl: String?) {
        this.uploaderUrl = uploaderUrl
    }

    fun isUploaderVerified(): Boolean = uploaderVerified
    fun setUploaderVerified(uploaderVerified: Boolean) {
        this.uploaderVerified = uploaderVerified
    }

    fun getStreamCount(): Long = streamCount
    fun setStreamCount(streamCount: Long) {
        this.streamCount = streamCount
    }

    fun getDescription(): Description? = description
    fun setDescription(description: Description?) {
        this.description = description
    }

    fun getPlaylistType(): PlaylistInfo.PlaylistType? = playlistType
    fun setPlaylistType(playlistType: PlaylistInfo.PlaylistType?) {
        this.playlistType = playlistType
    }
}
