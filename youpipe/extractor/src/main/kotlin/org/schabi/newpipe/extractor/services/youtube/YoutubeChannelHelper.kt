package org.schabi.newpipe.extractor.services.youtube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.defaultAlertsCheck
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getJsonPostResponse
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getTextFromObject
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.hasArtistOrVerifiedIconBadgeAttachment
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.prepareDesktopJsonBuilder
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString
import java.io.IOException
import java.io.Serializable
import java.nio.charset.StandardCharsets

/**
 * Shared functions for extracting YouTube channel pages and tabs.
 */
object YoutubeChannelHelper {

    private const val BROWSE_ENDPOINT = "browseEndpoint"
    private const val BROWSE_ID = "browseId"
    private const val CAROUSEL_HEADER_RENDERER = "carouselHeaderRenderer"
    private const val C4_TABBED_HEADER_RENDERER = "c4TabbedHeaderRenderer"
    private const val CONTENT = "content"
    private const val CONTENTS = "contents"
    private const val HEADER = "header"
    private const val PAGE_HEADER_VIEW_MODEL = "pageHeaderViewModel"
    private const val TAB_RENDERER = "tabRenderer"
    private const val TITLE = "title"
    private const val TOPIC_CHANNEL_DETAILS_RENDERER = "topicChannelDetailsRenderer"

    @JvmStatic
    @Throws(ExtractionException::class, IOException::class)
    fun resolveChannelId(idOrPath: String): String {
        val channelId = idOrPath.split("/")

        if (channelId[0].startsWith("UC")) {
            return channelId[0]
        }

        if (channelId[0] != "channel") {
            var urlToResolve: String? = "https://www.youtube.com/$idOrPath"

            var endpoint: JsonObject? = null
            var webPageType = ""

            var tries = 0
            while (urlToResolve != null && tries < 3) {
                val body = prepareDesktopJsonBuilder(Localization.DEFAULT, ContentCountry.DEFAULT)
                    .value("url", urlToResolve)
                    .done().toString()
                    .toByteArray(StandardCharsets.UTF_8)

                val jsonResponse = getJsonPostResponse(
                    "navigation/resolve_url", body, Localization.DEFAULT
                )

                checkIfChannelResponseIsValid(jsonResponse)

                endpoint = jsonResponse.getObject("endpoint")

                webPageType = endpoint?.getObject("commandMetadata")
                    ?.getObject("webCommandMetadata")
                    ?.getString("webPageType") ?: ""

                urlToResolve = if ("WEB_PAGE_TYPE_UNKNOWN" == webPageType) {
                    endpoint?.getObject("urlEndpoint")?.getString("url")
                } else {
                    null
                }
                tries++
            }

            val browseId = endpoint?.getObject(BROWSE_ENDPOINT)
                ?.getString(BROWSE_ID, "") ?: ""

            if ((webPageType.equals("WEB_PAGE_TYPE_BROWSE", ignoreCase = true) ||
                        webPageType.equals("WEB_PAGE_TYPE_CHANNEL", ignoreCase = true)) &&
                browseId.isNotEmpty()
            ) {
                if (!browseId.startsWith("UC")) {
                    throw ExtractionException("Redirected id is not pointing to a channel")
                }
                return browseId
            }

            if (channelId.size < 2) {
                throw ExtractionException("Failed to resolve channelId for $idOrPath")
            }
        }

        return channelId[1]
    }

    /**
     * Response data object for [getChannelResponse], after any redirection.
     */
    class ChannelResponseData(
        @JvmField val jsonResponse: JsonObject,
        @JvmField val channelId: String
    )

    @JvmStatic
    @Throws(ExtractionException::class, IOException::class)
    fun getChannelResponse(
        channelId: String,
        parameters: String,
        localization: Localization,
        country: ContentCountry
    ): ChannelResponseData {
        var id = channelId
        var ajaxJson: JsonObject? = null

        var level = 0
        while (level < 3) {
            val body = prepareDesktopJsonBuilder(localization, country)
                .value(BROWSE_ID, id)
                .value("params", parameters)
                .done().toString()
                .toByteArray(StandardCharsets.UTF_8)

            val jsonResponse = getJsonPostResponse("browse", body, localization)

            checkIfChannelResponseIsValid(jsonResponse)

            val endpoint = jsonResponse.getArray("onResponseReceivedActions")
                ?.getObject(0)
                ?.getObject("navigateAction")
                ?.getObject("endpoint")

            val webPageType = endpoint?.getObject("commandMetadata")
                ?.getObject("webCommandMetadata")
                ?.getString("webPageType") ?: ""

            val browseId = endpoint?.getObject(BROWSE_ENDPOINT)
                ?.getString(BROWSE_ID, "") ?: ""

            if ((webPageType.equals("WEB_PAGE_TYPE_BROWSE", ignoreCase = true) ||
                        webPageType.equals("WEB_PAGE_TYPE_CHANNEL", ignoreCase = true)) &&
                browseId.isNotEmpty()
            ) {
                if (!browseId.startsWith("UC")) {
                    throw ExtractionException("Redirected id is not pointing to a channel")
                }
                id = browseId
                level++
            } else {
                ajaxJson = jsonResponse
                break
            }
        }

        if (ajaxJson == null) {
            throw ExtractionException("Got no channel response after 3 redirects")
        }

        defaultAlertsCheck(ajaxJson)

        return ChannelResponseData(ajaxJson, id)
    }

    @Throws(ContentNotAvailableException::class)
    private fun checkIfChannelResponseIsValid(jsonResponse: JsonObject) {
        val errorObj = jsonResponse.getObject("error")
        if (errorObj != null && errorObj.isNotEmpty()) {
            val errorCode = errorObj["code"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() } ?: -1
            if (errorCode == 404) {
                throw ContentNotAvailableException("This channel doesn't exist.")
            } else {
                val status = errorObj.getString("status") ?: ""
                val message = errorObj.getString("message") ?: ""
                throw ContentNotAvailableException("Got error:\"$status\": $message")
            }
        }
    }

    /**
     * A channel header response.
     */
    class ChannelHeader(
        @JvmField val json: JsonObject,
        @JvmField val headerType: HeaderType
    ) : Serializable {

        enum class HeaderType {
            C4_TABBED,
            INTERACTIVE_TABBED,
            CAROUSEL,
            PAGE
        }
    }

    @JvmStatic
    fun getChannelHeader(channelResponse: JsonObject): ChannelHeader? {
        val header = channelResponse.getObject(HEADER) ?: return null

        if (header.containsKey(C4_TABBED_HEADER_RENDERER)) {
            val json = header.getObject(C4_TABBED_HEADER_RENDERER) ?: return null
            return ChannelHeader(json, ChannelHeader.HeaderType.C4_TABBED)
        } else if (header.containsKey(CAROUSEL_HEADER_RENDERER)) {
            val carousel = header.getObject(CAROUSEL_HEADER_RENDERER)?.getArray(CONTENTS)
            val item = carousel?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it.containsKey(TOPIC_CHANNEL_DETAILS_RENDERER) }
                ?.getObject(TOPIC_CHANNEL_DETAILS_RENDERER) ?: return null
            return ChannelHeader(item, ChannelHeader.HeaderType.CAROUSEL)
        } else if (header.containsKey("pageHeaderRenderer")) {
            val json = header.getObject("pageHeaderRenderer") ?: return null
            return ChannelHeader(json, ChannelHeader.HeaderType.PAGE)
        } else if (header.containsKey("interactiveTabbedHeaderRenderer")) {
            val json = header.getObject("interactiveTabbedHeaderRenderer") ?: return null
            return ChannelHeader(json, ChannelHeader.HeaderType.INTERACTIVE_TABBED)
        }

        return null
    }

    @JvmStatic
    fun isChannelVerified(channelHeader: ChannelHeader): Boolean {
        return when (channelHeader.headerType) {
            ChannelHeader.HeaderType.CAROUSEL -> true
            ChannelHeader.HeaderType.PAGE -> {
                val pageHeaderViewModel = channelHeader.json.getObject(CONTENT)
                    ?.getObject(PAGE_HEADER_VIEW_MODEL)

                val attachmentRuns = pageHeaderViewModel
                    ?.getObject(TITLE)
                    ?.getObject("dynamicTextViewModel")
                    ?.getObject("text")
                    ?.getArray("attachmentRuns")

                val hasCircleOrMusicIcon = if (attachmentRuns != null) {
                    hasArtistOrVerifiedIconBadgeAttachment(attachmentRuns)
                } else false

                if (!hasCircleOrMusicIcon && pageHeaderViewModel?.getObject("image")
                        ?.containsKey("contentPreviewImageViewModel") == true
                ) {
                    true
                } else {
                    hasCircleOrMusicIcon
                }
            }
            ChannelHeader.HeaderType.INTERACTIVE_TABBED -> channelHeader.json.containsKey("autoGenerated")
            else -> {
                val badges = channelHeader.json.getArray("badges")
                YoutubeParsingHelper.isVerified(badges ?: JsonArray(emptyList()))
            }
        }
    }

    @JvmStatic
    @Throws(ParsingException::class)
    fun getChannelId(
        channelHeader: ChannelHeader?,
        jsonResponse: JsonObject,
        fallbackChannelId: String?
    ): String {
        if (channelHeader != null) {
            when (channelHeader.headerType) {
                ChannelHeader.HeaderType.C4_TABBED -> {
                    val channelId = channelHeader.json.getObject(HEADER)
                        ?.getObject(C4_TABBED_HEADER_RENDERER)
                        ?.getString("channelId", "") ?: ""
                    if (!isNullOrEmpty(channelId)) return channelId

                    val navigationC4TabChannelId = channelHeader.json
                        .getObject("navigationEndpoint")
                        ?.getObject(BROWSE_ENDPOINT)
                        ?.getString(BROWSE_ID)
                    if (!isNullOrEmpty(navigationC4TabChannelId)) return navigationC4TabChannelId!!
                }
                ChannelHeader.HeaderType.CAROUSEL -> {
                    val navigationCarouselChannelId = channelHeader.json.getObject(HEADER)
                        ?.getObject(CAROUSEL_HEADER_RENDERER)
                        ?.getArray(CONTENTS)
                        ?.mapNotNull { it as? JsonObject }
                        ?.firstOrNull { it.containsKey(TOPIC_CHANNEL_DETAILS_RENDERER) }
                        ?.getObject(TOPIC_CHANNEL_DETAILS_RENDERER)
                        ?.getObject("navigationEndpoint")
                        ?.getObject(BROWSE_ENDPOINT)
                        ?.getString(BROWSE_ID)
                    if (!isNullOrEmpty(navigationCarouselChannelId)) return navigationCarouselChannelId!!
                }
                else -> {}
            }
        }

        val externalChannelId = jsonResponse.getObject("metadata")
            ?.getObject("channelMetadataRenderer")
            ?.getString("externalChannelId")
        if (!isNullOrEmpty(externalChannelId)) return externalChannelId!!

        if (!isNullOrEmpty(fallbackChannelId)) return fallbackChannelId!!

        throw ParsingException("Could not get channel ID")
    }

    @JvmStatic
    @Throws(ParsingException::class)
    fun getChannelName(
        channelHeader: ChannelHeader?,
        channelAgeGateRenderer: JsonObject?,
        jsonResponse: JsonObject
    ): String {
        if (channelAgeGateRenderer != null) {
            val title = channelAgeGateRenderer.getString("channelTitle")
            if (isNullOrEmpty(title)) throw ParsingException("Could not get channel name")
            return title!!
        }

        val metadataRendererTitle = jsonResponse.getObject("metadata")
            ?.getObject("channelMetadataRenderer")
            ?.getString(TITLE)
        if (!isNullOrEmpty(metadataRendererTitle)) return metadataRendererTitle!!

        val headerName: String? = channelHeader?.let { header ->
            val channelJson = header.json
            when (header.headerType) {
                ChannelHeader.HeaderType.PAGE -> {
                    channelJson.getObject(CONTENT)
                        ?.getObject(PAGE_HEADER_VIEW_MODEL)
                        ?.getObject(TITLE)
                        ?.getObject("dynamicTextViewModel")
                        ?.getObject("text")
                        ?.getString(CONTENT)
                        ?: channelJson.getString("pageTitle")
                }
                ChannelHeader.HeaderType.CAROUSEL,
                ChannelHeader.HeaderType.INTERACTIVE_TABBED -> getTextFromObject(channelJson.getObject(TITLE))
                ChannelHeader.HeaderType.C4_TABBED -> channelJson.getString(TITLE)
            }
        }

        if (!isNullOrEmpty(headerName)) return headerName!!

        val microformatTitle = jsonResponse.getObject("microformat")
            ?.getObject("microformatDataRenderer")
            ?.getString(TITLE)

        if (!isNullOrEmpty(microformatTitle)) return microformatTitle!!

        throw ParsingException("Could not get channel name")
    }

    @JvmStatic
    fun getChannelAgeGateRenderer(jsonResponse: JsonObject): JsonObject? {
        return jsonResponse.getObject(CONTENTS)
            ?.getObject("twoColumnBrowseResultsRenderer")
            ?.getArray("tabs")
            ?.mapNotNull { it as? JsonObject }
            ?.flatMap { tab ->
                tab.getObject(TAB_RENDERER)
                    ?.getObject(CONTENT)
                    ?.getObject("sectionListRenderer")
                    ?.getArray(CONTENTS)
                    ?.mapNotNull { it as? JsonObject } ?: emptyList()
            }
            ?.firstOrNull { it.containsKey("channelAgeGateRenderer") }
            ?.getObject("channelAgeGateRenderer")
    }
}
