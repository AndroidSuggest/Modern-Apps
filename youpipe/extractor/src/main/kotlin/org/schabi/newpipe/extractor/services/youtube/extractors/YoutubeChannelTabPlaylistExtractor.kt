package org.schabi.newpipe.extractor.services.youtube.extractors

import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubePlaylistLinkHandlerFactory
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty
import java.io.IOException

/**
 * A [ChannelTabExtractor] for YouTube system playlists using a
 * [YoutubePlaylistExtractor] instance.
 *
 * It is currently used to bypass age-restrictions on channels marked as age-restricted by their
 * owner(s).
 */
class YoutubeChannelTabPlaylistExtractor @Throws(
    IllegalArgumentException::class,
    SystemPlaylistUrlCreationException::class
) constructor(
    service: StreamingService,
    linkHandler: ListLinkHandler
) : ChannelTabExtractor(service, linkHandler) {

    private val playlistExtractorInstance: PlaylistExtractor
    private var playlistExisting: Boolean = false

    init {
        val playlistLinkHandler = getPlaylistLinkHandler(linkHandler)
        this.playlistExtractorInstance = YoutubePlaylistExtractor(service, playlistLinkHandler)
    }

    @Throws(IOException::class, ExtractionException::class)
    override fun onFetchPage(downloader: Downloader) {
        try {
            playlistExtractorInstance.onFetchPage(downloader)
            if (!playlistExisting) {
                playlistExisting = true
            }
        } catch (e: ContentNotAvailableException) {
            // If a channel has no content of the type requested, the corresponding system playlist
            // won't exist, so a ContentNotAvailableException would be thrown
            // Ignore such issues in this case
        }
    }

    @Throws(IOException::class, ExtractionException::class)
    override fun getInitialPage(): InfoItemsPage {
        if (!playlistExisting) {
            return InfoItemsPage.emptyPage()
        }
        return playlistExtractorInstance.getInitialPage()
    }

    @Throws(IOException::class, ExtractionException::class)
    override fun getPage(page: Page): InfoItemsPage {
        if (!playlistExisting) {
            return InfoItemsPage.emptyPage()
        }
        return playlistExtractorInstance.getPage(page)
    }

    /**
     * Get a playlist [ListLinkHandler] from a channel tab one.
     *
     * This method converts a channel ID without its `UC` prefix into a YouTube system
     * playlist, depending on the first content filter provided in the given
     * [ListLinkHandler].
     *
     * The first content filter must be a channel tabs one among the
     * [ChannelTabs.VIDEOS videos], [ChannelTabs.SHORTS shorts] and
     * [ChannelTabs.LIVESTREAMS] ones, which would be converted respectively into playlists
     * with the ID `UULF`, `UUSH` and `UULV` on which the channel ID without the
     * `UC` part is appended.
     *
     * @param originalLinkHandler the original [ListLinkHandler] with which a
     * [YoutubeChannelTabPlaylistExtractor] instance is being constructed
     *
     * @return a [ListLinkHandler] to use for the [YoutubePlaylistExtractor] instance
     * needed to extract channel tabs data from a system playlist
     * @throws IllegalArgumentException if the original [ListLinkHandler] does not meet the
     * required criteria above
     * @throws SystemPlaylistUrlCreationException if the system playlist URL could not be created,
     * which should never happen
     */
    @Throws(IllegalArgumentException::class, SystemPlaylistUrlCreationException::class)
    private fun getPlaylistLinkHandler(
        originalLinkHandler: ListLinkHandler
    ): ListLinkHandler {
        val contentFilters = originalLinkHandler.contentFilters
        if (contentFilters.isEmpty()) {
            throw IllegalArgumentException("A content filter is required")
        }

        val channelId = originalLinkHandler.id
        if (isNullOrEmpty(channelId) || !channelId!!.startsWith("UC")) {
            throw IllegalArgumentException("Invalid channel ID")
        }

        val channelIdWithoutUc = channelId.substring(2)

        val playlistId: String = when (contentFilters[0]) {
            ChannelTabs.VIDEOS -> "UULF" + channelIdWithoutUc
            ChannelTabs.SHORTS -> "UUSH" + channelIdWithoutUc
            ChannelTabs.LIVESTREAMS -> "UULV" + channelIdWithoutUc
            else -> throw IllegalArgumentException(
                "Only Videos, Shorts and Livestreams tabs can extracted as playlists"
            )
        }

        try {
            val newUrl = YoutubePlaylistLinkHandlerFactory.getInstance().getUrl(playlistId)
            return ListLinkHandler(newUrl, newUrl, playlistId, listOf(), "")
        } catch (e: ParsingException) {
            // This should be not reachable, as the given playlist ID should be valid and
            // YoutubePlaylistLinkHandlerFactory doesn't throw any exception
            throw SystemPlaylistUrlCreationException(
                "Could not create a YouTube playlist from a valid playlist ID", e
            )
        }
    }

    /**
     * Exception thrown when a YouTube system playlist URL could not be created.
     *
     * This exception should be never thrown, as given playlist IDs should be always valid.
     */
    class SystemPlaylistUrlCreationException(message: String, cause: Throwable) :
        RuntimeException(message, cause)
}
