package org.schabi.newpipe.extractor.playlist

import org.schabi.newpipe.extractor.InfoItemExtractor
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.stream.Description
import javax.annotation.Nonnull

interface PlaylistInfoItemExtractor : InfoItemExtractor {

    /**
     * Get the uploader name
     * @return the uploader name
     */
    @Throws(ParsingException::class)
    fun getUploaderName(): String?

    /**
     * Get the uploader url
     * @return the uploader url
     */
    @Throws(ParsingException::class)
    fun getUploaderUrl(): String?

    /**
     * Get whether the uploader is verified
     * @return whether the uploader is verified
     */
    @Throws(ParsingException::class)
    fun isUploaderVerified(): Boolean

    /**
     * Get the number of streams
     * @return the number of streams
     */
    @Throws(ParsingException::class)
    fun getStreamCount(): Long

    /**
     * Get the description of the playlist if there is any.
     * Otherwise, an [Description.EMPTY_DESCRIPTION] is returned.
     * @return the playlist's description
     */
    @Nonnull
    @Throws(ParsingException::class)
    fun getDescription(): Description = Description.EMPTY_DESCRIPTION

    /**
     * @return the type of this playlist, see [PlaylistInfo.PlaylistType] for a description
     *         of types. If not overridden always returns [PlaylistInfo.PlaylistType.NORMAL].
     */
    @Nonnull
    @Throws(ParsingException::class)
    fun getPlaylistType(): PlaylistInfo.PlaylistType = PlaylistInfo.PlaylistType.NORMAL
}
