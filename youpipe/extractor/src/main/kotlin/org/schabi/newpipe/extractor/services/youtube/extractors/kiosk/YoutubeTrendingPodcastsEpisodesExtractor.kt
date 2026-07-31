package org.schabi.newpipe.extractor.services.youtube.extractors.kiosk

import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler

class YoutubeTrendingPodcastsEpisodesExtractor(
    streamingService: StreamingService,
    linkHandler: ListLinkHandler,
    kioskId: String
) : YoutubeDesktopBaseKioskExtractor(streamingService, linkHandler, kioskId, "FEpodcasts_destination", "qgcCCAM%3D")
