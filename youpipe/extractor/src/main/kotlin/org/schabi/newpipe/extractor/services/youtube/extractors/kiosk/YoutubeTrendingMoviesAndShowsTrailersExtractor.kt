package org.schabi.newpipe.extractor.services.youtube.extractors.kiosk

import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler

class YoutubeTrendingMoviesAndShowsTrailersExtractor(
    streamingService: StreamingService,
    linkHandler: ListLinkHandler,
    kioskId: String
) : YoutubeChartsBaseKioskExtractor(streamingService, linkHandler, kioskId, "TRENDING_MOVIES") {

    @Throws(ParsingException::class)
    override fun getName(): String = "Trending Movie Trailers"
}
