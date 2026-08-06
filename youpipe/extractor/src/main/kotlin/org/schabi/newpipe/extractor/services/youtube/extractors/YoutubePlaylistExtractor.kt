package org.schabi.newpipe.extractor.services.youtube.extractors

import java.io.IOException
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.localization.TimeAgoParser
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.DISABLE_PRETTY_PRINT_PARAMETER
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.YOUTUBEI_V1_URL
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.extractPlaylistTypeFromPlaylistUrl
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getImagesFromThumbnailsArray
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getJsonPostResponse
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getTextFromObject
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getUrlFromNavigationEndpoint
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.prepareDesktopJsonBuilder
import org.schabi.newpipe.extractor.services.youtube.protos.playlist.PlaylistProtobufContinuation.ContinuationParams
import org.schabi.newpipe.extractor.services.youtube.protos.playlist.PlaylistProtobufContinuation.PlaylistContinuation
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector
import org.schabi.newpipe.extractor.utils.Utils
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString
import org.schabi.newpipe.extractor.utils.orEmptyArray
import org.schabi.newpipe.extractor.utils.orEmptyObject

class YoutubePlaylistExtractor(
    service: StreamingService,
    linkHandler: ListLinkHandler
) : PlaylistExtractor(service, linkHandler) {

    private var browseMetadataResponse: JsonObject? = null
    private var initialBrowseContinuationResponse: JsonObject? = null

    private var playlistInfo: JsonObject? = null
    private var uploaderInfo: JsonObject? = null
    private var playlistHeader: JsonObject? = null

    private var isNewPlaylistInterface: Boolean = false
    private var isCoursePlaylist: Boolean? = null

    @Throws(IOException::class, ExtractionException::class)
    override fun onFetchPage(downloader: Downloader) {
        val playlistId = getId()

        val localization = getExtractorLocalization()
        val body = prepareDesktopJsonBuilder(localization, getExtractorContentCountry())
            .value("browseId", "VL$playlistId")
            .value("params", "wgYCCAA%3D")
            .done().toString()
            .toByteArray(Charsets.UTF_8)

        browseMetadataResponse = getJsonPostResponse(
            BROWSE_ENDPOINT,
            listOf("\$fields=$SIDEBAR,$HEADER,$MICROFORMAT,alerts"),
            body,
            localization
        )

        YoutubeParsingHelper.defaultAlertsCheck(browseMetadataResponse!!)
        isNewPlaylistInterface = checkIfResponseIsNewPlaylistInterface()

        val playlistContinuation = PlaylistContinuation.newBuilder()
            .setParameters(
                ContinuationParams.newBuilder()
                    .setBrowseId("VL$playlistId")
                    .setPlaylistId(playlistId)
                    .setContinuationProperties(PLAYLIST_CONTINUATION_PROPERTIES_BASE64)
                    .build()
            )
            .build()

        initialBrowseContinuationResponse = getJsonPostResponse(
            BROWSE_ENDPOINT,
            prepareDesktopJsonBuilder(localization, getExtractorContentCountry())
                .value(
                    "continuation",
                    Utils.encodeUrlUtf8(
                        Base64.getUrlEncoder().encodeToString(playlistContinuation.toByteArray())
                    )
                )
                .done().toString()
                .toByteArray(Charsets.UTF_8),
            localization
        )
    }

    private fun checkIfResponseIsNewPlaylistInterface(): Boolean {
        return browseMetadataResponse!!.containsKey(HEADER) && !browseMetadataResponse!!.containsKey(SIDEBAR)
    }

    @Throws(ParsingException::class)
    private fun getUploaderInfoObj(): JsonObject {
        if (uploaderInfo == null) {
            uploaderInfo = browseMetadataResponse!!.getObject(SIDEBAR).orEmptyObject()
                .getObject("playlistSidebarRenderer").orEmptyObject()
                .getArray("items").orEmptyArray()
                .filterIsInstance<JsonObject>()
                .filter { item ->
                    item.getObject("playlistSidebarSecondaryInfoRenderer")
                        ?.getObject("videoOwner")
                        ?.containsKey(VIDEO_OWNER_RENDERER) == true
                }
                .map { item ->
                    item.getObject("playlistSidebarSecondaryInfoRenderer").orEmptyObject()
                        .getObject("videoOwner").orEmptyObject()
                        .getObject(VIDEO_OWNER_RENDERER).orEmptyObject()
                }
                .firstOrNull() ?: throw ParsingException("Could not get uploader info")
        }
        return uploaderInfo!!
    }

    @Throws(ParsingException::class)
    private fun getPlaylistInfoObj(): JsonObject {
        if (playlistInfo == null) {
            playlistInfo = browseMetadataResponse!!.getObject(SIDEBAR).orEmptyObject()
                .getObject("playlistSidebarRenderer").orEmptyObject()
                .getArray("items").orEmptyArray()
                .filterIsInstance<JsonObject>()
                .filter { it.containsKey("playlistSidebarPrimaryInfoRenderer") }
                .map { it.getObject("playlistSidebarPrimaryInfoRenderer").orEmptyObject() }
                .firstOrNull() ?: throw ParsingException("Could not get playlist info")
        }
        return playlistInfo!!
    }

    private fun getPlaylistHeaderObj(): JsonObject {
        if (playlistHeader == null) {
            playlistHeader = browseMetadataResponse!!.getObject(HEADER).orEmptyObject()
                .getObject("playlistHeaderRenderer").orEmptyObject()
        }
        return playlistHeader!!
    }

    private fun isCoursePlaylistCheck(): Boolean {
        if (isCoursePlaylist == null) {
            isCoursePlaylist = getPlaylistHeaderObj().getObject("onDescriptionTap").orEmptyObject()
                .getObject(COMMAND_EXECUTOR_COMMAND).orEmptyObject()
                .getArray("commands").orEmptyArray()
                .filterIsInstance<JsonObject>()
                .any { obj ->
                    "engagement-panel-course-metadata" == obj.getObject("showEngagementPanelEndpoint")
                        ?.getObject("identifier")
                        ?.getString("tag")
                }
        }
        return isCoursePlaylist!!
    }

    @Throws(ParsingException::class)
    override fun getName(): String {
        val name = getTextFromObject(getPlaylistInfoObj().getObject(TITLE))
        if (!isNullOrEmpty(name)) {
            return name
        }
        return browseMetadataResponse!!.getObject(MICROFORMAT).orEmptyObject()
            .getObject("microformatDataRenderer").orEmptyObject()
            .getString(TITLE)
            ?: throw ParsingException("Could not get playlist name")
    }

    @Throws(ParsingException::class)
    override fun getThumbnails(): List<Image> {
        val playlistMetadataThumbnailsArray: JsonArray? = if (isNewPlaylistInterface) {
            getPlaylistHeaderObj().getObject("playlistHeaderBanner").orEmptyObject()
                .getObject("heroPlaylistThumbnailRenderer").orEmptyObject()
                .getObject(THUMBNAIL).orEmptyObject()
                .getArray(THUMBNAILS)
        } else {
            playlistInfo!!.getObject("thumbnailRenderer").orEmptyObject()
                .getObject("playlistVideoThumbnailRenderer").orEmptyObject()
                .getObject(THUMBNAIL).orEmptyObject()
                .getArray(THUMBNAILS)
        }

        if (playlistMetadataThumbnailsArray != null && playlistMetadataThumbnailsArray.isNotEmpty()) {
            return getImagesFromThumbnailsArray(playlistMetadataThumbnailsArray)
        }

        val microFormatThumbnailsArray = browseMetadataResponse!!.getObject(MICROFORMAT).orEmptyObject()
            .getObject("microformatDataRenderer").orEmptyObject()
            .getObject(THUMBNAIL).orEmptyObject()
            .getArray(THUMBNAILS).orEmptyArray()

        if (microFormatThumbnailsArray.isNotEmpty()) {
            return getImagesFromThumbnailsArray(microFormatThumbnailsArray)
        }

        throw ParsingException("Could not get playlist thumbnails")
    }

    @Throws(ParsingException::class)
    override fun getUploaderUrl(): String? {
        try {
            return getUrlFromNavigationEndpoint(
                if (isNewPlaylistInterface) {
                    getPlaylistHeaderObj().getObject("ownerText").orEmptyObject()
                        .getArray("runs").orEmptyArray()
                        .getObject(0).orEmptyObject()
                        .getObject("navigationEndpoint").orEmptyObject()
                } else {
                    getUploaderInfoObj().getObject("navigationEndpoint").orEmptyObject()
                }
            ) ?: throw ParsingException("null url")
        } catch (e: Exception) {
            throw ParsingException("Could not get playlist uploader url", e)
        }
    }

    @Throws(ParsingException::class)
    override fun getUploaderName(): String? {
        try {
            return getTextFromObject(
                if (isNewPlaylistInterface) {
                    getPlaylistHeaderObj().getObject("ownerText")
                } else {
                    getUploaderInfoObj().getObject(TITLE)
                }
            )!!
        } catch (e: Exception) {
            throw ParsingException("Could not get playlist uploader name", e)
        }
    }

    @Throws(ParsingException::class)
    override fun getUploaderAvatars(): List<Image> {
        if (isNewPlaylistInterface) {
            return emptyList()
        }
        try {
            return getImagesFromThumbnailsArray(
                getUploaderInfoObj().getObject(THUMBNAIL).orEmptyObject()
                    .getArray(THUMBNAILS).orEmptyArray()
            )
        } catch (e: Exception) {
            throw ParsingException("Could not get playlist uploader avatars", e)
        }
    }

    @Throws(ParsingException::class)
    override fun isUploaderVerified(): Boolean = false

    @Throws(ParsingException::class)
    override fun getStreamCount(): Long {
        if (isNewPlaylistInterface) {
            val numVideosText = getTextFromObject(getPlaylistHeaderObj().getObject("numVideosText"))
            if (numVideosText != null) {
                try {
                    return java.lang.Long.parseLong(Utils.removeNonDigitCharacters(numVideosText))
                } catch (ignored: NumberFormatException) {
                }
            }

            val firstByLineRendererText = getTextFromObject(
                getPlaylistHeaderObj().getArray("byline").orEmptyArray()
                    .getObject(0).orEmptyObject()
                    .getObject("text")
            )

            if (firstByLineRendererText != null) {
                try {
                    return java.lang.Long.parseLong(Utils.removeNonDigitCharacters(firstByLineRendererText))
                } catch (ignored: NumberFormatException) {
                }
            }
        }

        val briefStats = (if (isNewPlaylistInterface) getPlaylistHeaderObj() else getPlaylistInfoObj())
            .getArray("briefStats")
        if (briefStats != null && briefStats.isNotEmpty()) {
            val briefsStatsText = getTextFromObject(briefStats.getObject(0))
            if (briefsStatsText != null) {
                return java.lang.Long.parseLong(Utils.removeNonDigitCharacters(briefsStatsText))
            }
        }

        val stats = (if (isNewPlaylistInterface) getPlaylistHeaderObj() else getPlaylistInfoObj())
            .getArray("stats")
        if (stats != null && stats.isNotEmpty()) {
            val statsText = getTextFromObject(stats.getObject(0))
            if (statsText != null) {
                return java.lang.Long.parseLong(Utils.removeNonDigitCharacters(statsText))
            }
        }

        return ITEM_COUNT_UNKNOWN
    }

    @Throws(ParsingException::class)
    override fun getDescription(): Description {
        val description = getTextFromObject(
            getPlaylistInfoObj().getObject("description"),
            true
        )
        return Description(description, Description.HTML)
    }

    @Throws(IOException::class, ExtractionException::class)
    override fun getInitialPage(): InfoItemsPage<StreamInfoItem> {
        val collector = StreamInfoItemsCollector(getServiceId())

        var initialItems = initialBrowseContinuationResponse!!
            .getArray(ON_RESPONSE_RECEIVED_ACTIONS).orEmptyArray()
            .getObject(0).orEmptyObject()
            .getObject("reloadContinuationItemsCommand").orEmptyObject()
            .getArray(CONTINUATION_ITEMS).orEmptyArray()

        if (initialItems.isEmpty()) {
            initialItems = initialBrowseContinuationResponse!!.getArray(ON_RESPONSE_RECEIVED_ACTIONS).orEmptyArray()
                .getObject(0).orEmptyObject()
                .getObject(APPEND_CONTINUATION_ITEMS_ACTION).orEmptyObject()
                .getArray(CONTINUATION_ITEMS).orEmptyArray()
        }

        collectStreamsFrom(collector, initialItems)

        return InfoItemsPage(collector, getNextPageFrom(initialItems))
    }

    @Throws(IOException::class, ExtractionException::class)
    override fun getPage(page: Page): InfoItemsPage<StreamInfoItem> {
        if (isNullOrEmpty(page.url)) {
            throw IllegalArgumentException("Page doesn't contain an URL")
        }

        val collector = StreamInfoItemsCollector(getServiceId())

        val ajaxJson = getJsonPostResponse(BROWSE_ENDPOINT, page.body!!, getExtractorLocalization())

        val continuation = ajaxJson.getArray(ON_RESPONSE_RECEIVED_ACTIONS).orEmptyArray()
            .getObject(0).orEmptyObject()
            .getObject(APPEND_CONTINUATION_ITEMS_ACTION).orEmptyObject()
            .getArray(CONTINUATION_ITEMS).orEmptyArray()

        collectStreamsFrom(collector, continuation)

        return InfoItemsPage(collector, getNextPageFrom(continuation))
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun getNextPageFrom(contents: JsonArray?): Page? {
        if (contents == null || contents.isEmpty()) {
            return null
        }

        val continuation: String?

        val lastElement = contents.getObject(contents.size - 1).orEmptyObject()
        if (lastElement.containsKey("continuationItemRenderer")) {
            val continuationEndpoint = lastElement
                .getObject("continuationItemRenderer").orEmptyObject()
                .getObject("continuationEndpoint").orEmptyObject()

            val continuationObject: JsonObject
            if (continuationEndpoint.containsKey(COMMAND_EXECUTOR_COMMAND)) {
                continuationObject = continuationEndpoint.getObject(COMMAND_EXECUTOR_COMMAND).orEmptyObject()
                    .getArray("commands").orEmptyArray()
                    .filterIsInstance<JsonObject>()
                    .filter { it.containsKey(CONTINUATION_COMMAND) }
                    .firstOrNull() ?: JsonObject(emptyMap())
            } else {
                continuationObject = continuationEndpoint
            }

            continuation = continuationObject.getObject(CONTINUATION_COMMAND)?.getString("token")
        } else if (lastElement.containsKey("continuationItemViewModel")) {
            val continuationItemViewModel = lastElement.getObject("continuationItemViewModel").orEmptyObject()

            continuation = continuationItemViewModel.getObject(CONTINUATION_COMMAND).orEmptyObject()
                .getObject("innertubeCommand").orEmptyObject()
                .getObject(CONTINUATION_COMMAND).orEmptyObject()
                .getString("token")
        } else {
            return null
        }

        if (isNullOrEmpty(continuation)) {
            return null
        }

        val body = prepareDesktopJsonBuilder(getExtractorLocalization(), getExtractorContentCountry())
            .value("continuation", continuation)
            .done().toString()
            .toByteArray(Charsets.UTF_8)

        return Page(YOUTUBEI_V1_URL + "browse?" + DISABLE_PRETTY_PRINT_PARAMETER, body)
    }

    private fun collectStreamsFrom(
        collector: StreamInfoItemsCollector,
        videos: JsonArray
    ) {
        val timeAgoParser: TimeAgoParser = getTimeAgoParser()
        val playlistExtractor: PlaylistExtractor = this
        val isCoursePlaylistResult = isCoursePlaylistCheck()

        videos.filterIsInstance<JsonObject>().forEach { video ->
            when {
                video.containsKey(PLAYLIST_VIDEO_RENDERER) -> {
                    collector.commit(object : YoutubeStreamInfoItemExtractor(
                        video.getObject(PLAYLIST_VIDEO_RENDERER).orEmptyObject(), timeAgoParser
                    ) {
                        @Throws(ParsingException::class)
                        override fun getUploaderName(): String? {
                            if (isCoursePlaylistResult) {
                                return playlistExtractor.getUploaderName()
                            }
                            return super.getUploaderName()
                        }

                        @Throws(ParsingException::class)
                        override fun getUploaderUrl(): String? {
                            if (isCoursePlaylistResult) {
                                return playlistExtractor.getUploaderUrl()
                            }
                            return super.getUploaderUrl()
                        }
                    })
                }
                video.containsKey(RICH_ITEM_RENDERER) -> {
                    val richItemRenderer = video.getObject(RICH_ITEM_RENDERER).orEmptyObject()
                    if (richItemRenderer.containsKey("content")) {
                        val richItemRendererContent = richItemRenderer.getObject("content").orEmptyObject()
                        if (richItemRendererContent.containsKey(REEL_ITEM_RENDERER)) {
                            collector.commit(
                                YoutubeReelInfoItemExtractor(
                                    richItemRendererContent.getObject(REEL_ITEM_RENDERER).orEmptyObject()
                                )
                            )
                        }
                    }
                }
                video.containsKey(LOCKUP_VIEW_MODEL) -> {
                    collector.commit(object : YoutubeStreamInfoItemLockupExtractor(
                        video.getObject(LOCKUP_VIEW_MODEL).orEmptyObject(), timeAgoParser
                    ) {
                        override fun isChannelOrCoursePlaylistLockupItem(): Boolean = isCoursePlaylistResult

                        @Throws(ParsingException::class)
                        override fun getUploaderName(): String? {
                            if (isCoursePlaylistResult) {
                                return playlistExtractor.getUploaderName()
                            }
                            return super.getUploaderName()
                        }

                        @Throws(ParsingException::class)
                        override fun getUploaderUrl(): String? {
                            if (isCoursePlaylistResult) {
                                return playlistExtractor.getUploaderUrl()
                            }
                            return super.getUploaderUrl()
                        }
                    })
                }
            }
        }
    }

    @Throws(ParsingException::class)
    override fun getPlaylistType(): PlaylistInfo.PlaylistType {
        return extractPlaylistTypeFromPlaylistUrl(getUrl())
    }

    companion object {
        private const val PLAYLIST_VIDEO_RENDERER = "playlistVideoRenderer"
        private const val RICH_ITEM_RENDERER = "richItemRenderer"
        private const val REEL_ITEM_RENDERER = "reelItemRenderer"
        private const val LOCKUP_VIEW_MODEL = "lockupViewModel"
        private const val SIDEBAR = "sidebar"
        private const val HEADER = "header"
        private const val VIDEO_OWNER_RENDERER = "videoOwnerRenderer"
        private const val MICROFORMAT = "microformat"
        private const val COMMAND_EXECUTOR_COMMAND = "commandExecutorCommand"
        private const val THUMBNAIL = "thumbnail"
        private const val THUMBNAILS = "thumbnails"
        private const val ON_RESPONSE_RECEIVED_ACTIONS = "onResponseReceivedActions"
        private const val CONTINUATION_ITEMS = "continuationItems"
        private const val APPEND_CONTINUATION_ITEMS_ACTION = "appendContinuationItemsAction"
        private const val CONTINUATION_COMMAND = "continuationCommand"
        private const val TITLE = "title"
        private const val BROWSE_ENDPOINT = "browse"
        private const val PLAYLIST_CONTINUATION_PROPERTIES_BASE64 = "CADCBgIIAA%3D%3D"
    }
}
