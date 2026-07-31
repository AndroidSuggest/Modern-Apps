package org.schabi.newpipe.extractor.services.youtube.extractors.kiosk

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
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getJsonPostResponse
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getTextAtKey
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.prepareDesktopJsonBuilder
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamInfoItemExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getObject
import java.io.IOException
import java.nio.charset.StandardCharsets

class YoutubeTrendingExtractor(
    service: StreamingService,
    linkHandler: ListLinkHandler,
    kioskId: String
) : KioskExtractor<StreamInfoItem>(service, linkHandler, kioskId) {

    private var initialData: JsonObject? = null

    @Throws(IOException::class, ExtractionException::class)
    override fun onFetchPage(downloader: Downloader) {
        val body = prepareDesktopJsonBuilder(extractorLocalization, extractorContentCountry)
            .value("browseId", "FEtrending")
            .value("params", VIDEOS_TAB_PARAMS)
            .done().toString()
            .toByteArray(StandardCharsets.UTF_8)

        initialData = getJsonPostResponse("browse", body, extractorLocalization)
    }

    override fun getPage(page: Page): InfoItemsPage<StreamInfoItem> {
        return InfoItemsPage.emptyPage()
    }

    @Throws(ParsingException::class)
    override fun getName(): String {
        val header = initialData!!.getObject("header")!!
        var name: String? = null
        when {
            header.containsKey("feedTabbedHeaderRenderer") ->
                name = getTextAtKey(header.getObject("feedTabbedHeaderRenderer")!!, "title")
            header.containsKey("c4TabbedHeaderRenderer") ->
                name = getTextAtKey(header.getObject("c4TabbedHeaderRenderer")!!, "title")
            header.containsKey("pageHeaderRenderer") ->
                name = getTextAtKey(header.getObject("pageHeaderRenderer")!!, "pageTitle")
        }

        if (isNullOrEmpty(name)) {
            throw ParsingException("Could not get Trending name")
        }
        return name!!
    }

    @Throws(ParsingException::class)
    override fun getInitialPage(): InfoItemsPage<StreamInfoItem> {
        val collector = StreamInfoItemsCollector(serviceId)
        val timeAgoParser = getTimeAgoParser()
        val tab = getTrendingTab()
        val tabContent = tab.getObject("content")!!
        val isVideoTab = tab.getObject("endpoint")!!.getObject("browseEndpoint")!!
            .getString("params", "") == VIDEOS_TAB_PARAMS

        if (tabContent.containsKey("richGridRenderer")) {
            tabContent.getObject("richGridRenderer")!!
                .getArray("contents")!!
                .filterIsInstance<JsonObject>()
                .filter { it.containsKey("richItemRenderer") }
                .map { it.getObject("richItemRenderer")!!.getObject("content")!!.getObject("videoRenderer")!! }
                .forEach { videoRenderer ->
                    collector.commit(YoutubeStreamInfoItemExtractor(videoRenderer, timeAgoParser))
                }
        } else if (tabContent.containsKey("sectionListRenderer")) {
            val shelves = tabContent.getObject("sectionListRenderer")!!
                .getArray("contents")!!
                .filterIsInstance<JsonObject>()
                .flatMap { content ->
                    (content.getObject("itemSectionRenderer")!!.getArray("contents")!!)
                        .filterIsInstance<JsonObject>()
                }
                .map { it.getObject("shelfRenderer")!! }

            val items: List<JsonObject> = if (isVideoTab) {
                shelves.take(1)
            } else {
                shelves.filter { !it.containsKey("title") }
            }

            items.flatMap { shelfRenderer ->
                shelfRenderer.getObject("content")!!
                    .getObject("expandedShelfContentsRenderer")!!
                    .getArray("items")!!
                    .filterIsInstance<JsonObject>()
            }
                .map { it.getObject("videoRenderer")!! }
                .forEach { videoRenderer ->
                    collector.commit(YoutubeStreamInfoItemExtractor(videoRenderer, timeAgoParser))
                }
        }

        return InfoItemsPage(collector, null)
    }

    @Throws(ParsingException::class)
    private fun getTrendingTab(): JsonObject {
        return initialData!!.getObject("contents")!!
            .getObject("twoColumnBrowseResultsRenderer")!!
            .getArray("tabs")!!
            .filterIsInstance<JsonObject>()
            .map { it.getObject("tabRenderer")!! }
            .filter { it.getObject("selected")?.let { sel -> 
                // selected is boolean
                (sel as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBoolean() ?: false
            } ?: (it["selected"] as? kotlinx.serialization.json.JsonPrimitive)?.let { p -> 
                try { p.content.toBoolean() } catch(e:Exception){ false } 
            } ?: false }
            .filter { it.containsKey("content") }
            .firstOrNull() ?: throw ParsingException("Could not get \"Now\" or \"Videos\" trending tab")
    }

    companion object {
        const val KIOSK_ID = "Trending"
        private const val VIDEOS_TAB_PARAMS = "4gIOGgxtb3N0X3BvcHVsYXI%3D"
    }
}
