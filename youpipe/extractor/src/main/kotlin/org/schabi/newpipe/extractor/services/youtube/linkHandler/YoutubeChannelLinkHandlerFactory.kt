package org.schabi.newpipe.extractor.services.youtube.linkHandler

import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.utils.Utils
import java.net.URL
import java.util.regex.Pattern

class YoutubeChannelLinkHandlerFactory private constructor() : ListLinkHandlerFactory() {

    companion object {
        private val INSTANCE = YoutubeChannelLinkHandlerFactory()

        @JvmStatic
        fun getInstance(): YoutubeChannelLinkHandlerFactory = INSTANCE

        private val EXCLUDED_SEGMENTS: Pattern = Pattern.compile(
            "playlist|watch|attribution_link|watch_popup|embed|feed|select_site|account|reporthistory|redirect"
        )
    }

    @Throws(ParsingException::class)
    override fun getUrl(
        id: String,
        contentFilter: List<String>,
        sortFilter: String
    ): String {
        return "https://www.youtube.com/$id"
    }

    private fun isCustomShortChannelUrl(splitPath: Array<String>): Boolean {
        return splitPath.size == 1 && splitPath[0].isNotEmpty() &&
                !EXCLUDED_SEGMENTS.matcher(splitPath[0]).matches()
    }

    private fun isHandle(splitPath: Array<String>): Boolean {
        return splitPath.isNotEmpty() && splitPath[0].startsWith("@")
    }

    @Throws(ParsingException::class)
    override fun getId(url: String): String {
        try {
            val urlObj = Utils.stringToURL(url)
            var path = urlObj.path

            if (!Utils.isHTTP(urlObj) || !(YoutubeParsingHelper.isYoutubeURL(urlObj) ||
                        YoutubeParsingHelper.isInvidiousURL(urlObj) ||
                        YoutubeParsingHelper.isHooktubeURL(urlObj))
            ) {
                throw ParsingException("The URL given is not a YouTube URL")
            }

            // Remove leading "/"
            path = path.substring(1)

            // Java's String.split drops trailing empty strings; isCustomShortChannelUrl
            // requires exactly one segment, so a trailing "/" must not add one.
            val splitPath = path.split("/").dropLastWhile { it.isEmpty() }.toTypedArray()

            if (isHandle(splitPath) || isCustomShortChannelUrl(splitPath)) {
                return splitPath[0]
            }

            if (!path.startsWith("user/") && !path.startsWith("channel/") && !path.startsWith("c/")) {
                throw ParsingException("The given URL is not a channel, a user or a handle URL")
            }

            val id = splitPath[1]

            if (Utils.isBlank(id)) {
                throw ParsingException("The given ID is not a YouTube channel or user ID")
            }

            return splitPath[0] + "/" + id
        } catch (e: ParsingException) {
            throw e
        } catch (e: Exception) {
            throw ParsingException("Could not parse URL :" + e.message, e)
        }
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
}
