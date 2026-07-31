package org.schabi.newpipe.extractor.services.youtube.linkHandler

import org.schabi.newpipe.extractor.exceptions.FoundAdException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.LinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isHooktubeURL
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isInvidiousURL
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isY2ubeURL
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isYoutubeServiceURL
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isYoutubeURL
import org.schabi.newpipe.extractor.utils.Utils
import java.net.MalformedURLException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.util.regex.Pattern

class YoutubeStreamLinkHandlerFactory private constructor() : LinkHandlerFactory() {

    companion object {
        private val YOUTUBE_VIDEO_ID_REGEX_PATTERN: Pattern =
            Pattern.compile("^([a-zA-Z0-9_-]{11})")

        private val INSTANCE = YoutubeStreamLinkHandlerFactory()

        @JvmStatic
        fun getInstance(): YoutubeStreamLinkHandlerFactory = INSTANCE

        private val SUBPATHS: List<String> = listOf("embed/", "live/", "shorts/", "watch/", "v/", "w/")

        private fun extractId(id: String?): String? {
            if (id != null) {
                val m = YOUTUBE_VIDEO_ID_REGEX_PATTERN.matcher(id)
                return if (m.find()) m.group(1) else null
            }
            return null
        }

        @Throws(ParsingException::class)
        private fun assertIsId(id: String?): String {
            val extractedId = extractId(id)
            if (extractedId != null) {
                return extractedId
            } else {
                throw ParsingException("The given string is not a YouTube video ID")
            }
        }
    }

    @Throws(ParsingException::class)
    override fun getUrl(id: String): String {
        return "https://www.youtube.com/watch?v=$id"
    }

    @Throws(ParsingException::class)
    override fun getId(theUrlString: String): String {
        var urlString = theUrlString
        try {
            val uri = URI(urlString)
            val scheme = uri.scheme

            if (scheme != null &&
                (scheme == "vnd.youtube" || scheme == "vnd.youtube.launch")
            ) {
                val schemeSpecificPart = uri.schemeSpecificPart
                if (schemeSpecificPart.startsWith("//")) {
                    val extractedId = extractId(schemeSpecificPart.substring(2))
                    if (extractedId != null) {
                        return extractedId
                    }
                    urlString = "https:$schemeSpecificPart"
                } else {
                    return assertIsId(schemeSpecificPart)
                }
            }
        } catch (ignored: URISyntaxException) {
        }

        val url: URL
        try {
            url = Utils.stringToURL(urlString)
        } catch (e: MalformedURLException) {
            throw ParsingException("The given URL is not valid", e)
        }

        val host = url.host
        var path = url.path
        if (path.isNotEmpty()) {
            path = path.substring(1)
        }

        if (!Utils.isHTTP(url) || !(isYoutubeURL(url) || isYoutubeServiceURL(url) ||
                    isHooktubeURL(url) || isInvidiousURL(url) || isY2ubeURL(url))
        ) {
            if (host.equals("googleads.g.doubleclick.net", ignoreCase = true)) {
                throw FoundAdException("Error: found ad: $urlString")
            }
            throw ParsingException("The URL is not a YouTube URL")
        }

        if (YoutubePlaylistLinkHandlerFactory.getInstance().acceptUrl(urlString)) {
            throw ParsingException("Error: no suitable URL: $urlString")
        }

        when (host.uppercase()) {
            "WWW.YOUTUBE-NOCOOKIE.COM" -> {
                if (path.startsWith("embed/")) {
                    return assertIsId(path.substring(6))
                }
            }

            "YOUTUBE.COM", "WWW.YOUTUBE.COM", "M.YOUTUBE.COM", "MUSIC.YOUTUBE.COM" -> {
                if (path == "attribution_link") {
                    val uQueryValue = Utils.getQueryValue(url, "u")

                    val decodedURL: URL
                    try {
                        decodedURL = Utils.stringToURL("https://www.youtube.com$uQueryValue")
                    } catch (e: MalformedURLException) {
                        throw ParsingException("Error: no suitable URL: $urlString")
                    }

                    val viewQueryValue = Utils.getQueryValue(decodedURL, "v")
                    return assertIsId(viewQueryValue)
                }

                val maybeId = getIdFromSubpathsInPath(path)
                if (maybeId != null) {
                    return maybeId
                }

                val viewQueryValue = Utils.getQueryValue(url, "v")
                return assertIsId(viewQueryValue)
            }

            "Y2U.BE", "YOUTU.BE" -> {
                val viewQueryValue = Utils.getQueryValue(url, "v")
                if (viewQueryValue != null) {
                    return assertIsId(viewQueryValue)
                }
                return assertIsId(path)
            }

            "HOOKTUBE.COM",
            "INVIDIO.US",
            "DEV.INVIDIO.US",
            "WWW.INVIDIO.US",
            "REDIRECT.INVIDIOUS.IO",
            "INVIDIOUS.SNOPYTA.ORG",
            "YEWTU.BE",
            "TUBE.CONNECT.CAFE",
            "TUBUS.EDUVID.ORG",
            "INVIDIOUS.KAVIN.ROCKS",
            "INVIDIOUS-US.KAVIN.ROCKS",
            "PIPED.KAVIN.ROCKS",
            "INVIDIOUS.SITE",
            "VID.MINT.LGBT",
            "INVIDIOU.SITE",
            "INVIDIOUS.FDN.FR",
            "INVIDIOUS.048596.XYZ",
            "INVIDIOUS.ZEE.LI",
            "VID.PUFFYAN.US",
            "YTPRIVATE.COM",
            "INVIDIOUS.NAMAZSO.EU",
            "INVIDIOUS.SILKKY.CLOUD",
            "INVIDIOUS.EXONIP.DE",
            "INV.RIVERSIDE.ROCKS",
            "INVIDIOUS.BLAMEFRAN.NET",
            "INVIDIOUS.MOOMOO.ME",
            "YTB.TROM.TF",
            "YT.CYBERHOST.UK",
            "Y.COM.CM" -> {
                if (path == "watch") {
                    val viewQueryValue = Utils.getQueryValue(url, "v")
                    if (viewQueryValue != null) {
                        return assertIsId(viewQueryValue)
                    }
                }
                val maybeId = getIdFromSubpathsInPath(path)
                if (maybeId != null) {
                    return maybeId
                }

                val viewQueryValue = Utils.getQueryValue(url, "v")
                if (viewQueryValue != null) {
                    return assertIsId(viewQueryValue)
                }

                return assertIsId(path)
            }
        }

        throw ParsingException("Error: no suitable URL: $urlString")
    }

    @Throws(FoundAdException::class)
    override fun onAcceptUrl(url: String): Boolean {
        return try {
            getId(url)
            true
        } catch (fe: FoundAdException) {
            throw fe
        } catch (e: ParsingException) {
            false
        }
    }

    @Throws(ParsingException::class)
    private fun getIdFromSubpathsInPath(path: String): String? {
        for (subpath in SUBPATHS) {
            if (path.startsWith(subpath)) {
                val id = path.substring(subpath.length)
                return assertIsId(id)
            }
        }
        return null
    }
}
