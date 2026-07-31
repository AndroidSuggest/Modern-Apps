package org.schabi.newpipe.extractor.services.youtube.extractors.kiosk

import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.UnsupportedContentInCountryException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import java.io.IOException

class YoutubeTrendingMusicExtractor(
    streamingService: StreamingService,
    linkHandler: ListLinkHandler,
    kioskId: String
) : YoutubeChartsBaseKioskExtractor(streamingService, linkHandler, kioskId, "TRENDING_VIDEOS") {

    @Throws(IOException::class, ExtractionException::class)
    override fun onFetchPage(downloader: Downloader) {
        if (!YT_CHARTS_SUPPORTED_COUNTRY_CODES.contains(extractorContentCountry.countryCode)) {
            throw UnsupportedContentInCountryException(
                "YouTube Charts doesn't support this country for trending music videos charts"
            )
        }
        super.onFetchPage(downloader)
    }

    @Throws(ParsingException::class)
    override fun getName(): String = "Trending Music Videos"
}
