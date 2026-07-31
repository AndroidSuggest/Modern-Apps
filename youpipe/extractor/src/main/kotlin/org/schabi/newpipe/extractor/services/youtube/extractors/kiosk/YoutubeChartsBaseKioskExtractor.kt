package org.schabi.newpipe.extractor.services.youtube.extractors.kiosk

import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.kiosk.KioskExtractor
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.DISABLE_PRETTY_PRINT_PARAMETER
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getClientHeaders
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getOriginReferrerHeaders
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getThumbnailsFromInfoItem
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getValidJsonResponseBody
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.prepareJsonBuilder
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeChannelLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.utils.JsonUtils
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString
import org.schabi.newpipe.extractor.utils.orEmptyArray
import org.schabi.newpipe.extractor.utils.orEmptyObject

/**
 * Base class parsing responses from YouTube Charts for all trending video charts.
 *
 * Note: YouTube Charts isn't officially supported in all YouTube supported countries (there are
 * fewer countries in the LAUNCHED_CHART_COUNTRIES array of YouTube Charts' HTML responses than
 * in the YouTube country selector).
 *
 * For some trends, some videos are still returned in unsupported countries, even if there are
 * fewer than in a supported country, for others an HTTP 400 error is returned saying
 * Request contains an invalid argument.
 */
abstract class YoutubeChartsBaseKioskExtractor(
    streamingService: StreamingService,
    linkHandler: ListLinkHandler,
    kioskId: String,
    protected val chartType: String
) : KioskExtractor<StreamInfoItem>(streamingService, linkHandler, kioskId) {

    protected var browseResponse: JsonObject? = null

    @Throws(IOException::class, ExtractionException::class)
    override fun onFetchPage(downloader: Downloader) {
        val localization = getExtractorLocalization()
        val contentCountry = getExtractorContentCountry()

        val innertubeClientRequestInfo = InnertubeClientRequestInfo.ofWebMusicAnalyticsChartsClient()

        val builder = prepareJsonBuilder(localization, contentCountry, innertubeClientRequestInfo, null)
            .value("browseId", "FEmusic_analytics_charts_home")
            .value(
                "query",
                "perspective=CHART_DETAILS&chart_params_country_code=${contentCountry.countryCode}&chart_params_chart_type=$chartType"
            )

        val body = builder.done().toString().toByteArray(Charsets.UTF_8)

        val headers = HashMap(getOriginReferrerHeaders("https://charts.youtube.com"))
        headers.putAll(
            getClientHeaders(
                innertubeClientRequestInfo.clientInfo.clientId,
                innertubeClientRequestInfo.clientInfo.clientVersion
            )
        )

        browseResponse = JsonUtils.toJsonObject(
            getValidJsonResponseBody(
                downloader.postWithContentTypeJson(YT_CHARTS_ENDPOINT, headers, body, localization)
            )
        )
    }

    override fun getName(): String {
        throw ParsingException("Implemented by subclasses")
    }

    @Throws(IOException::class, ExtractionException::class)
    override fun getInitialPage(): InfoItemsPage<StreamInfoItem> {
        val videos = browseResponse!!.getObject("contents").orEmptyObject()
            .getObject("sectionListRenderer").orEmptyObject()
            .getArray("contents").orEmptyArray()
            .getObject(0).orEmptyObject()
            .getObject("musicAnalyticsSectionRenderer").orEmptyObject()
            .getObject("content").orEmptyObject()
            .getArray("videos").orEmptyArray()
            .getObject(0).orEmptyObject()
            .getArray("videoViews").orEmptyArray()

        val collector = StreamInfoItemsCollector(getServiceId())

        videos.filterIsInstance<JsonObject>()
            .forEach { video -> collector.commit(YoutubeChartsVideoInfoItemExtractor(video)) }

        return InfoItemsPage(collector, null)
    }

    override fun getPage(page: Page): InfoItemsPage<StreamInfoItem> {
        return InfoItemsPage.emptyPage()
    }

    class YoutubeChartsVideoInfoItemExtractor(
        private val videoObject: JsonObject
    ) : StreamInfoItemExtractor {

        override fun getStreamType(): StreamType = StreamType.VIDEO_STREAM

        override fun isAd(): Boolean = false

        override fun getDuration(): Long {
            return (videoObject["videoDuration"] as? kotlinx.serialization.json.JsonPrimitive)?.let {
                it.content.toLongOrNull() ?: -1L
            } ?: (videoObject.getObject("videoDuration")?.let { -1L } ?: -1L) ?: run {
                // Try compat extension getInt
                videoObject.let { obj ->
                    try {
                        (obj["videoDuration"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLong() ?: -1L
                    } catch (e: Exception) {
                        -1L
                    }
                }
            }
        }

        override fun getViewCount(): Long = -1

        @Throws(ParsingException::class)
        override fun getUploaderName(): String? {
            return videoObject.getString("channelName") ?: ""
        }

        @Throws(ParsingException::class)
        override fun getUploaderUrl(): String? {
            val channelId = videoObject.getString("externalChannelId")
            if (isNullOrEmpty(channelId)) {
                throw ParsingException("Could not get channel ID")
            }
            return YoutubeChannelLinkHandlerFactory.getInstance().getUrl("channel/$channelId")
        }

        override fun isUploaderVerified(): Boolean = false

        override fun getTextualUploadDate(): String? = null

        override fun getUploadDate(): DateWrapper {
            val releaseDate = videoObject.getObject("releaseDate").orEmptyObject()
            val year = (releaseDate["year"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toInt() ?: 1970
            val month = (releaseDate["month"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toInt() ?: 1
            val day = (releaseDate["day"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toInt() ?: 1
            val localDate = LocalDate.of(year, month, day)
            val instant = localDate.atStartOfDay(ZoneOffset.UTC).toInstant()
            return DateWrapper(instant, true)
        }

        @Throws(ParsingException::class)
        override fun getName(): String {
            return videoObject.getString("title") ?: ""
        }

        @Throws(ParsingException::class)
        override fun getUrl(): String {
            return YoutubeStreamLinkHandlerFactory.getInstance().getUrl(
                videoObject.getString("id")
                    ?: throw ParsingException("Could not get video id")
            )
        }

        @Throws(ParsingException::class)
        override fun getThumbnails(): List<Image> {
            return getThumbnailsFromInfoItem(videoObject)
        }
    }

    companion object {
        @JvmStatic
        protected val YT_CHARTS_SUPPORTED_COUNTRY_CODES: Set<String> = setOf(
            "AE", "AR", "AT", "AU", "BE", "BO", "BR", "CA", "CH", "CL", "CO", "CR", "CZ", "DE",
            "DK", "DO", "EC", "EE", "EG", "ES", "FI", "FR", "GB", "GT", "HN", "HU", "ID", "IE",
            "IL", "IN", "IS", "IT", "JP", "KE", "KR", "LU", "MX", "NG", "NI", "NL", "NO", "NZ",
            "PA", "PE", "PL", "PT", "PY", "RO", "RS", "RU", "SA", "SE", "SV", "TR", "TZ", "UA",
            "UG", "US", "UY", "ZA", "ZW"
        )

        protected const val YT_CHARTS_ENDPOINT =
            "https://charts.youtube.com/youtubei/v1/browse?alt=json&$DISABLE_PRETTY_PRINT_PARAMETER"
    }
}
