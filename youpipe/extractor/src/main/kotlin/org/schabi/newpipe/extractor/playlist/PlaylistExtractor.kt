package org.schabi.newpipe.extractor.playlist

import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import javax.annotation.Nonnull

abstract class PlaylistExtractor(
    service: StreamingService,
    linkHandler: ListLinkHandler
) : ListExtractor<StreamInfoItem>(service, linkHandler) {

    @Throws(ParsingException::class)
    abstract fun getUploaderUrl(): String?

    @Throws(ParsingException::class)
    abstract fun getUploaderName(): String?

    @Nonnull
    @Throws(ParsingException::class)
    abstract fun getUploaderAvatars(): List<Image>

    @Throws(ParsingException::class)
    abstract fun isUploaderVerified(): Boolean

    @Throws(ParsingException::class)
    abstract fun getStreamCount(): Long

    @Nonnull
    @Throws(ParsingException::class)
    abstract fun getDescription(): Description

    @Nonnull
    @Throws(ParsingException::class)
    open fun getThumbnails(): List<Image> = emptyList()

    @Nonnull
    @Throws(ParsingException::class)
    open fun getBanners(): List<Image> = emptyList()

    @Nonnull
    @Throws(ParsingException::class)
    open fun getSubChannelName(): String = ""

    @Nonnull
    @Throws(ParsingException::class)
    open fun getSubChannelUrl(): String = ""

    @Nonnull
    @Throws(ParsingException::class)
    open fun getSubChannelAvatars(): List<Image> = emptyList()

    @Throws(ParsingException::class)
    open fun getPlaylistType(): PlaylistInfo.PlaylistType = PlaylistInfo.PlaylistType.NORMAL
}
