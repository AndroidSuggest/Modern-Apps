package org.schabi.newpipe.extractor.services.youtube.linkHandler

import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.UnsupportedTabException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory

class YoutubeChannelTabLinkHandlerFactory private constructor() : ListLinkHandlerFactory() {

    companion object {
        private val INSTANCE = YoutubeChannelTabLinkHandlerFactory()

        @JvmStatic
        fun getInstance(): YoutubeChannelTabLinkHandlerFactory = INSTANCE

        @JvmStatic
        @Throws(UnsupportedTabException::class)
        fun getUrlSuffix(tab: String): String {
            return when (tab) {
                ChannelTabs.VIDEOS -> "/videos"
                ChannelTabs.SHORTS -> "/shorts"
                ChannelTabs.LIVESTREAMS -> "/streams"
                ChannelTabs.ALBUMS -> "/releases"
                ChannelTabs.PLAYLISTS -> "/playlists"
                else -> throw UnsupportedTabException(tab)
            }
        }
    }

    @Throws(ParsingException::class)
    override fun getUrl(id: String, contentFilter: List<String>, sortFilter: String): String {
        return "https://www.youtube.com/$id" + getUrlSuffix(contentFilter[0])
    }

    @Throws(ParsingException::class)
    override fun getId(url: String): String {
        return YoutubeChannelLinkHandlerFactory.getInstance().getId(url)
    }

    @Throws(ParsingException::class)
    override fun onAcceptUrl(url: String): Boolean {
        return try {
            getId(url)
            true
        } catch (e: ParsingException) {
            false
        }
    }

    override fun getAvailableContentFilter(): Array<String> {
        return arrayOf(
            ChannelTabs.VIDEOS,
            ChannelTabs.SHORTS,
            ChannelTabs.LIVESTREAMS,
            ChannelTabs.ALBUMS,
            ChannelTabs.PLAYLISTS
        )
    }
}
