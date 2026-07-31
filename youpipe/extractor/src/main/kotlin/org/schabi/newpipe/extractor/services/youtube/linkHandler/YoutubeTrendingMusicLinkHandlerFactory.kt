package org.schabi.newpipe.extractor.services.youtube.linkHandler

import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory
import org.schabi.newpipe.extractor.utils.Utils
import java.net.MalformedURLException
import java.net.URL
import java.util.Locale

class YoutubeTrendingMusicLinkHandlerFactory private constructor() : ListLinkHandlerFactory() {

    companion object {
        const val KIOSK_ID: String = "trending_music"

        @JvmField
        val INSTANCE: YoutubeTrendingMusicLinkHandlerFactory =
            YoutubeTrendingMusicLinkHandlerFactory()

        private const val PATH = "/charts/TrendingVideos"
    }

    @Throws(ParsingException::class)
    override fun getUrl(id: String, contentFilter: List<String>, sortFilter: String): String {
        return "https://charts.youtube.com$PATH/RightNow"
    }

    @Throws(ParsingException::class)
    override fun getId(url: String): String {
        return KIOSK_ID
    }

    @Throws(ParsingException::class)
    override fun onAcceptUrl(url: String): Boolean {
        val urlObj: URL
        try {
            urlObj = Utils.stringToURL(url)
        } catch (e: MalformedURLException) {
            return false
        }

        return Utils.isHTTP(urlObj) &&
                "charts.youtube.com" == urlObj.host.lowercase(Locale.ROOT) &&
                urlObj.path.startsWith(PATH)
    }
}
