package org.schabi.newpipe.extractor.services.youtube.extractors

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MultiInfoItemsCollector
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.localization.TimeAgoParser
import org.schabi.newpipe.extractor.services.youtube.YoutubeChannelHelper
import org.schabi.newpipe.extractor.services.youtube.YoutubeChannelHelper.getChannelResponse
import org.schabi.newpipe.extractor.services.youtube.YoutubeChannelHelper.resolveChannelId
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.DISABLE_PRETTY_PRINT_PARAMETER
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.YOUTUBEI_V1_URL
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getJsonPostResponse
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.prepareDesktopJsonBuilder
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeChannelTabLinkHandlerFactory
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Optional

/**
 * A [ChannelTabExtractor] implementation for the YouTube service.
 *
 * It currently supports `Videos`, `Shorts`, `Live`, `Playlists`,
 * `Albums` and `Channels` tabs.
 */
open class YoutubeChannelTabExtractor(
    service: StreamingService,
    linkHandler: ListLinkHandler
) : ChannelTabExtractor(service, linkHandler) {

    protected var channelHeader: YoutubeChannelHelper.ChannelHeader? = null

    private var jsonResponse: JsonObject? = null
    private var channelId: String? = null

    private fun getChannelTabsParameters(): String {
        return when (getName()) {
            ChannelTabs.VIDEOS -> "EgZ2aWRlb3PyBgQKAjoA"
            ChannelTabs.SHORTS -> "EgZzaG9ydHPyBgUKA5oBAA%3D%3D"
            ChannelTabs.LIVESTREAMS -> "EgdzdHJlYW1z8gYECgJ6AA%3D%3D"
            ChannelTabs.ALBUMS -> "EghyZWxlYXNlc_IGBQoDsgEA"
            ChannelTabs.PLAYLISTS -> "EglwbGF5bGlzdHPyBgQKAkIA"
            else -> throw ParsingException("Unsupported channel tab: ${getName()}")
        }
    }

    @Throws(IOException::class, ExtractionException::class)
    override fun onFetchPage(downloader: Downloader) {
        val channelIdFromId = resolveChannelId(super.getId())
        val params = getChannelTabsParameters()

        val data = getChannelResponse(
            channelIdFromId,
            params, getExtractorLocalization(), getExtractorContentCountry()
        )

        jsonResponse = data.jsonResponse
        channelHeader = YoutubeChannelHelper.getChannelHeader(jsonResponse!!)
        channelId = data.channelId
    }

    override fun getUrl(): String {
        try {
            return YoutubeChannelTabLinkHandlerFactory.getInstance()
                .getUrl("channel/" + getId(), listOf(getName()), "")
        } catch (e: ParsingException) {
            return super.getUrl()
        }
    }

    override fun getId(): String {
        return YoutubeChannelHelper.getChannelId(channelHeader, jsonResponse!!, channelId)
    }

    @Throws(ParsingException::class)
    protected open fun getChannelName(): String {
        return YoutubeChannelHelper.getChannelName(
            channelHeader,
            YoutubeChannelHelper.getChannelAgeGateRenderer(jsonResponse!!),
            jsonResponse!!
        )
    }

    @Throws(IOException::class, ExtractionException::class)
    override fun getInitialPage(): InfoItemsPage<InfoItem> {
        val collector = MultiInfoItemsCollector(getServiceId())

        var items: JsonArray = JsonArray(emptyList())
        val tab = getTabData()

        if (tab.isPresent) {
            val tabContent = tab.get().getObject("content")

            items = tabContent!!.getObject("sectionListRenderer")!!
                .getArray("contents")!!
                .getObject(0)!!
                .getObject("itemSectionRenderer")!!
                .getArray("contents")!!
                .getObject(0)!!
                .getObject("gridRenderer")!!
                .getArray("items")!!

            if (items.isEmpty()) {
                items = tabContent.getObject("richGridRenderer")!!
                    .getArray("contents")!!

                if (items.isEmpty()) {
                    items = tabContent.getObject("sectionListRenderer")!!
                        .getArray("contents")!!
                }
            }
        }

        val verifiedStatus: VerifiedStatus = if (channelHeader == null) {
            VerifiedStatus.UNKNOWN
        } else {
            if (YoutubeChannelHelper.isChannelVerified(channelHeader!!))
                VerifiedStatus.VERIFIED else VerifiedStatus.UNVERIFIED
        }

        val channelName = getChannelName()
        val channelUrl = getUrl()

        val continuation = collectItemsFrom(
            collector, items, verifiedStatus,
            channelName, channelUrl
        ).orElse(null)

        val nextPage = getNextPageFrom(
            continuation, listOf(channelName, channelUrl, verifiedStatus.toString())
        )

        return InfoItemsPage(collector, nextPage)
    }

    @Throws(IOException::class, ExtractionException::class)
    override fun getPage(page: Page): InfoItemsPage<InfoItem> {
        if (page == null || isNullOrEmpty(page.url)) {
            throw IllegalArgumentException("Page doesn't contain an URL")
        }

        val channelIds = page.ids
        val collector = MultiInfoItemsCollector(getServiceId())

        val ajaxJson = getJsonPostResponse("browse", page.body, getExtractorLocalization())

        val sectionListContinuation = ajaxJson.getArray("onResponseReceivedActions")!!
            .filterIsInstance<JsonObject>()
            .filter { it.containsKey("appendContinuationItemsAction") }
            .map { it.getObject("appendContinuationItemsAction")!! }
            .firstOrNull() ?: JsonObject(emptyMap())

        val continuation = collectItemsFrom(
            collector,
            sectionListContinuation.getArray("continuationItems"), channelIds
        ).orElse(null)

        return InfoItemsPage(collector, getNextPageFrom(continuation, channelIds))
    }

    internal open fun getTabData(): Optional<JsonObject> {
        val urlSuffix = YoutubeChannelTabLinkHandlerFactory.getUrlSuffix(getName())

        return jsonResponse!!.getObject("contents")!!
            .getObject("twoColumnBrowseResultsRenderer")!!
            .getArray("tabs")!!
            .filterIsInstance<JsonObject>()
            .filter { it.containsKey("tabRenderer") }
            .map { it.getObject("tabRenderer")!! }
            .filter { tabRenderer ->
                tabRenderer.getObject("endpoint")!!
                    .getObject("commandMetadata")!!.getObject("webCommandMetadata")!!
                    .getString("url", "")!!.endsWith(urlSuffix)
            }
            .firstOrNull()
            ?.let { Optional.of(it) }
            ?.filter { tabRenderer ->
                val tabContents = tabRenderer.getObject("content")!!
                    .getObject("sectionListRenderer")!!
                    .getArray("contents")!!
                    .getObject(0)!!
                    .getObject("itemSectionRenderer")!!
                    .getArray("contents")!!
                tabContents.size != 1 || !tabContents.getObject(0)!!.containsKey("messageRenderer")
            } ?: Optional.empty()
    }

    private fun collectItemsFrom(
        collector: MultiInfoItemsCollector,
        items: JsonArray?,
        channelIds: List<String>
    ): Optional<JsonObject> {
        val channelName: String?
        val channelUrl: String?
        val verifiedStatus: VerifiedStatus

        if (channelIds.size >= 3) {
            channelName = channelIds[0]
            channelUrl = channelIds[1]
            verifiedStatus = try {
                VerifiedStatus.valueOf(channelIds[2])
            } catch (e: IllegalArgumentException) {
                VerifiedStatus.UNKNOWN
            }
        } else {
            channelName = null
            channelUrl = null
            verifiedStatus = VerifiedStatus.UNKNOWN
        }

        return collectItemsFrom(collector, items, verifiedStatus, channelName, channelUrl)
    }

    private fun collectItemsFrom(
        collector: MultiInfoItemsCollector,
        items: JsonArray?,
        verifiedStatus: VerifiedStatus,
        channelName: String?,
        channelUrl: String?
    ): Optional<JsonObject> {
        if (items == null) return Optional.empty()
        return items.filterIsInstance<JsonObject>()
            .map { item -> collectItem(collector, item, verifiedStatus, channelName, channelUrl) }
            .reduce { c1: Optional<JsonObject>, c2: Optional<JsonObject> -> if (c1.isPresent) c1 else c2 }
            .let { if (it != null) it else Optional.empty() }
    }

    private fun collectItem(
        collector: MultiInfoItemsCollector,
        item: JsonObject,
        channelVerifiedStatus: VerifiedStatus,
        channelName: String?,
        channelUrl: String?
    ): Optional<JsonObject> {
        val timeAgoParser: TimeAgoParser = getTimeAgoParser()

        if (item.containsKey("richItemRenderer")) {
            val richItem = item.getObject("richItemRenderer")!!.getObject("content")!!

            when {
                richItem.containsKey("videoRenderer") ->
                    commitVideo(collector, timeAgoParser, richItem.getObject("videoRenderer")!!,
                        channelVerifiedStatus, channelName, channelUrl)
                richItem.containsKey("reelItemRenderer") ->
                    commitReel(collector, richItem.getObject("reelItemRenderer")!!,
                        channelVerifiedStatus, channelName, channelUrl)
                richItem.containsKey("shortsLockupViewModel") ->
                    commitShortsLockup(collector, richItem.getObject("shortsLockupViewModel")!!,
                        channelVerifiedStatus, channelName, channelUrl)
                richItem.containsKey("playlistRenderer") ->
                    commitPlaylist(collector, richItem.getObject("playlistRenderer")!!,
                        channelVerifiedStatus, channelName, channelUrl)
                richItem.containsKey("lockupViewModel") ->
                    commitLockup(collector, channelVerifiedStatus, channelName, channelUrl,
                        timeAgoParser, richItem)
            }
        } else if (item.containsKey("gridVideoRenderer")) {
            commitVideo(collector, timeAgoParser, item.getObject("gridVideoRenderer")!!,
                channelVerifiedStatus, channelName, channelUrl)
        } else if (item.containsKey("gridPlaylistRenderer")) {
            commitPlaylist(collector, item.getObject("gridPlaylistRenderer")!!,
                channelVerifiedStatus, channelName, channelUrl)
        } else if (item.containsKey("gridShowRenderer")) {
            collector.commit(
                YoutubeGridShowRendererChannelInfoItemExtractor(
                    item.getObject("gridShowRenderer")!!, channelVerifiedStatus, channelName,
                    channelUrl
                )
            )
        } else if (item.containsKey("shelfRenderer")) {
            return collectItem(
                collector, item.getObject("shelfRenderer")!!.getObject("content")!!,
                channelVerifiedStatus, channelName, channelUrl
            )
        } else if (item.containsKey("itemSectionRenderer")) {
            return collectItemsFrom(
                collector, item.getObject("itemSectionRenderer")!!.getArray("contents"),
                channelVerifiedStatus, channelName, channelUrl
            )
        } else if (item.containsKey("horizontalListRenderer")) {
            return collectItemsFrom(
                collector, item.getObject("horizontalListRenderer")!!.getArray("items"),
                channelVerifiedStatus, channelName, channelUrl
            )
        } else if (item.containsKey("expandedShelfContentsRenderer")) {
            return collectItemsFrom(
                collector, item.getObject("expandedShelfContentsRenderer")!!.getArray("items"),
                channelVerifiedStatus, channelName, channelUrl
            )
        } else if (item.containsKey("lockupViewModel")) {
            commitLockup(collector, channelVerifiedStatus, channelName, channelUrl, timeAgoParser, item)
        } else if (item.containsKey("continuationItemRenderer")) {
            return Optional.ofNullable(item.getObject("continuationItemRenderer"))
        }

        return Optional.empty()
    }

    private fun commitLockup(
        collector: MultiInfoItemsCollector,
        channelVerifiedStatus: VerifiedStatus,
        channelName: String?,
        channelUrl: String?,
        timeAgoParser: TimeAgoParser,
        richItem: JsonObject
    ) {
        val lockupViewModel = richItem.getObject("lockupViewModel")!!
        val contentType = lockupViewModel.getString("contentType")
        if ("LOCKUP_CONTENT_TYPE_PLAYLIST" == contentType ||
            "LOCKUP_CONTENT_TYPE_PODCAST" == contentType
        ) {
            commitPlaylistLockup(collector, lockupViewModel, channelVerifiedStatus, channelName, channelUrl)
        } else if ("LOCKUP_CONTENT_TYPE_VIDEO" == contentType) {
            commitVideoLockup(collector, timeAgoParser, lockupViewModel, channelVerifiedStatus, channelName, channelUrl)
        }
    }

    private fun commitReel(
        collector: MultiInfoItemsCollector,
        reelItemRenderer: JsonObject,
        channelVerifiedStatus: VerifiedStatus,
        channelName: String?,
        channelUrl: String?
    ) {
        collector.commit(
            object : YoutubeReelInfoItemExtractor(reelItemRenderer) {
                override fun getUploaderName(): String {
                    return if (isNullOrEmpty(channelName)) super.getUploaderName() else channelName!!
                }

                override fun getUploaderUrl(): String {
                    return if (isNullOrEmpty(channelUrl)) super.getUploaderName() else channelUrl!!
                }

                override fun isUploaderVerified(): Boolean {
                    return channelVerifiedStatus == VerifiedStatus.VERIFIED
                }
            }
        )
    }

    private fun commitShortsLockup(
        collector: MultiInfoItemsCollector,
        shortsLockupViewModel: JsonObject,
        channelVerifiedStatus: VerifiedStatus,
        channelName: String?,
        channelUrl: String?
    ) {
        collector.commit(
            object : YoutubeShortsLockupInfoItemExtractor(shortsLockupViewModel) {
                override fun getUploaderName(): String {
                    return if (isNullOrEmpty(channelName)) super.getUploaderName() else channelName!!
                }

                override fun getUploaderUrl(): String {
                    return if (isNullOrEmpty(channelUrl)) super.getUploaderName() else channelUrl!!
                }

                override fun isUploaderVerified(): Boolean {
                    return channelVerifiedStatus == VerifiedStatus.VERIFIED
                }
            }
        )
    }

    private fun commitVideoLockup(
        collector: MultiInfoItemsCollector,
        timeAgoParser: TimeAgoParser,
        lockupViewModel: JsonObject,
        channelVerifiedStatus: VerifiedStatus,
        channelName: String?,
        channelUrl: String?
    ) {
        collector.commit(
            object : YoutubeStreamInfoItemLockupExtractor(lockupViewModel, timeAgoParser) {
                override fun isChannelOrCoursePlaylistLockupItem(): Boolean = true

                override fun getUploaderAvatars(): List<Image> = emptyList()

                override fun getUploaderName(): String {
                    return if (isNullOrEmpty(channelName)) super.getUploaderName() else channelName!!
                }

                override fun getUploaderUrl(): String {
                    return if (isNullOrEmpty(channelUrl)) super.getUploaderName() else channelUrl!!
                }

                override fun isUploaderVerified(): Boolean {
                    return channelVerifiedStatus == VerifiedStatus.VERIFIED
                }
            }
        )
    }

    private fun commitPlaylistLockup(
        collector: MultiInfoItemsCollector,
        playlistLockupViewModel: JsonObject,
        channelVerifiedStatus: VerifiedStatus,
        channelName: String?,
        channelUrl: String?
    ) {
        collector.commit(
            object : YoutubeMixOrPlaylistLockupInfoItemExtractor(playlistLockupViewModel) {
                override fun getUploaderName(): String {
                    return if (isNullOrEmpty(channelName)) super.getUploaderName() else channelName!!
                }

                override fun getUploaderUrl(): String {
                    return if (isNullOrEmpty(channelUrl)) super.getUploaderName() else channelUrl!!
                }

                @Throws(ParsingException::class)
                override fun isUploaderVerified(): Boolean {
                    return when (channelVerifiedStatus) {
                        VerifiedStatus.VERIFIED -> true
                        VerifiedStatus.UNVERIFIED -> false
                        else -> super.isUploaderVerified()
                    }
                }
            }
        )
    }

    private fun commitVideo(
        collector: MultiInfoItemsCollector,
        timeAgoParser: TimeAgoParser,
        jsonObject: JsonObject,
        channelVerifiedStatus: VerifiedStatus,
        channelName: String?,
        channelUrl: String?
    ) {
        collector.commit(
            object : YoutubeStreamInfoItemExtractor(jsonObject, timeAgoParser) {
                override fun getUploaderName(): String {
                    return if (isNullOrEmpty(channelName)) super.getUploaderName() else channelName!!
                }

                override fun getUploaderUrl(): String {
                    return if (isNullOrEmpty(channelUrl)) super.getUploaderName() else channelUrl!!
                }

                @Throws(ParsingException::class)
                override fun isUploaderVerified(): Boolean {
                    return when (channelVerifiedStatus) {
                        VerifiedStatus.VERIFIED -> true
                        VerifiedStatus.UNVERIFIED -> false
                        else -> super.isUploaderVerified()
                    }
                }
            }
        )
    }

    private fun commitPlaylist(
        collector: MultiInfoItemsCollector,
        jsonObject: JsonObject,
        channelVerifiedStatus: VerifiedStatus,
        channelName: String?,
        channelUrl: String?
    ) {
        collector.commit(
            object : YoutubePlaylistInfoItemExtractor(jsonObject) {
                override fun getUploaderName(): String {
                    return if (isNullOrEmpty(channelName)) super.getUploaderName() else channelName!!
                }

                override fun getUploaderUrl(): String {
                    return if (isNullOrEmpty(channelUrl)) super.getUploaderName() else channelUrl!!
                }

                @Throws(ParsingException::class)
                override fun isUploaderVerified(): Boolean {
                    return when (channelVerifiedStatus) {
                        VerifiedStatus.VERIFIED -> true
                        VerifiedStatus.UNVERIFIED -> false
                        else -> super.isUploaderVerified()
                    }
                }
            }
        )
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun getNextPageFrom(
        continuations: JsonObject?,
        channelIds: List<String>
    ): Page? {
        if (isNullOrEmpty(continuations)) {
            return null
        }

        val continuationEndpoint = continuations!!.getObject("continuationEndpoint")!!
        val continuation = continuationEndpoint.getObject("continuationCommand")!!
            .getString("token")!!

        val body = prepareDesktopJsonBuilder(getExtractorLocalization(), getExtractorContentCountry())
            .value("continuation", continuation)
            .done().toString()
            .toByteArray(StandardCharsets.UTF_8)

        return Page("$YOUTUBEI_V1_URL${"browse"}?$DISABLE_PRETTY_PRINT_PARAMETER", null,
            channelIds, null, body)
    }

    /**
     * A [YoutubeChannelTabExtractor] for the `Videos` tab, if it has been already
     * fetched.
     */
    class VideosTabExtractor(
        service: StreamingService,
        linkHandler: ListLinkHandler,
        private val tabRenderer: JsonObject,
        channelHeader: YoutubeChannelHelper.ChannelHeader?,
        private val channelNameStr: String,
        private val channelIdStr: String,
        private val channelUrlStr: String
    ) : YoutubeChannelTabExtractor(service, linkHandler) {

        init {
            this.channelHeader = channelHeader
        }

        override fun onFetchPage(downloader: Downloader) {
            // Nothing to do, the initial data was already fetched and is stored in the link handler
        }

        override fun getId(): String = channelIdStr

        override fun getUrl(): String = channelUrlStr

        override fun getChannelName(): String = channelNameStr

        override fun getTabData(): Optional<JsonObject> = Optional.of(tabRenderer)
    }

    /**
     * Enum representing the verified state of a channel
     */
    private enum class VerifiedStatus {
        VERIFIED,
        UNVERIFIED,
        UNKNOWN
    }

    private class YoutubeGridShowRendererChannelInfoItemExtractor(
        gridShowRenderer: JsonObject,
        private val verifiedStatus: VerifiedStatus,
        private val channelName: String?,
        private val channelUrl: String?
    ) : YoutubeBaseShowInfoItemExtractor(gridShowRenderer) {

        override fun getUploaderName(): String? = channelName

        override fun getUploaderUrl(): String? = channelUrl

        @Throws(ParsingException::class)
        override fun isUploaderVerified(): Boolean {
            return when (verifiedStatus) {
                VerifiedStatus.VERIFIED -> true
                VerifiedStatus.UNVERIFIED -> false
                else -> throw ParsingException("Could not get uploader verification status")
            }
        }
    }
}
