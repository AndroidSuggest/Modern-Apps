package org.schabi.newpipe.extractor.services.youtube.extractors

import java.io.IOException
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
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.Companion.ALL
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.Companion.CHANNELS
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.Companion.PLAYLISTS
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.Companion.VIDEOS
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.Companion.getSearchParameter
import org.schabi.newpipe.extractor.utils.JsonUtils
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString
import org.schabi.newpipe.extractor.utils.orEmptyArray
import org.schabi.newpipe.extractor.utils.orEmptyObject

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

        val body = jsonBuilder.done().toString().toByteArray(Charsets.UTF_8)

        initialData = getJsonPostResponse("search", body, localization)
    }

    @Throws(ParsingException::class)
    override fun getUrl(): String {
        return super.getUrl() + "&gl=" + getExtractorContentCountry().countryCode
    }

    @Throws(ParsingException::class)
    override fun getSearchSuggestion(): String {
        val itemSectionRenderer = initialData!!.getObject("contents").orEmptyObject()
            .getObject("twoColumnSearchResultsRenderer").orEmptyObject()
            .getObject("primaryContents").orEmptyObject()
            .getObject("sectionListRenderer").orEmptyObject()
            .getArray("contents").orEmptyArray()
            .getObject(0).orEmptyObject()
            .getObject("itemSectionRenderer").orEmptyObject()
        val didYouMeanRenderer = itemSectionRenderer.getArray("contents").orEmptyArray()
            .getObject(0).orEmptyObject()
            .getObject("didYouMeanRenderer")

        if (didYouMeanRenderer != null && didYouMeanRenderer.isNotEmpty()) {
            return JsonUtils.getString(didYouMeanRenderer, "correctedQueryEndpoint.searchEndpoint.query")
        }

        return getTextFromObject(
            itemSectionRenderer.getArray("contents").orEmptyArray()
                .getObject(0).orEmptyObject()
                .getObject("showingResultsForRenderer").orEmptyObject()
                .getObject("correctedQuery")
        ) ?: ""
    }

    override fun isCorrectedSearch(): Boolean {
        val showingResultsForRenderer = initialData!!.getObject("contents").orEmptyObject()
            .getObject("twoColumnSearchResultsRenderer").orEmptyObject().getObject("primaryContents").orEmptyObject()
            .getObject("sectionListRenderer").orEmptyObject().getArray("contents").orEmptyArray().getObject(0).orEmptyObject()
            .getObject("itemSectionRenderer").orEmptyObject().getArray("contents").orEmptyArray().getObject(0).orEmptyObject()
            .getObject("showingResultsForRenderer")
        return showingResultsForRenderer != null && showingResultsForRenderer.isNotEmpty()
    }

    @Throws(ParsingException::class)
    override fun getMetaInfo(): List<MetaInfo> {
        return YoutubeMetaInfoHelper.getMetaInfo(
            initialData!!.getObject("contents").orEmptyObject()
                .getObject("twoColumnSearchResultsRenderer").orEmptyObject()
                .getObject("primaryContents").orEmptyObject()
                .getObject("sectionListRenderer").orEmptyObject()
                .getArray("contents").orEmptyArray()
        )
    }

    @Throws(IOException::class, ExtractionException::class)
    override fun getInitialPage(): InfoItemsPage<InfoItem> {
        val collector = MultiInfoItemsCollector(getServiceId())

        val sections = initialData!!.getObject("contents").orEmptyObject()
            .getObject("twoColumnSearchResultsRenderer").orEmptyObject()
            .getObject("primaryContents").orEmptyObject()
            .getObject("sectionListRenderer").orEmptyObject()
            .getArray("contents").orEmptyArray()

        var nextPage: Page? = null

        for (section in sections) {
            val sectionJsonObject = section as JsonObject
            if (sectionJsonObject.containsKey("itemSectionRenderer")) {
                val itemSectionRenderer = sectionJsonObject.getObject("itemSectionRenderer").orEmptyObject()

                collectStreamsFrom(collector, itemSectionRenderer.getArray("contents").orEmptyArray())
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

        val rawBody = prepareDesktopJsonBuilder(localization, getExtractorContentCountry())
            .value("continuation", page.id)
            .done().toString().toByteArray(Charsets.UTF_8)

        val ajaxJson = getJsonPostResponse("search", rawBody, localization)

        val continuationItems = ajaxJson.getArray("onResponseReceivedCommands").orEmptyArray()
            .getObject(0).orEmptyObject()
            .getObject("appendContinuationItemsAction").orEmptyObject()
            .getArray("continuationItems").orEmptyArray()

        val contents = continuationItems.getObject(0).orEmptyObject()
            .getObject("itemSectionRenderer").orEmptyObject()
            .getArray("contents").orEmptyArray()
        collectStreamsFrom(collector, contents)

        return InfoItemsPage(
            collector,
            getNextPageFrom(continuationItems.getObject(1).orEmptyObject().getObject("continuationItemRenderer"))
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
                    getTextFromObject(item.getObject("backgroundPromoRenderer").orEmptyObject().getObject("bodyText"))
                        ?: "Nothing found"
                )
                item.containsKey("videoRenderer") && extractVideoResults -> {
                    collector.commit(
                        YoutubeStreamInfoItemExtractor(
                            item.getObject("videoRenderer").orEmptyObject(), timeAgoParser
                        )
                    )
                }
                item.containsKey("channelRenderer") && extractChannelResults -> {
                    collector.commit(
                        YoutubeChannelInfoItemExtractor(
                            item.getObject("channelRenderer").orEmptyObject()
                        )
                    )
                }
                item.containsKey("playlistRenderer") && extractPlaylistResults -> {
                    collector.commit(
                        YoutubePlaylistInfoItemExtractor(
                            item.getObject("playlistRenderer").orEmptyObject()
                        )
                    )
                }
                item.containsKey("showRenderer") && extractPlaylistResults -> {
                    collector.commit(
                        YoutubeShowRendererInfoItemExtractor(
                            item.getObject("showRenderer").orEmptyObject()
                        )
                    )
                }
                item.containsKey("lockupViewModel") -> {
                    val lockupViewModel = item.getObject("lockupViewModel").orEmptyObject()
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

        val token = continuationItemRenderer.getObject("continuationEndpoint").orEmptyObject()
            .getObject("continuationCommand").orEmptyObject()
            .getString("token")
            ?: return null

        val url = YOUTUBEI_V1_URL + "search?" + DISABLE_PRETTY_PRINT_PARAMETER

        return Page(url, token)
    }
}
