package org.schabi.newpipe.extractor.services.youtube.extractors

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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
import org.schabi.newpipe.extractor.services.youtube.YoutubeMetaInfoHelper
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.DISABLE_PRETTY_PRINT_PARAMETER
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.YOUTUBEI_V1_URL
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getJsonPostResponse
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getTextFromObject
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.prepareDesktopJsonBuilder
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.ALL
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.CHANNELS
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.PLAYLISTS
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.VIDEOS
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.getSearchParameter
import org.schabi.newpipe.extractor.utils.JsonUtils
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Objects

class YoutubeSearchExtractor(
    service: StreamingService,
    linkHandler: SearchQueryHandler
) : SearchExtractor(service, linkHandler) {

    private val searchType: String?
    private val extractVideoResults: Boolean
    private val extractChannelResults: Boolean
    private val extractPlaylistResults: Boolean

    private var initialData: JsonObject? = null

    init {
        val contentFilters = linkHandler.contentFilters
        searchType = if (isNullOrEmpty(contentFilters)) null else contentFilters[0]
        extractVideoResults = searchType == null || ALL == searchType || VIDEOS == searchType
        extractChannelResults = searchType == null || ALL == searchType || CHANNELS == searchType
        extractPlaylistResults = searchType == null || ALL == searchType || PLAYLISTS == searchType
    }

    @Throws(IOException::class, ExtractionException::class)
    override fun onFetchPage(downloader: Downloader) {
        val query = super.getSearchString()
        val localization = getExtractorLocalization()
        val params = getSearchParameter(searchType)

        val jsonBuilder = prepareDesktopJsonBuilder(localization, getExtractorContentCountry())
            .value("query", query)
        if (!isNullOrEmpty(params)) {
            jsonBuilder.value("params", params)
        }

        val body = jsonBuilder.done().toString().toByteArray(StandardCharsets.UTF_8)

        initialData = getJsonPostResponse("search", body, localization)
    }

    @Throws(ParsingException::class)
    override fun getUrl(): String {
        return super.getUrl() + "&gl=" + getExtractorContentCountry().countryCode
    }

    @Throws(ParsingException::class)
    override fun getSearchSuggestion(): String {
        val itemSectionRenderer = initialData!!.getObject("contents")!!
            .getObject("twoColumnSearchResultsRenderer")!!
            .getObject("primaryContents")!!
            .getObject("sectionListRenderer")!!
            .getArray("contents")!!
            .getObject(0)!!
            .getObject("itemSectionRenderer")!!
        val didYouMeanRenderer = itemSectionRenderer.getArray("contents")!!
            .getObject(0)!!
            .getObject("didYouMeanRenderer")

        if (didYouMeanRenderer != null && didYouMeanRenderer.isNotEmpty()) {
            return JsonUtils.getString(didYouMeanRenderer, "correctedQueryEndpoint.searchEndpoint.query")
        }

        return Objects.requireNonNullElse(
            getTextFromObject(
                itemSectionRenderer.getArray("contents")!!
                    .getObject(0)!!
                    .getObject("showingResultsForRenderer")!!
                    .getObject("correctedQuery")
            ), ""
        )
    }

    override fun isCorrectedSearch(): Boolean {
        val showingResultsForRenderer = initialData!!.getObject("contents")!!
            .getObject("twoColumnSearchResultsRenderer")!!.getObject("primaryContents")!!
            .getObject("sectionListRenderer")!!.getArray("contents")!!.getObject(0)!!
            .getObject("itemSectionRenderer")!!.getArray("contents")!!.getObject(0)!!
            .getObject("showingResultsForRenderer")
        return showingResultsForRenderer != null && showingResultsForRenderer.isNotEmpty()
    }

    @Throws(ParsingException::class)
    override fun getMetaInfo(): List<MetaInfo> {
        return YoutubeMetaInfoHelper.getMetaInfo(
            initialData!!.getObject("contents")!!
                .getObject("twoColumnSearchResultsRenderer")!!
                .getObject("primaryContents")!!
                .getObject("sectionListRenderer")!!
                .getArray("contents")!!
        )
    }

    @Throws(IOException::class, ExtractionException::class)
    override fun getInitialPage(): InfoItemsPage<InfoItem> {
        val collector = MultiInfoItemsCollector(getServiceId())

        val sections = initialData!!.getObject("contents")!!
            .getObject("twoColumnSearchResultsRenderer")!!
            .getObject("primaryContents")!!
            .getObject("sectionListRenderer")!!
            .getArray("contents")!!

        var nextPage: Page? = null

        for (section in sections) {
            val sectionJsonObject = section as JsonObject
            if (sectionJsonObject.containsKey("itemSectionRenderer")) {
                val itemSectionRenderer = sectionJsonObject.getObject("itemSectionRenderer")!!

                collectStreamsFrom(collector, itemSectionRenderer.getArray("contents")!!)
            } else if (sectionJsonObject.containsKey("continuationItemRenderer")) {
                nextPage = getNextPageFrom(
                    sectionJsonObject.getObject("continuationItemRenderer")
                )
            }
        }

        return InfoItemsPage(collector, nextPage)
    }

    @Throws(IOException::class, ExtractionException::class)
    override fun getPage(page: Page): InfoItemsPage<InfoItem> {
        if (page == null || isNullOrEmpty(page.url)) {
            throw IllegalArgumentException("Page doesn't contain an URL")
        }

        val localization = getExtractorLocalization()
        val collector = MultiInfoItemsCollector(getServiceId())

        val jsonBody = buildJsonObject {
            // prepareDesktopJsonBuilder equivalent - we need to build a proper JSON
            // The original Java used prepareDesktopJsonBuilder + value("continuation", token)
            // Use YoutubeParsingHelper's builder via JsonUtils for portability
            put("context", prepareDesktopJsonBuilder(localization, getExtractorContentCountry())
                .getObject("context") ?: buildJsonObject {})
            put("continuation", page.id)
        }

        // Actually we need full builder pattern, fallback to direct use of helper
        val rawBody = prepareDesktopJsonBuilder(localization, getExtractorContentCountry())
            .value("continuation", page.id)
            .done().toString().toByteArray(StandardCharsets.UTF_8)

        val ajaxJson = getJsonPostResponse("search", rawBody, localization)

        val continuationItems = ajaxJson.getArray("onResponseReceivedCommands")!!
            .getObject(0)!!
            .getObject("appendContinuationItemsAction")!!
            .getArray("continuationItems")!!

        val contents = continuationItems.getObject(0)!!
            .getObject("itemSectionRenderer")!!
            .getArray("contents")!!
        collectStreamsFrom(collector, contents)

        return InfoItemsPage(
            collector,
            getNextPageFrom(continuationItems.getObject(1)!!.getObject("continuationItemRenderer"))
        )
    }

    @Throws(ParsingException::class, NothingFoundException::class)
    private fun collectStreamsFrom(
        collector: MultiInfoItemsCollector,
        contents: JsonArray
    ) {
        val timeAgoParser = getTimeAgoParser()

        for (content in contents) {
            val item = content as JsonObject
            when {
                item.containsKey("backgroundPromoRenderer") -> throw NothingFoundException(
                    getTextFromObject(item.getObject("backgroundPromoRenderer")!!.getObject("bodyText"))
                )
                item.containsKey("videoRenderer") && extractVideoResults -> {
                    collector.commit(
                        YoutubeStreamInfoItemExtractor(
                            item.getObject("videoRenderer")!!, timeAgoParser
                        )
                    )
                }
                item.containsKey("channelRenderer") && extractChannelResults -> {
                    collector.commit(
                        YoutubeChannelInfoItemExtractor(
                            item.getObject("channelRenderer")!!
                        )
                    )
                }
                item.containsKey("playlistRenderer") && extractPlaylistResults -> {
                    collector.commit(
                        YoutubePlaylistInfoItemExtractor(
                            item.getObject("playlistRenderer")!!
                        )
                    )
                }
                item.containsKey("showRenderer") && extractPlaylistResults -> {
                    collector.commit(
                        YoutubeShowRendererInfoItemExtractor(
                            item.getObject("showRenderer")!!
                        )
                    )
                }
                item.containsKey("lockupViewModel") -> {
                    val lockupViewModel = item.getObject("lockupViewModel")!!
                    val contentType = lockupViewModel.getObject("contentType")?.let {
                        // contentType is stored as a primitive string in kotlinx.json compat layer
                        // Actually need to resolve - it returns String via getString
                        // The Java getString("contentType") returnsString
                        // Use extension getString
                        lockupViewModel.let { lvm -> 
                            (lvm["contentType"] as? kotlinx.serialization.json.JsonPrimitive)?.content 
                        }
                    } ?: lockupViewModel.getString("contentType") ?: ""
                    // Use helper to read
                    val ct = lockupViewModel.getString("contentType") ?: ""
                    if ((ct == "LOCKUP_CONTENT_TYPE_PLAYLIST" || ct == "LOCKUP_CONTENT_TYPE_PODCAST") && extractPlaylistResults) {
                        collector.commit(YoutubeMixOrPlaylistLockupInfoItemExtractor(lockupViewModel))
                    } else if (ct == "LOCKUP_CONTENT_TYPE_VIDEO" && extractVideoResults) {
                        collector.commit(YoutubeStreamInfoItemLockupExtractor(lockupViewModel, timeAgoParser))
                    }
                }
            }
        }
    }

    private fun getNextPageFrom(continuationItemRenderer: JsonObject?): Page? {
        if (continuationItemRenderer == null || continuationItemRenderer.isEmpty()) {
            return null
        }

        val token = continuationItemRenderer.getObject("continuationEndpoint")!!
            .getObject("continuationCommand")!!
            .getString("token")!!

        val url = YOUTUBEI_V1_URL + "search?" + DISABLE_PRETTY_PRINT_PARAMETER

        return Page(url, token)
    }
}
