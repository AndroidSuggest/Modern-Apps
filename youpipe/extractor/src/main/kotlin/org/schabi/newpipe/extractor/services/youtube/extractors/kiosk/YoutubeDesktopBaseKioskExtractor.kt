package org.schabi.newpipe.extractor.services.youtube.extractors.kiosk

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.kiosk.KioskExtractor
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeChannelHelper
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.DISABLE_PRETTY_PRINT_PARAMETER
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.YOUTUBEI_V1_URL
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getClientVersion
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getJsonPostResponse
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.prepareJsonBuilder
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamInfoItemExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamInfoItemLockupExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString
import java.io.IOException
import java.nio.charset.StandardCharsets

abstract class YoutubeDesktopBaseKioskExtractor(
    streamingService: StreamingService,
    linkHandler: ListLinkHandler,
    kioskId: String,
    protected val browseId: String,
    protected val params: String
) : KioskExtractor<StreamInfoItem>(streamingService, linkHandler, kioskId) {

    protected var responseData: YoutubeChannelHelper.ChannelResponseData? = null

    @Throws(IOException::class, ExtractionException::class)
    override fun onFetchPage(downloader: Downloader) {
        responseData = YoutubeChannelHelper.getChannelResponse(
            browseId,
            params,
            getExtractorLocalization(),
            getExtractorContentCountry()
        )
    }

    @Throws(ParsingException::class)
    override fun getName(): String {
        return YoutubeChannelHelper.getChannelName(
            YoutubeChannelHelper.getChannelHeader(responseData!!.jsonResponse),
            null,
            responseData!!.jsonResponse
        )
    }

    @Throws(IOException::class, ExtractionException::class)
    override fun getInitialPage(): InfoItemsPage<StreamInfoItem> {
        val tabRendererContent = responseData!!.jsonResponse.getObject("contents")!!
            .getObject("twoColumnBrowseResultsRenderer")!!
            .getArray("tabs")!!
            .getObject(0)!!
            .getObject("tabRenderer")!!
            .getObject("content")!!

        val tabContents: JsonArray
        if (tabRendererContent.containsKey("sectionListRenderer")) {
            tabContents = tabRendererContent.getObject("sectionListRenderer")!!
                .getArray("contents")!!
                .getObject(0)!!
                .getObject("itemSectionRenderer")!!
                .getArray("contents")!!
                .getObject(0)!!
                .getObject("shelfRenderer")!!
                .getObject("content")!!
                .getObject("gridRenderer")!!
                .getArray("items")!!
        } else if (tabRendererContent.containsKey("richGridRenderer")) {
            tabContents = tabRendererContent.getObject("richGridRenderer")!!
                .getArray("contents")!!
        } else {
            tabContents = JsonArray(emptyList())
        }

        return collectStreamItems(
            tabContents,
            responseData!!.jsonResponse.getObject("responseContext")?.getString("visitorData")
        )
    }

    @Throws(IOException::class, ExtractionException::class)
    override fun getPage(page: Page): InfoItemsPage<StreamInfoItem> {
        if (page == null || page.body == null) {
            throw IllegalArgumentException("Page is null or doesn't contain a body")
        }

        val continuationResponse = getJsonPostResponse("browse", page.body, getExtractorLocalization())

        val continuationItems = continuationResponse.getArray("onResponseReceivedActions")!!
            .filterIsInstance<JsonObject>()
            .filter { it.containsKey("appendContinuationItemsAction") }
            .map { it.getObject("appendContinuationItemsAction")!! }
            .firstOrNull()?.getArray("continuationItems") ?: JsonArray(emptyList())

        return collectStreamItems(continuationItems, page.id)
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun collectStreamItems(
        items: JsonArray,
        visitorData: String?
    ): InfoItemsPage<StreamInfoItem> {
        val collector = StreamInfoItemsCollector(getServiceId())

        val nextPage: Page?
        if (items.isEmpty()) {
            nextPage = null
        } else {
            val timeAgoParser = getTimeAgoParser()
            items.filterIsInstance<JsonObject>().forEach { content ->
                when {
                    content.containsKey("richItemRenderer") -> {
                        val richItem = content.getObject("richItemRenderer")!!.getObject("content")!!
                        if (richItem.containsKey("videoRenderer")) {
                            collector.commit(
                                YoutubeStreamInfoItemExtractor(
                                    richItem.getObject("videoRenderer")!!, timeAgoParser
                                )
                            )
                        }
                    }
                    content.containsKey("gridVideoRenderer") -> {
                        collector.commit(
                            YoutubeStreamInfoItemExtractor(
                                content.getObject("gridVideoRenderer")!!, timeAgoParser
                            )
                        )
                    }
                    content.containsKey("lockupViewModel") -> {
                        val lockupViewModel = content.getObject("lockupViewModel")!!
                        if ("LOCKUP_CONTENT_TYPE_VIDEO" == lockupViewModel.getString("contentType")) {
                            collector.commit(
                                YoutubeStreamInfoItemLockupExtractor(lockupViewModel, timeAgoParser)
                            )
                        }
                    }
                }
            }

            val lastContent = items.getObject(items.size - 1)
            nextPage = if (lastContent != null && lastContent.containsKey("continuationItemRenderer")) {
                getNextPageFrom(lastContent.getObject("continuationItemRenderer"), visitorData)
            } else {
                null
            }
        }

        return InfoItemsPage(collector, nextPage)
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun getNextPageFrom(
        continuation: JsonObject?,
        visitorData: String?
    ): Page? {
        if (continuation == null || continuation.isEmpty()) {
            return null
        }

        val continuationEndpoint = continuation.getObject("continuationEndpoint")!!
        val continuationToken = continuationEndpoint.getObject("continuationCommand")!!
            .getString("token")!!

        val webClientRequestInfo = InnertubeClientRequestInfo.ofWebClient()
        webClientRequestInfo.clientInfo.clientVersion = getClientVersion()
        webClientRequestInfo.clientInfo.visitorData = visitorData

        val body = prepareJsonBuilder(
            getExtractorLocalization(),
            getExtractorContentCountry(),
            webClientRequestInfo,
            null
        )
            .value("continuation", continuationToken)
            .done().toString()
            .toByteArray(StandardCharsets.UTF_8)

        return Page(YOUTUBEI_V1_URL + "browse?" + DISABLE_PRETTY_PRINT_PARAMETER, visitorData, null, null, body)
    }
}
