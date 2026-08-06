package org.schabi.newpipe.extractor.services.youtube

import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelExtractor
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor
import org.schabi.newpipe.extractor.comments.CommentsExtractor
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.kiosk.KioskList
import org.schabi.newpipe.extractor.linkhandler.LinkHandler
import org.schabi.newpipe.extractor.linkhandler.LinkHandlerFactory
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory
import org.schabi.newpipe.extractor.linkhandler.ReadyChannelTabListLinkHandler
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandlerFactory
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeChannelExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeChannelTabExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeCommentsExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeMixPlaylistExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeMusicSearchExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubePlaylistExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeSearchExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeSubscriptionExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeSuggestionExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.kiosk.YoutubeLiveExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.kiosk.YoutubeTrendingExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.kiosk.YoutubeTrendingGamingVideosExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.kiosk.YoutubeTrendingMoviesAndShowsTrailersExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.kiosk.YoutubeTrendingMusicExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.kiosk.YoutubeTrendingPodcastsEpisodesExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeChannelLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeChannelTabLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeCommentsLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeLiveLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubePlaylistLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeTrendingGamingVideosLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeTrendingLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeTrendingMoviesAndShowsTrailersLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeTrendingMusicLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeTrendingPodcastsEpisodesLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.subscription.SubscriptionExtractor
import org.schabi.newpipe.extractor.suggestion.SuggestionExtractor
import java.util.EnumSet

class YoutubeService(id: Int) : StreamingService(
    id,
    "YouTube",
    EnumSet.of(
        ServiceInfo.MediaCapability.AUDIO,
        ServiceInfo.MediaCapability.VIDEO,
        ServiceInfo.MediaCapability.LIVE,
        ServiceInfo.MediaCapability.COMMENTS
    )
) {

    override fun getBaseUrl(): String = "https://youtube.com"

    override fun getStreamLHFactory(): LinkHandlerFactory =
        YoutubeStreamLinkHandlerFactory.getInstance()

    override fun getChannelLHFactory(): ListLinkHandlerFactory =
        YoutubeChannelLinkHandlerFactory.getInstance()

    override fun getChannelTabLHFactory(): ListLinkHandlerFactory =
        YoutubeChannelTabLinkHandlerFactory.getInstance()

    override fun getPlaylistLHFactory(): ListLinkHandlerFactory =
        YoutubePlaylistLinkHandlerFactory.getInstance()

    override fun getSearchQHFactory(): SearchQueryHandlerFactory =
        YoutubeSearchQueryHandlerFactory.getInstance()

    override fun getStreamExtractor(linkHandler: LinkHandler): StreamExtractor =
        YoutubeStreamExtractor(this, linkHandler)

    override fun getChannelExtractor(linkHandler: ListLinkHandler): ChannelExtractor =
        YoutubeChannelExtractor(this, linkHandler)

    override fun getChannelTabExtractor(linkHandler: ListLinkHandler): ChannelTabExtractor {
        return if (linkHandler is ReadyChannelTabListLinkHandler) {
            linkHandler.getChannelTabExtractor(this)
        } else {
            YoutubeChannelTabExtractor(this, linkHandler)
        }
    }

    override fun getPlaylistExtractor(linkHandler: ListLinkHandler): PlaylistExtractor {
        return if (YoutubeParsingHelper.isYoutubeMixId(linkHandler.id)) {
            YoutubeMixPlaylistExtractor(this, linkHandler)
        } else {
            YoutubePlaylistExtractor(this, linkHandler)
        }
    }

    override fun getSearchExtractor(queryHandler: SearchQueryHandler): SearchExtractor {
        val contentFilters = queryHandler.contentFilters
        return if (contentFilters.isNotEmpty() && contentFilters[0].startsWith("music_")) {
            YoutubeMusicSearchExtractor(this, queryHandler)
        } else {
            YoutubeSearchExtractor(this, queryHandler)
        }
    }

    override fun getSuggestionExtractor(): SuggestionExtractor =
        YoutubeSuggestionExtractor(this)

    @Throws(ExtractionException::class)
    override fun getKioskList(): KioskList {
        val list = KioskList(this)
        val trendingLHF = YoutubeTrendingLinkHandlerFactory.INSTANCE
        val runningLivesLHF = YoutubeLiveLinkHandlerFactory.INSTANCE
        val trendingPodcastsEpisodesLHF =
            YoutubeTrendingPodcastsEpisodesLinkHandlerFactory.INSTANCE
        val trendingGamingVideosLHF = YoutubeTrendingGamingVideosLinkHandlerFactory.INSTANCE
        val trendingMoviesAndShowsLHF =
            YoutubeTrendingMoviesAndShowsTrailersLinkHandlerFactory.INSTANCE
        val trendingMusicLHF = YoutubeTrendingMusicLinkHandlerFactory.INSTANCE

        try {
            list.addKioskEntry(
                { streamingService, url, kioskId ->
                    YoutubeLiveExtractor(
                        streamingService,
                        runningLivesLHF.fromUrl(url),
                        kioskId
                    )
                },
                runningLivesLHF,
                YoutubeLiveLinkHandlerFactory.KIOSK_ID
            )
            list.addKioskEntry(
                { streamingService, url, kioskId ->
                    YoutubeTrendingPodcastsEpisodesExtractor(
                        streamingService,
                        trendingPodcastsEpisodesLHF.fromUrl(url),
                        kioskId
                    )
                },
                trendingPodcastsEpisodesLHF,
                YoutubeTrendingPodcastsEpisodesLinkHandlerFactory.KIOSK_ID
            )
            list.addKioskEntry(
                { streamingService, url, kioskId ->
                    YoutubeTrendingGamingVideosExtractor(
                        streamingService,
                        trendingGamingVideosLHF.fromUrl(url),
                        kioskId
                    )
                },
                trendingGamingVideosLHF,
                YoutubeTrendingGamingVideosLinkHandlerFactory.KIOSK_ID
            )
            list.addKioskEntry(
                { streamingService, url, kioskId ->
                    YoutubeTrendingMoviesAndShowsTrailersExtractor(
                        streamingService,
                        trendingMoviesAndShowsLHF.fromUrl(url),
                        kioskId
                    )
                },
                trendingMoviesAndShowsLHF,
                YoutubeTrendingMoviesAndShowsTrailersLinkHandlerFactory.KIOSK_ID
            )
            list.addKioskEntry(
                { streamingService, url, kioskId ->
                    YoutubeTrendingMusicExtractor(
                        streamingService,
                        trendingMusicLHF.fromUrl(url),
                        kioskId
                    )
                },
                trendingMusicLHF,
                YoutubeTrendingMusicLinkHandlerFactory.KIOSK_ID
            )
            // Deprecated (i.e. removed from the interface of YouTube) since July 21, 2025
            list.addKioskEntry(
                { streamingService, url, kioskId ->
                    YoutubeTrendingExtractor(
                        streamingService,
                        trendingLHF.fromUrl(url),
                        kioskId
                    )
                },
                trendingLHF,
                YoutubeTrendingExtractor.KIOSK_ID
            )
            list.setDefaultKiosk(YoutubeLiveLinkHandlerFactory.KIOSK_ID)
        } catch (e: Exception) {
            throw ExtractionException(e)
        }

        return list
    }

    override fun getSubscriptionExtractor(): SubscriptionExtractor =
        YoutubeSubscriptionExtractor(this)

    override fun getCommentsLHFactory(): ListLinkHandlerFactory =
        YoutubeCommentsLinkHandlerFactory.getInstance()

    @Throws(ExtractionException::class)
    override fun getCommentsExtractor(linkHandler: ListLinkHandler): CommentsExtractor =
        YoutubeCommentsExtractor(this, linkHandler)

    override fun getSupportedLocalizations(): List<Localization> = SUPPORTED_LANGUAGES

    override fun getSupportedCountries(): List<ContentCountry> = SUPPORTED_COUNTRIES

    companion object {
        private val SUPPORTED_LANGUAGES: List<Localization> = Localization.listFrom(
            "en-GB"
        )

        private val SUPPORTED_COUNTRIES: List<ContentCountry> = ContentCountry.listFrom(
            "DZ", "AR", "AU", "AT", "AZ", "BH", "BD", "BY", "BE", "BO", "BA", "BR", "BG", "KH",
            "CA", "CL", "CO", "CR", "HR", "CY", "CZ", "DK", "DO", "EC", "EG", "SV", "EE", "FI",
            "FR", "GE", "DE", "GH", "GR", "GT", "HN", "HK", "HU", "IS", "IN", "ID", "IQ", "IE",
            "IL", "IT", "JM", "JP", "JO", "KZ", "KE", "KW", "LA", "LV", "LB", "LY", "LI", "LT",
            "LU", "MY", "MT", "MX", "ME", "MA", "NP", "NL", "NZ", "NI", "NG", "MK", "NO", "OM",
            "PK", "PA", "PG", "PY", "PE", "PH", "PL", "PT", "PR", "QA", "RO", "RU", "SA", "SN",
            "RS", "SG", "SK", "SI", "ZA", "KR", "ES", "LK", "SE", "CH", "TW", "TZ", "TH", "TN",
            "TR", "UG", "UA", "AE", "GB", "US", "UY", "VE", "VN", "YE", "ZW"
        )
    }
}
