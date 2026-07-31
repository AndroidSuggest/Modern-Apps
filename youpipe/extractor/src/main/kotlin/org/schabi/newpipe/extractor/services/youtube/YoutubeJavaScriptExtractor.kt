package org.schabi.newpipe.extractor.services.youtube

import org.jsoup.Jsoup
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.utils.Parser
import java.net.MalformedURLException
import java.net.URL
import java.util.regex.Pattern
import javax.annotation.Nonnull

/**
 * The extractor of YouTube's base JavaScript player file.
 *
 * This class handles fetching of this base JavaScript player file in order to allow other classes
 * to extract the needed data.
 *
 * It will try to get the player URL from YouTube's IFrame resource first, and from a YouTube embed
 * watch page as a fallback.
 */
internal object YoutubeJavaScriptExtractor {

    private const val HTTPS = "https:"
    private const val BASE_JS_PLAYER_URL_FORMAT =
        "https://www.youtube.com/s/player/%s/player_ias.vflset/en_GB/base.js"
    private val IFRAME_RES_JS_BASE_PLAYER_HASH_PATTERN: Pattern = Pattern.compile(
        "player\\\\/([a-z0-9]{8})\\\\/"
    )
    private val EMBEDDED_WATCH_PAGE_JS_BASE_PLAYER_URL_PATTERN: Pattern = Pattern.compile(
        "\"jsUrl\":\"(/s/player/[A-Za-z0-9]+/player_ias\\.vflset/[A-Za-z_-]+/base\\.js)\""
    )

    /**
     * Extracts the JavaScript base player file.
     *
     * @param videoId the video ID used to get the JavaScript base player file (an empty one can be
     *                passed, even it is not recommend in order to spoof better official YouTube
     *                clients)
     * @return the whole JavaScript base player file as a string
     * @throws ParsingException if the extraction of the file failed
     */
    @Nonnull
    @JvmStatic
    @Throws(ParsingException::class)
    fun extractJavaScriptPlayerCode(@Nonnull videoId: String): String {
        var url: String
        try {
            url = extractJavaScriptUrlWithIframeResource()
            val playerJsUrl = cleanJavaScriptUrl(url)

            // Assert that the URL we extracted and built is valid
            URL(playerJsUrl)

            return downloadJavaScriptCode(playerJsUrl)
        } catch (e: Exception) {
            url = extractJavaScriptUrlWithEmbedWatchPage(videoId)
            val playerJsUrl = cleanJavaScriptUrl(url)

            try {
                // Assert that the URL we extracted and built is valid
                URL(playerJsUrl)
            } catch (exception: MalformedURLException) {
                throw ParsingException(
                    "The extracted and built JavaScript URL is invalid", exception
                )
            }

            return downloadJavaScriptCode(playerJsUrl)
        }
    }

    @Nonnull
    @JvmStatic
    @Throws(ParsingException::class)
    fun extractJavaScriptUrlWithIframeResource(): String {
        val iframeUrl: String
        val iframeContent: String
        try {
            iframeUrl = "https://www.youtube.com/iframe_api"
            iframeContent = NewPipe.getDownloader()
                .get(iframeUrl, Localization.DEFAULT)
                .responseBody()
        } catch (e: Exception) {
            throw ParsingException("Could not fetch IFrame resource", e)
        }

        try {
            val hash = Parser.matchGroup1(
                IFRAME_RES_JS_BASE_PLAYER_HASH_PATTERN, iframeContent
            )
            return String.format(BASE_JS_PLAYER_URL_FORMAT, hash)
        } catch (e: Parser.RegexException) {
            throw ParsingException(
                "IFrame resource didn't provide JavaScript base player's hash", e
            )
        }
    }

    @Nonnull
    @JvmStatic
    @Throws(ParsingException::class)
    fun extractJavaScriptUrlWithEmbedWatchPage(@Nonnull videoId: String): String {
        val embedUrl: String
        val embedPageContent: String
        try {
            embedUrl = "https://www.youtube.com/embed/$videoId"
            embedPageContent = NewPipe.getDownloader()
                .get(embedUrl, Localization.DEFAULT)
                .responseBody()
        } catch (e: Exception) {
            throw ParsingException("Could not fetch embedded watch page", e)
        }

        // Parse HTML response with jsoup and look at script elements first
        val doc = Jsoup.parse(embedPageContent)
        val elems = doc.select("script")
            .attr("name", "player/base")
        for (elem in elems) {
            // Script URLs should be relative and not absolute
            val playerUrl = elem.attr("src")
            if (playerUrl.contains("base.js")) {
                return playerUrl
            }
        }

        // Use regexes to match the URL in an embedded script of the HTML page
        try {
            return Parser.matchGroup1(
                EMBEDDED_WATCH_PAGE_JS_BASE_PLAYER_URL_PATTERN, embedPageContent
            )
        } catch (e: Parser.RegexException) {
            throw ParsingException(
                "Embedded watch page didn't provide JavaScript base player's URL", e
            )
        }
    }

    @Nonnull
    private fun cleanJavaScriptUrl(@Nonnull javaScriptPlayerUrl: String): String {
        return if (javaScriptPlayerUrl.startsWith("//")) {
            // https part has to be added manually if the URL is protocol-relative
            HTTPS + javaScriptPlayerUrl
        } else if (javaScriptPlayerUrl.startsWith("/")) {
            // https://www.youtube.com part has to be added manually if the URL is relative to
            // YouTube's domain
            HTTPS + "//www.youtube.com" + javaScriptPlayerUrl
        } else {
            javaScriptPlayerUrl
        }
    }

    @Nonnull
    @Throws(ParsingException::class)
    private fun downloadJavaScriptCode(@Nonnull javaScriptPlayerUrl: String): String {
        try {
            return NewPipe.getDownloader()
                .get(javaScriptPlayerUrl, Localization.DEFAULT)
                .responseBody()
        } catch (e: Exception) {
            throw ParsingException("Could not get JavaScript base player's code", e)
        }
    }
}
