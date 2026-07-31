package org.schabi.newpipe.extractor.services.youtube.extractors

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelExtractor
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.linkhandler.ReadyChannelTabListLinkHandler
import org.schabi.newpipe.extractor.services.youtube.YoutubeChannelHelper
import org.schabi.newpipe.extractor.services.youtube.YoutubeChannelHelper.ChannelHeader.HeaderType
import org.schabi.newpipe.extractor.services.youtube.YoutubeChannelHelper.getChannelResponse
import org.schabi.newpipe.extractor.services.youtube.YoutubeChannelHelper.resolveChannelId
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getTextFromObject
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeChannelTabExtractor.VideosTabExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeChannelLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeChannelTabLinkHandlerFactory
import org.schabi.newpipe.extractor.utils.Utils
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString
import java.io.IOException

class YoutubeChannelExtractor(
    service: StreamingService,
    linkHandler: ListLinkHandler
) : ChannelExtractor(service, linkHandler) {

    private var jsonResponse: JsonObject? = null
    private var channelHeader: YoutubeChannelHelper.ChannelHeader? = null
    private var channelId: String? = null

    /**
     * If a channel is age-restricted, its pages are only accessible to logged-in and
     * age-verified users, we get an `channelAgeGateRenderer` in this case, containing only
     * the following metadata: channel name and channel avatar.
     *
     * This restriction doesn't seem to apply to all countries.
     */
    private var channelAgeGateRenderer: JsonObject? = null

    @Throws(IOException::class, ExtractionException::class)
    override fun onFetchPage(downloader: Downloader) {
        val channelPath = super.getId()
        val id = resolveChannelId(channelPath)
        // Fetch Videos tab
        val data = getChannelResponse(
            id,
            "EgZ2aWRlb3PyBgQKAjoA", getExtractorLocalization(), getExtractorContentCountry()
        )

        jsonResponse = data.jsonResponse
        channelHeader = YoutubeChannelHelper.getChannelHeader(jsonResponse!!)
        channelId = data.channelId
        channelAgeGateRenderer = YoutubeChannelHelper.getChannelAgeGateRenderer(jsonResponse!!)
    }

    override fun getUrl(): String {
        try {
            return YoutubeChannelLinkHandlerFactory.getInstance().getUrl("channel/" + getId())
        } catch (e: ParsingException) {
            return super.getUrl()
        }
    }

    override fun getId(): String {
        assertPageFetched()
        return YoutubeChannelHelper.getChannelId(channelHeader, jsonResponse!!, channelId)
    }

    override fun getName(): String {
        assertPageFetched()
        return YoutubeChannelHelper.getChannelName(
            channelHeader, channelAgeGateRenderer, jsonResponse!!
        )
    }

    override fun getAvatars(): List<Image> {
        assertPageFetched()
        if (channelAgeGateRenderer != null) {
            return channelAgeGateRenderer!!.getObject(AVATAR)
                ?.getArray(THUMBNAILS)
                ?.let { YoutubeParsingHelper.getImagesFromThumbnailsArray(it) }
                ?: throw ParsingException("Could not get avatars")
        }

        return channelHeader?.let { header ->
            val array: JsonArray = when (header.headerType) {
                HeaderType.PAGE -> {
                    val imageObj = header.json.getObject(CONTENT)
                        ?.getObject(PAGE_HEADER_VIEW_MODEL)
                        ?.getObject(IMAGE)

                    when {
                        imageObj?.containsKey(CONTENT_PREVIEW_IMAGE_VIEW_MODEL) == true -> {
                            imageObj.getObject(CONTENT_PREVIEW_IMAGE_VIEW_MODEL)
                                ?.getObject(IMAGE)?.getArray(SOURCES) ?: JsonArray(emptyList())
                        }
                        imageObj?.containsKey("decoratedAvatarViewModel") == true -> {
                            imageObj.getObject("decoratedAvatarViewModel")
                                ?.getObject(AVATAR)
                                ?.getObject("avatarViewModel")
                                ?.getObject(IMAGE)?.getArray(SOURCES)
                                ?: JsonArray(emptyList())
                        }
                        else -> JsonArray(emptyList())
                    }
                }
                HeaderType.INTERACTIVE_TABBED -> header.json.getObject("boxArt")
                    ?.getArray(THUMBNAILS) ?: JsonArray(emptyList())
                HeaderType.C4_TABBED, HeaderType.CAROUSEL -> header.json.getObject(AVATAR)
                    ?.getArray(THUMBNAILS) ?: JsonArray(emptyList())
            }

            YoutubeParsingHelper.getImagesFromThumbnailsArray(array)
        } ?: throw ParsingException("Could not get avatars")
    }

    override fun getBanners(): List<Image> {
        assertPageFetched()
        if (channelAgeGateRenderer != null) {
            return emptyList()
        }

        return channelHeader?.let { header ->
            val array: JsonArray = if (header.headerType == HeaderType.PAGE) {
                val pageHeaderViewModel = header.json.getObject(CONTENT)
                    ?.getObject(PAGE_HEADER_VIEW_MODEL)

                if (pageHeaderViewModel?.containsKey(BANNER) == true) {
                    pageHeaderViewModel.getObject(BANNER)
                        ?.getObject("imageBannerViewModel")
                        ?.getObject(IMAGE)?.getArray(SOURCES)
                        ?: JsonArray(emptyList())
                } else {
                    JsonArray(emptyList())
                }
            } else {
                header.json.getObject(BANNER)?.getArray(THUMBNAILS) ?: JsonArray(emptyList())
            }

            YoutubeParsingHelper.getImagesFromThumbnailsArray(array)
        } ?: emptyList()
    }

    @Throws(ParsingException::class)
    override fun getFeedUrl(): String? {
        try {
            return YoutubeParsingHelper.getFeedUrlFrom(getId())
        } catch (e: Exception) {
            throw ParsingException("Could not get feed URL", e)
        }
    }

    @Throws(ParsingException::class)
    override fun getSubscriberCount(): Long {
        assertPageFetched()
        if (channelAgeGateRenderer != null) {
            return UNKNOWN_SUBSCRIBER_COUNT
        }

        channelHeader?.let { header ->
            if (header.headerType == HeaderType.INTERACTIVE_TABBED) {
                return UNKNOWN_SUBSCRIBER_COUNT
            }

            val headerJson = header.json
            if (header.headerType == HeaderType.PAGE) {
                return getSubscriberCountFromPageChannelHeader(headerJson)
            }

            var textObject: JsonObject? = null

            if (headerJson.containsKey("subscriberCountText")) {
                textObject = headerJson.getObject("subscriberCountText")
            } else if (headerJson.containsKey("subtitle")) {
                textObject = headerJson.getObject("subtitle")
            }

            if (textObject != null) {
                try {
                    return Utils.mixedNumberWordToLong(getTextFromObject(textObject))
                } catch (e: NumberFormatException) {
                    throw ParsingException("Could not get subscriber count", e)
                }
            }
        }

        return UNKNOWN_SUBSCRIBER_COUNT
    }

    @Throws(ParsingException::class)
    private fun getSubscriberCountFromPageChannelHeader(headerJson: JsonObject): Long {
        val metadataObject = headerJson.getObject(CONTENT)
            ?.getObject(PAGE_HEADER_VIEW_MODEL)
            ?.getObject(METADATA)
        if (metadataObject?.containsKey("contentMetadataViewModel") == true) {
            val metadataRows = metadataObject.getObject("contentMetadataViewModel")!!
                .getArray("metadataRows")!!

            val lastMetadataRowParts = metadataRows.getObject(
                maxOf(0, metadataRows.size - 1)
            )!!.getArray("metadataParts")!!

            if (lastMetadataRowParts.size < 2) {
                return UNKNOWN_SUBSCRIBER_COUNT
            }

            try {
                return Utils.mixedNumberWordToLong(
                    lastMetadataRowParts.getObject(0)!!.getObject("text")!!.getString(CONTENT)
                )
            } catch (e: NumberFormatException) {
                throw ParsingException("Could not get subscriber count", e)
            }
        }

        return UNKNOWN_SUBSCRIBER_COUNT
    }

    @Throws(ParsingException::class)
    override fun getDescription(): String? {
        assertPageFetched()
        if (channelAgeGateRenderer != null) {
            return null
        }

        try {
            if (channelHeader != null && channelHeader!!.headerType == HeaderType.INTERACTIVE_TABBED) {
                return getTextFromObject(channelHeader!!.json.getObject("description"))
            }

            return jsonResponse!!.getObject(METADATA)!!
                .getObject("channelMetadataRenderer")!!
                .getString("description")
        } catch (e: Exception) {
            throw ParsingException("Could not get channel description", e)
        }
    }

    override fun getParentChannelName(): String = ""

    override fun getParentChannelUrl(): String = ""

    override fun getParentChannelAvatars(): List<Image> = emptyList()

    @Throws(ParsingException::class)
    override fun isVerified(): Boolean {
        assertPageFetched()
        if (channelAgeGateRenderer != null) {
            return false
        }

        if (channelHeader == null) {
            throw ParsingException(
                "Could not get channel verified status, no channel header has been extracted"
            )
        }

        return YoutubeChannelHelper.isChannelVerified(channelHeader!!)
    }

    @Throws(ParsingException::class)
    override fun getTabs(): List<ListLinkHandler> {
        assertPageFetched()
        if (channelAgeGateRenderer == null) {
            return getTabsForNonAgeRestrictedChannels()
        }

        return getTabsForAgeRestrictedChannels()
    }

    @Throws(ParsingException::class)
    private fun getTabsForNonAgeRestrictedChannels(): List<ListLinkHandler> {
        val responseTabs = jsonResponse!!.getObject(CONTENTS)!!
            .getObject("twoColumnBrowseResultsRenderer")!!
            .getArray("tabs")!!

        val tabs = mutableListOf<ListLinkHandler>()
        val addNonVideosTab: (String) -> Unit = { tabName ->
            try {
                tabs.add(
                    YoutubeChannelTabLinkHandlerFactory.getInstance().fromQuery(
                        channelId, listOf(tabName), ""
                    )
                )
            } catch (ignored: ParsingException) {
            }
        }

        val name = getName()
        val url = getUrl()
        val id = getId()

        responseTabs.filterIsInstance<JsonObject>()
            .filter { it.containsKey(TAB_RENDERER) }
            .mapNotNull { it.getObject(TAB_RENDERER) }
            .forEach { tabRenderer ->
                val tabUrl = tabRenderer.getObject("endpoint")
                    ?.getObject("commandMetadata")
                    ?.getObject("webCommandMetadata")
                    ?.getString("url")
                if (tabUrl != null) {
                    val urlParts = tabUrl.split("/")
                    if (urlParts.isEmpty()) {
                        return@forEach
                    }

                    val urlSuffix = urlParts[urlParts.size - 1]

                    val channelHeaderCopy = if (channelHeader == null) null else
                        YoutubeChannelHelper.ChannelHeader(channelHeader!!.json, channelHeader!!.headerType)

                    when (urlSuffix) {
                        "videos" -> {
                            tabs.add(
                                0, ReadyChannelTabListLinkHandler(
                                    tabUrl,
                                    channelId!!,
                                    ChannelTabs.VIDEOS
                                ) { service, linkHandler ->
                                    VideosTabExtractor(
                                        service, linkHandler, tabRenderer,
                                        channelHeaderCopy, name, id, url
                                    )
                                }
                            )
                        }
                        "shorts" -> addNonVideosTab(ChannelTabs.SHORTS)
                        "streams" -> addNonVideosTab(ChannelTabs.LIVESTREAMS)
                        "releases" -> addNonVideosTab(ChannelTabs.ALBUMS)
                        "playlists" -> addNonVideosTab(ChannelTabs.PLAYLISTS)
                        else -> {}
                    }
                }
            }

        return tabs.toList()
    }

    @Throws(ParsingException::class)
    private fun getTabsForAgeRestrictedChannels(): List<ListLinkHandler> {
        val tabs = mutableListOf<ListLinkHandler>()
        val channelUrl = getUrl()

        val addTab: (String) -> Unit = { tabName ->
            tabs.add(
                ReadyChannelTabListLinkHandler(
                    "$channelUrl/$tabName",
                    channelId!!, tabName
                ) { service, linkHandler -> YoutubeChannelTabPlaylistExtractor(service, linkHandler) }
            )
        }

        addTab(ChannelTabs.VIDEOS)
        addTab(ChannelTabs.SHORTS)
        addTab(ChannelTabs.LIVESTREAMS)
        return tabs.toList()
    }

    @Throws(ParsingException::class)
    override fun getTags(): List<String> {
        assertPageFetched()
        if (channelAgeGateRenderer != null) {
            return emptyList()
        }

        return jsonResponse!!.getObject("microformat")!!
            .getObject("microformatDataRenderer")!!
            .getArray("tags")!!
            .filterIsInstance<String>()
            .toList()
    }

    companion object {
        private const val IMAGE = "image"
        private const val CONTENTS = "contents"
        private const val CONTENT_PREVIEW_IMAGE_VIEW_MODEL = "contentPreviewImageViewModel"
        private const val PAGE_HEADER_VIEW_MODEL = "pageHeaderViewModel"
        private const val TAB_RENDERER = "tabRenderer"
        private const val CONTENT = "content"
        private const val METADATA = "metadata"
        private const val AVATAR = "avatar"
        private const val THUMBNAILS = "thumbnails"
        private const val SOURCES = "sources"
        private const val BANNER = "banner"
    }
}
