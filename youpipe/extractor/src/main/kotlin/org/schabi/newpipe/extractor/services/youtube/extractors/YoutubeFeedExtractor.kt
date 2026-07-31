package org.schabi.newpipe.extractor.services.youtube.extractors

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.feed.FeedExtractor
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector
import java.io.IOException

class YoutubeFeedExtractor(
    service: StreamingService,
    linkHandler: ListLinkHandler
) : FeedExtractor(service, linkHandler) {

    private var document: Document? = null

    @Throws(IOException::class, ExtractionException::class)
    override fun onFetchPage(downloader: Downloader) {
        val channelIdOrUser = linkHandler.id
        val feedUrl = YoutubeParsingHelper.getFeedUrlFrom(channelIdOrUser)

        val response = downloader.get(feedUrl)
        if (response.responseCode() == 404) {
            throw ContentNotAvailableException("Could not get feed: 404 - not found")
        }
        document = Jsoup.parse(response.responseBody())
    }

    override fun getInitialPage(): ListExtractor.InfoItemsPage<StreamInfoItem> {
        val entries = document!!.select("feed > entry")
        val collector = StreamInfoItemsCollector(serviceId)

        for (entryElement in entries) {
            collector.commit(YoutubeFeedInfoItemExtractor(entryElement))
        }

        return ListExtractor.InfoItemsPage(collector, null)
    }

    override fun getId(): String {
        return getUrl().replace(WEBSITE_CHANNEL_BASE_URL, "")
    }

    override fun getUrl(): String {
        val authorUriElement = document!!.select("feed > author > uri")
            .first()
        if (authorUriElement != null) {
            val authorUriElementText = authorUriElement.text()
            if (authorUriElementText != "") {
                return authorUriElementText
            }
        }

        val linkElement = document!!.select("feed > link[rel*=alternate]")
            .first()
        if (linkElement != null) {
            return linkElement.attr("href")
        }

        return ""
    }

    override fun getName(): String {
        val nameElement = document!!.select("feed > author > name")
            .first()
            ?: return ""

        return nameElement.text()
    }

    override fun getPage(page: Page): ListExtractor.InfoItemsPage<StreamInfoItem> {
        return ListExtractor.InfoItemsPage.emptyPage()
    }

    companion object {
        private const val WEBSITE_CHANNEL_BASE_URL = "https://www.youtube.com/channel/"
    }
}
