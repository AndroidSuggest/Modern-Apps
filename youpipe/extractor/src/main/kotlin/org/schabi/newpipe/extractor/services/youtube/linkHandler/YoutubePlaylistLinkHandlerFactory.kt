package org.schabi.newpipe.extractor.services.youtube.linkHandler

import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.LinkHandler
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.utils.Utils
import java.net.MalformedURLException
import java.net.URL

class YoutubePlaylistLinkHandlerFactory private constructor() : ListLinkHandlerFactory() {

    companion object {
        private val INSTANCE = YoutubePlaylistLinkHandlerFactory()

        @JvmStatic
        fun getInstance(): YoutubePlaylistLinkHandlerFactory = INSTANCE
    }

    @Throws(ParsingException::class)
    override fun getUrl(id: String, contentFilters: List<String>, sortFilter: String): String {
        return "https://www.youtube.com/playlist?list=$id"
    }

    @Throws(ParsingException::class)
    override fun getId(url: String): String {
        try {
            val urlObj = Utils.stringToURL(url)

            if (!Utils.isHTTP(urlObj) || !(YoutubeParsingHelper.isYoutubeURL(urlObj) ||
                        YoutubeParsingHelper.isInvidiousURL(urlObj))
            ) {
                throw ParsingException("the url given is not a YouTube-URL")
            }

            val path = urlObj.path
            if (path != "/watch" && path != "/playlist") {
                throw ParsingException("the url given is neither a video nor a playlist URL")
            }

            val listID = Utils.getQueryValue(urlObj, "list")
                ?: throw ParsingException("the URL given does not include a playlist")

            if (!listID.matches("[a-zA-Z0-9_-]{10,}".toRegex())) {
                throw ParsingException("the list-ID given in the URL does not match the list pattern")
            }

            return listID
        } catch (e: ParsingException) {
            throw e
        } catch (e: Exception) {
            throw ParsingException("Error could not parse URL: " + e.message, e)
        }
    }

    override fun onAcceptUrl(url: String): Boolean {
        return try {
            getId(url)
            true
        } catch (e: ParsingException) {
            false
        }
    }

    /**
     * If it is a mix (auto-generated playlist) URL, return a [LinkHandler] where the URL is
     * like `https://youtube.com/watch?v=videoId&list=playlistId`
     * Otherwise use super
     */
    @Throws(ParsingException::class)
    override fun fromUrl(url: String): ListLinkHandler {
        try {
            val urlObj = Utils.stringToURL(url)
            val listID = Utils.getQueryValue(urlObj, "list")
            if (listID != null && YoutubeParsingHelper.isYoutubeMixId(listID)) {
                var videoID = Utils.getQueryValue(urlObj, "v")
                if (videoID == null) {
                    videoID = YoutubeParsingHelper.extractVideoIdFromMixId(listID)
                }
                val newUrl = "https://www.youtube.com/watch?v=$videoID&list=$listID"
                return ListLinkHandler(LinkHandler(url, newUrl, listID))
            }
        } catch (e: MalformedURLException) {
            throw ParsingException("Error could not parse URL: " + e.message, e)
        }
        return super.fromUrl(url)
    }
}
