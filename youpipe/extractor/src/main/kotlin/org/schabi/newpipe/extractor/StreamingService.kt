package org.schabi.newpipe.extractor

import org.schabi.newpipe.extractor.channel.ChannelExtractor
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor
import org.schabi.newpipe.extractor.comments.CommentsExtractor
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.feed.FeedExtractor
import org.schabi.newpipe.extractor.kiosk.KioskList
import org.schabi.newpipe.extractor.linkhandler.LinkHandler
import org.schabi.newpipe.extractor.linkhandler.LinkHandlerFactory
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandlerFactory
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.localization.TimeAgoParser
import org.schabi.newpipe.extractor.localization.TimeAgoPatternsManager
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.subscription.SubscriptionExtractor
import org.schabi.newpipe.extractor.suggestion.SuggestionExtractor
import org.schabi.newpipe.extractor.utils.Utils
import java.util.Collections
import javax.annotation.Nullable

abstract class StreamingService(
    @get:JvmName("getServiceId") val serviceId: Int,
    name: String,
    capabilities: Set<ServiceInfo.MediaCapability>
) {

    val serviceInfo: ServiceInfo = ServiceInfo(name, capabilities)

    fun getServiceId(): Int = serviceId
    fun getServiceInfo(): ServiceInfo = serviceInfo

    override fun toString(): String = "$serviceId:${serviceInfo.name}"

    abstract fun getBaseUrl(): String

    abstract fun getStreamLHFactory(): LinkHandlerFactory
    abstract fun getChannelLHFactory(): ListLinkHandlerFactory?
    abstract fun getChannelTabLHFactory(): ListLinkHandlerFactory?
    abstract fun getPlaylistLHFactory(): ListLinkHandlerFactory?
    abstract fun getSearchQHFactory(): SearchQueryHandlerFactory
    abstract fun getCommentsLHFactory(): ListLinkHandlerFactory?

    abstract fun getSearchExtractor(queryHandler: SearchQueryHandler): SearchExtractor
    abstract fun getSuggestionExtractor(): SuggestionExtractor
    abstract fun getSubscriptionExtractor(): SubscriptionExtractor?

    @Nullable
    @Throws(ExtractionException::class)
    open fun getFeedExtractor(url: String): FeedExtractor? = null

    @Throws(ExtractionException::class)
    abstract fun getKioskList(): KioskList

    @Throws(ExtractionException::class)
    abstract fun getChannelExtractor(linkHandler: ListLinkHandler): ChannelExtractor

    @Throws(ExtractionException::class)
    abstract fun getChannelTabExtractor(linkHandler: ListLinkHandler): ChannelTabExtractor

    @Throws(ExtractionException::class)
    abstract fun getPlaylistExtractor(linkHandler: ListLinkHandler): PlaylistExtractor

    @Throws(ExtractionException::class)
    abstract fun getStreamExtractor(linkHandler: LinkHandler): StreamExtractor

    @Throws(ExtractionException::class)
    abstract fun getCommentsExtractor(linkHandler: ListLinkHandler): CommentsExtractor?

    @Throws(ExtractionException::class)
    fun getSearchExtractor(query: String, contentFilter: List<String>, sortFilter: String): SearchExtractor =
        getSearchExtractor(getSearchQHFactory().fromQuery(query, contentFilter, sortFilter))

    @Throws(ExtractionException::class)
    fun getChannelExtractor(id: String, contentFilter: List<String>, sortFilter: String): ChannelExtractor =
        getChannelExtractor(getChannelLHFactory()!!.fromQuery(id, contentFilter, sortFilter))

    @Throws(ExtractionException::class)
    fun getPlaylistExtractor(id: String, contentFilter: List<String>, sortFilter: String): PlaylistExtractor =
        getPlaylistExtractor(getPlaylistLHFactory()!!.fromQuery(id, contentFilter, sortFilter))

    @Throws(ExtractionException::class)
    fun getSearchExtractor(query: String): SearchExtractor =
        getSearchExtractor(getSearchQHFactory().fromQuery(query))

    @Throws(ExtractionException::class)
    fun getChannelExtractor(url: String): ChannelExtractor =
        getChannelExtractor(getChannelLHFactory()!!.fromUrl(url))

    @Throws(ExtractionException::class)
    fun getChannelTabExtractorFromId(id: String, tab: String): ChannelTabExtractor =
        getChannelTabExtractor(
            getChannelTabLHFactory()!!.fromQuery(id, Collections.singletonList(tab), "")
        )

    @Throws(ExtractionException::class)
    fun getChannelTabExtractorFromIdAndBaseUrl(id: String, tab: String, baseUrl: String): ChannelTabExtractor =
        getChannelTabExtractor(
            getChannelTabLHFactory()!!.fromQuery(id, Collections.singletonList(tab), "", baseUrl)
        )

    @Throws(ExtractionException::class)
    fun getPlaylistExtractor(url: String): PlaylistExtractor =
        getPlaylistExtractor(getPlaylistLHFactory()!!.fromUrl(url))

    @Throws(ExtractionException::class)
    fun getStreamExtractor(url: String): StreamExtractor =
        getStreamExtractor(getStreamLHFactory().fromUrl(url))

    @Throws(ExtractionException::class)
    fun getCommentsExtractor(url: String): CommentsExtractor? {
        val factory = getCommentsLHFactory() ?: return null
        return getCommentsExtractor(factory.fromUrl(url))
    }

    @Throws(ParsingException::class)
    fun getLinkTypeByUrl(url: String): LinkType {
        val polishedUrl = Utils.followGoogleRedirectIfNeeded(url)
        val sH = getStreamLHFactory()
        val cH = getChannelLHFactory()
        val pH = getPlaylistLHFactory()

        return when {
            sH.acceptUrl(polishedUrl) -> LinkType.STREAM
            cH != null && cH.acceptUrl(polishedUrl) -> LinkType.CHANNEL
            pH != null && pH.acceptUrl(polishedUrl) -> LinkType.PLAYLIST
            else -> LinkType.NONE
        }
    }

    open fun getSupportedLocalizations(): List<Localization> =
        Collections.singletonList(Localization.DEFAULT)

    open fun getSupportedCountries(): List<ContentCountry> =
        Collections.singletonList(ContentCountry.DEFAULT)

    fun getLocalization(): Localization {
        val preferredLocalization = NewPipe.getPreferredLocalization()
        if (getSupportedLocalizations().contains(preferredLocalization)) {
            return preferredLocalization
        }
        for (supportedLanguage in getSupportedLocalizations()) {
            if (supportedLanguage.languageCode == preferredLocalization.languageCode) {
                return supportedLanguage
            }
        }
        return Localization.DEFAULT
    }

    fun getContentCountry(): ContentCountry {
        val preferredContentCountry = NewPipe.getPreferredContentCountry()
        return if (getSupportedCountries().contains(preferredContentCountry)) {
            preferredContentCountry
        } else {
            ContentCountry.DEFAULT
        }
    }

    fun getTimeAgoParser(localization: Localization): TimeAgoParser {
        val targetParser = TimeAgoPatternsManager.getTimeAgoParserFor(localization)
        if (targetParser != null) return targetParser

        if (localization.countryCode.isNotEmpty()) {
            val lessSpecific = Localization(localization.languageCode)
            val lessSpecificParser = TimeAgoPatternsManager.getTimeAgoParserFor(lessSpecific)
            if (lessSpecificParser != null) return lessSpecificParser
        }

        throw IllegalArgumentException("Localization is not supported (\"$localization\")")
    }

    class ServiceInfo(val name: String, val mediaCapabilities: Set<MediaCapability>) {
        fun getName(): String = name
        fun getMediaCapabilities(): Set<MediaCapability> = mediaCapabilities

        enum class MediaCapability {
            AUDIO, VIDEO, LIVE, COMMENTS
        }
    }

    enum class LinkType {
        NONE,
        STREAM,
        CHANNEL,
        PLAYLIST
    }
}
