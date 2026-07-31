package org.schabi.newpipe.extractor.services.youtube.linkHandler

import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isInvidiousURL
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isYoutubeURL
import org.schabi.newpipe.extractor.utils.Utils
import java.net.MalformedURLException
import java.net.URL

class YoutubeTrendingGamingVideosLinkHandlerFactory private constructor() : ListLinkHandlerFactory() {

    companion object {
        const val KIOSK_ID: String = "trending_gaming"

        @JvmField
        val INSTANCE: YoutubeTrendingGamingVideosLinkHandlerFactory =
            YoutubeTrendingGamingVideosLinkHandlerFactory()
    }

    @Throws(ParsingException::class)
    override fun getUrl(id: String, contentFilters: List<String>, sortFilter: String): String {
        return "https://www.youtube.com/gaming/trending"
    }

    @Throws(ParsingException::class)
    override fun getId(url: String): String {
        return KIOSK_ID
    }

    override fun onAcceptUrl(url: String): Boolean {
        val urlObj: URL
        try {
            urlObj = Utils.stringToURL(url)
        } catch (e: MalformedURLException) {
            return false
        }

        return Utils.isHTTP(urlObj) && (isYoutubeURL(urlObj) || isInvidiousURL(urlObj)) &&
                "/gaming/trending" == urlObj.path
    }
}
