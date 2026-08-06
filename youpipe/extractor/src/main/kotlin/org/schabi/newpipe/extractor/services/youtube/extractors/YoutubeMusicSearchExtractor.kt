package org.schabi.newpipe.extractor.services.youtube.extractors

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MetaInfo
import org.schabi.newpipe.extractor.MultiInfoItemsCollector
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.DISABLE_PRETTY_PRINT_PARAMETER
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getTextFromObject
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getValidJsonResponseBody
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getYoutubeMusicClientVersion
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getYoutubeMusicHeaders
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.Companion.MUSIC_ALBUMS
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.Companion.MUSIC_ARTISTS
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.Companion.MUSIC_PLAYLISTS
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.Companion.MUSIC_SONGS
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.Companion.MUSIC_VIDEOS
import org.schabi.newpipe.extractor.utils.JsonUtils
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString
import org.schabi.newpipe.extractor.utils.orEmptyArray
import org.schabi.newpipe.extractor.utils.orEmptyObject

class YoutubeMusicSearchExtractor(
    service: StreamingService,
    linkHandler: SearchQueryHandler
) : SearchExtractor(service, linkHandler) {

    private var initialData: JsonObject? = null
    private var cachedItemSectionRendererContents: List<JsonObject>? = null

    @Throws(IOException::class, ExtractionException::class)
    override fun onFetchPage(downloader: Downloader) {
        val url = "https://music.youtube.com/youtubei/v1/search?$DISABLE_PRETTY_PRINT_PARAMETER"

        val params: String?

        when (linkHandler.contentFilters[0]) {
            MUSIC_SONGS -> params = "Eg-KAQwIARAAGAAgACgAMABqChAEEAUQAxAKEAk%3D"
            MUSIC_VIDEOS -> params = "Eg-KAQwIABABGAAgACgAMABqChAEEAUQAxAKEAk%3D"
            MUSIC_ALBUMS -> params = "Eg-KAQwIABAAGAEgACgAMABqChAEEAUQAxAKEAk%3D"
            MUSIC_PLAYLISTS -> params = "Eg-KAQwIABAAGAAgACgBMABqChAEEAUQAxAKEAk%3D"
            MUSIC_ARTISTS -> params = "Eg-KAQwIABAAGAAgASgAMABqChAEEAUQAxAKEAk%3D"
            else -> params = null
        }

        val jsonBody = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", getYoutubeMusicClientVersion())
                    put("hl", "en-GB")
                    put("gl", getExtractorContentCountry().countryCode)
                    put("platform", "DESKTOP")
                    put("utcOffsetMinutes", 0)
                }
                putJsonObject("request") {
                    putJsonArray("internalExperimentFlags") {}
                    put("useSsl", true)
                }
                putJsonObject("user") {
                    put("lockedSafetyMode", false)
                }
            }
            put("query", getSearchString())
            if (params != null) {
                put("params", params)
            }
        }

        val json = jsonBody.toString().toByteArray(Charsets.UTF_8)

        val responseBody = getValidJsonResponseBody(
            downloader.postWithContentTypeJson(url, getYoutubeMusicHeaders(), json)
        )

        try {
            initialData = JsonUtils.toJsonObject(responseBody)
        } catch (e: Exception) {
            throw ParsingException("Could not parse JSON", e)
        }
    }

    private fun getItemSectionRendererContents(): List<JsonObject> {
        cachedItemSectionRendererContents?.let {
            return it
        }

        val contents = initialData!!
            .getObject("contents").orEmptyObject()
            .getObject("tabbedSearchResultsRenderer").orEmptyObject()
            .getArray("tabs").orEmptyArray()
            .getObject(0).orEmptyObject()
            .getObject("tabRenderer").orEmptyObject()
            .getObject("content").orEmptyObject()
            .getObject("sectionListRenderer").orEmptyObject()
            .getArray("contents").orEmptyArray()

        cachedItemSectionRendererContents = contents
            .filterIsInstance<JsonObject>()
            .map { c -> c.getObject("itemSectionRenderer") }
            .filter { it != null && it.isNotEmpty() }
            .map { it!!.getArray("contents").orEmptyArray().getObject(0).orEmptyObject() }
            .toList()
        return cachedItemSectionRendererContents!!
    }

    @Throws(ParsingException::class)
    override fun getSearchSuggestion(): String {
        for (obj in getItemSectionRendererContents()) {
            val didYouMeanRenderer = obj.getObject("didYouMeanRenderer")
            if (didYouMeanRenderer != null && didYouMeanRenderer.isNotEmpty()) {
                return getTextFromObject(didYouMeanRenderer.getObject("correctedQuery")) ?: ""
            }
            val showingResultsForRenderer = obj.getObject("showingResultsForRenderer")
            if (showingResultsForRenderer != null && showingResultsForRenderer.isNotEmpty()) {
                return try {
                    JsonUtils.getString(showingResultsForRenderer, "correctedQueryEndpoint.searchEndpoint.query")
                } catch (e: Exception) {
                    ""
                }
            }
        }
        return ""
    }

    @Throws(ParsingException::class)
    override fun isCorrectedSearch(): Boolean {
        return getItemSectionRendererContents()
            .any { it.containsKey("showingResultsForRenderer") }
    }

    override fun getMetaInfo(): List<MetaInfo> = emptyList()

    @Throws(IOException::class, ExtractionException::class)
    override fun getInitialPage(): InfoItemsPage<InfoItem> {
        val collector = MultiInfoItemsCollector(getServiceId())

        val contents = JsonUtils.getArray(
            JsonUtils.getArray(initialData!!, "contents.tabbedSearchResultsRenderer.tabs").getObject(0).orEmptyObject(),
            "tabRenderer.content.sectionListRenderer.contents"
        )

        var nextPage: Page? = null

        for (content in contents) {
            val contentObj = content as JsonObject
            if (contentObj.containsKey("musicShelfRenderer")) {
                val musicShelfRenderer = contentObj.getObject("musicShelfRenderer").orEmptyObject()

                collectMusicStreamsFrom(collector, musicShelfRenderer.getArray("contents").orEmptyArray())

                nextPage = getNextPageFrom(musicShelfRenderer.getArray("continuations"))
            }
        }

        return InfoItemsPage(collector, nextPage)
    }

    @Throws(IOException::class, ExtractionException::class)
    override fun getPage(page: Page): InfoItemsPage<InfoItem> {
        if (isNullOrEmpty(page.url)) {
            throw IllegalArgumentException("Page doesn't contain an URL")
        }

        val collector = MultiInfoItemsCollector(getServiceId())

        val jsonBody = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", getYoutubeMusicClientVersion())
                    put("hl", "en-GB")
                    put("gl", getExtractorContentCountry().countryCode)
                    put("platform", "DESKTOP")
                    put("utcOffsetMinutes", 0)
                }
                putJsonObject("request") {
                    putJsonArray("internalExperimentFlags") {}
                    put("useSsl", true)
                }
                putJsonObject("user") {
                    put("lockedSafetyMode", false)
                }
            }
        }

        val responseBody = getValidJsonResponseBody(
            downloader.postWithContentTypeJson(
                page.url, getYoutubeMusicHeaders(), jsonBody.toString().toByteArray(Charsets.UTF_8)
            )
        )

        val ajaxJson: JsonObject
        try {
            ajaxJson = JsonUtils.toJsonObject(responseBody)
        } catch (e: Exception) {
            throw ParsingException("Could not parse JSON", e)
        }

        val musicShelfContinuation = ajaxJson.getObject("continuationContents").orEmptyObject()
            .getObject("musicShelfContinuation").orEmptyObject()

        collectMusicStreamsFrom(collector, musicShelfContinuation.getArray("contents").orEmptyArray())
        val continuations = musicShelfContinuation.getArray("continuations")

        return InfoItemsPage(collector, getNextPageFrom(continuations))
    }

    private fun collectMusicStreamsFrom(
        collector: MultiInfoItemsCollector,
        videos: JsonArray
    ) {
        val searchType = linkHandler.contentFilters[0]
        videos.filterIsInstance<JsonObject>()
            .map { it.getObject("musicResponsiveListItemRenderer") }
            .filter { it != null }
            .forEach { infoItem ->
                val displayPolicy = infoItem!!.getString("musicItemRendererDisplayPolicy", "")
                if (displayPolicy == "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT") {
                    return@forEach
                }

                val descriptionElements = infoItem.getArray("flexColumns").orEmptyArray()
                    .getObject(1).orEmptyObject()
                    .getObject("musicResponsiveListItemFlexColumnRenderer").orEmptyObject()
                    .getObject("text").orEmptyObject()
                    .getArray("runs").orEmptyArray()

                when (searchType) {
                    MUSIC_SONGS, MUSIC_VIDEOS ->
                        collector.commit(
                            YoutubeMusicSongOrVideoInfoItemExtractor(
                                infoItem, descriptionElements, searchType
                            )
                        )
                    MUSIC_ARTISTS ->
                        collector.commit(YoutubeMusicArtistInfoItemExtractor(infoItem))
                    MUSIC_ALBUMS, MUSIC_PLAYLISTS ->
                        collector.commit(
                            YoutubeMusicAlbumOrPlaylistInfoItemExtractor(
                                infoItem, descriptionElements, searchType
                            )
                        )
                }
            }
    }

    private fun getNextPageFrom(continuations: JsonArray?): Page? {
        if (continuations == null || continuations.isEmpty()) {
            return null
        }

        val nextContinuationData = continuations.getObject(0).orEmptyObject()
            .getObject("nextContinuationData").orEmptyObject()
        val continuation = nextContinuationData.getString("continuation")
            ?: return null

        return Page(
            "https://music.youtube.com/youtubei/v1/search?ctoken=$continuation&continuation=$continuation&$DISABLE_PRETTY_PRINT_PARAMETER"
        )
    }
}
