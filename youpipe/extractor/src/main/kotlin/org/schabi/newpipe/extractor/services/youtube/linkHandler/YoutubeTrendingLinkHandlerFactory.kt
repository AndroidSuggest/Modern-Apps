package org.schabi.newpipe.extractor.services.youtube.linkHandler

import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isInvidiousURL
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isYoutubeURL
import org.schabi.newpipe.extractor.utils.Utils
import java.net.MalformedURLException
import java.net.URL

class YoutubeTrendingLinkHandlerFactory private constructor() : ListLinkHandlerFactory() {

    companion object {
        @JvmField
        val INSTANCE: YoutubeTrendingLinkHandlerFactory = YoutubeTrendingLinkHandlerFactory()
    }

    @Throws(ParsingException::class)
    override fun getUrl(id: String, contentFilter: List<String>, sortFilter: String): String {
        return "https://www.youtube.com/feed/trending"
    }

    @Throws(ParsingException::class)
    override fun getId(url: String): String {
        return "Trending"
    }

    override fun onAcceptUrl(url: String): Boolean {
        val urlObj: URL
        try {
            urlObj = Utils.stringToURL(url)
        } catch (e: MalformedURLException) {
            return false
        }

        val urlPath = urlObj.path
        return Utils.isHTTP(urlObj) && (isYoutubeURL(urlObj) || isInvidiousURL(urlObj)) &&
                urlPath == "/feed/trending"
    }
}
