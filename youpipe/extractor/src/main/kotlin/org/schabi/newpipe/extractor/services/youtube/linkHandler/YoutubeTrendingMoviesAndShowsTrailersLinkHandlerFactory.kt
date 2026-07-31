package org.schabi.newpipe.extractor.services.youtube.linkHandler

import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory
import org.schabi.newpipe.extractor.utils.Utils
import java.net.MalformedURLException
import java.net.URL
import java.util.Locale

class YoutubeTrendingMoviesAndShowsTrailersLinkHandlerFactory private constructor() :
    ListLinkHandlerFactory() {

    companion object {
        const val KIOSK_ID: String = "trending_movies_and_shows"

        @JvmField
        val INSTANCE: YoutubeTrendingMoviesAndShowsTrailersLinkHandlerFactory =
            YoutubeTrendingMoviesAndShowsTrailersLinkHandlerFactory()

        private const val PATH = "/charts/TrendingTrailers"
    }

    @Throws(ParsingException::class)
    override fun getUrl(id: String, contentFilter: List<String>, sortFilter: String): String {
        return "https://charts.youtube.com$PATH"
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
                PATH == urlObj.path
    }
}
