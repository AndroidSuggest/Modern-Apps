package org.schabi.newpipe.extractor.services.youtube.extractors

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.localization.TimeAgoParser
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getImagesFromThumbnailsArray
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getTextFromObject
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getThumbnailsFromInfoItem
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getUrlFromNavigationEndpoint
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.utils.JsonUtils
import org.schabi.newpipe.extractor.utils.Utils
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern

class YoutubeStreamInfoItemExtractor(
    private val videoInfo: JsonObject,
    private val timeAgoParser: TimeAgoParser?
) : StreamInfoItemExtractor {

    private var cachedStreamType: StreamType? = null
    private var isPremiere: Boolean? = null

    @Throws(ParsingException::class)
    override fun getStreamType(): StreamType {
        cachedStreamType?.let { return it }

        val badges = videoInfo.getArray("badges")
        if (badges != null) {
            for (badge in badges) {
                if (badge !is JsonObject) continue
                val badgeRenderer = badge.getObject("metadataBadgeRenderer")
                if (badgeRenderer != null) {
                    if (badgeRenderer.getString("style", "") == "BADGE_STYLE_TYPE_LIVE_NOW" ||
                        badgeRenderer.getString("label", "") == "LIVE NOW"
                    ) {
                        cachedStreamType = StreamType.LIVE_STREAM
                        return cachedStreamType!!
                    }
                }
            }
        }

        for (overlay in (videoInfo.getArray("thumbnailOverlays") ?: JsonArray(emptyList()))) {
            if (overlay !is JsonObject) continue
            val style = overlay.getObject("thumbnailOverlayTimeStatusRenderer")
                ?.getString("style", "") ?: ""
            if (style.equals("LIVE", ignoreCase = true)) {
                cachedStreamType = StreamType.LIVE_STREAM
                return cachedStreamType!!
            }
        }

        cachedStreamType = StreamType.VIDEO_STREAM
        return cachedStreamType!!
    }

    @Throws(ParsingException::class)
    override fun isAd(): Boolean {
        return isPremium() || getName() == "[Private video]" || getName() == "[Deleted video]"
    }

    @Throws(ParsingException::class)
    override fun getUrl(): String {
        try {
            val videoId = videoInfo.getString("videoId")
            return YoutubeStreamLinkHandlerFactory.getInstance().getUrl(videoId)
        } catch (e: Exception) {
            throw ParsingException("Could not get url", e)
        }
    }

    @Throws(ParsingException::class)
    override fun getName(): String {
        val title = videoInfo.getObject("title")
        val name = getTextFromObject(title)
        if (!isNullOrEmpty(name)) {
            return name!!
        }
        if (title != null && !title.containsKey("runs") && title.isNotEmpty()) {
            return ""
        }
        throw ParsingException("Could not get name")
    }

    @Throws(ParsingException::class)
    override fun getDuration(): Long {
        if (getStreamType() == StreamType.LIVE_STREAM) {
            return -1
        }

        var duration = getTextFromObject(videoInfo.getObject("lengthText"))

        if (isNullOrEmpty(duration)) {
            duration = videoInfo.getString("lengthSeconds")

            if (isNullOrEmpty(duration)) {
                val timeOverlays = (videoInfo.getArray("thumbnailOverlays") ?: JsonArray(emptyList()))
                    .filterIsInstance<JsonObject>()
                    .filter { it.containsKey("thumbnailOverlayTimeStatusRenderer") }
                    .mapNotNull { thumbnailOverlay ->
                        getTextFromObject(
                            thumbnailOverlay.getObject("thumbnailOverlayTimeStatusRenderer")!!
                                .getObject("text")
                        )
                    }
                    .filter { !isNullOrEmpty(it) }

                for (timeOverlayText in timeOverlays) {
                    try {
                        return YoutubeParsingHelper.parseDurationString(timeOverlayText)
                    } catch (ex: ParsingException) {
                        // try next
                    }
                }
            }

            if (isNullOrEmpty(duration)) {
                if (isPremiere()) {
                    return -1
                }
                throw ParsingException("Could not get duration")
            }
        }

        return YoutubeParsingHelper.parseDurationString(duration)
    }

    @Throws(ParsingException::class)
    override fun getUploaderName(): String {
        var name = getTextFromObject(videoInfo.getObject("longBylineText"))

        if (isNullOrEmpty(name)) {
            name = getTextFromObject(videoInfo.getObject("ownerText"))
            if (isNullOrEmpty(name)) {
                name = getTextFromObject(videoInfo.getObject("shortBylineText"))
                if (isNullOrEmpty(name)) {
                    throw ParsingException("Could not get uploader name")
                }
            }
        }
        return name!!
    }

    @Throws(ParsingException::class)
    override fun getUploaderUrl(): String {
        var url = getUrlFromNavigationEndpoint(
            videoInfo.getObject("longBylineText")!!
                .getArray("runs")!!.getObject(0)!!
                .getObject("navigationEndpoint")!!
        )

        if (isNullOrEmpty(url)) {
            url = getUrlFromNavigationEndpoint(
                videoInfo.getObject("ownerText")!!
                    .getArray("runs")!!.getObject(0)!!
                    .getObject("navigationEndpoint")!!
            )

            if (isNullOrEmpty(url)) {
                url = getUrlFromNavigationEndpoint(
                    videoInfo.getObject("shortBylineText")!!
                        .getArray("runs")!!.getObject(0)!!
                        .getObject("navigationEndpoint")!!
                )

                if (isNullOrEmpty(url)) {
                    throw ParsingException("Could not get uploader url")
                }
            }
        }

        return url!!
    }

    @Throws(ParsingException::class)
    override fun getUploaderAvatars(): List<Image> {
        if (videoInfo.containsKey("channelThumbnailSupportedRenderers")) {
            return getImagesFromThumbnailsArray(
                JsonUtils.getArray(
                    videoInfo,
                    "channelThumbnailSupportedRenderers.channelThumbnailWithLinkRenderer.thumbnail.thumbnails"
                )
            )
        }

        if (videoInfo.containsKey("channelThumbnail")) {
            return getImagesFromThumbnailsArray(
                JsonUtils.getArray(videoInfo, "channelThumbnail.thumbnails")
            )
        }

        return emptyList()
    }

    @Throws(ParsingException::class)
    override fun isUploaderVerified(): Boolean {
        return YoutubeParsingHelper.isVerified(videoInfo.getArray("ownerBadges")!!)
    }

    @Throws(ParsingException::class)
    override fun getTextualUploadDate(): String? {
        if (getStreamType() == StreamType.LIVE_STREAM) {
            return null
        }

        if (isPremiere()) {
            val localDateTime = LocalDateTime.ofInstant(getInstantFromPremiere(), ZoneId.systemDefault())
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(localDateTime)
        }

        var publishedTimeText = getTextFromObject(videoInfo.getObject("publishedTimeText"))

        if (isNullOrEmpty(publishedTimeText) && videoInfo.containsKey("videoInfo")) {
            publishedTimeText = videoInfo.getObject("videoInfo")!!
                .getArray("runs")!!
                .getObject(2)!!
                .getString("text")
        }

        return if (isNullOrEmpty(publishedTimeText)) null else publishedTimeText
    }

    @Throws(ParsingException::class)
    override fun getUploadDate(): DateWrapper? {
        if (getStreamType() == StreamType.LIVE_STREAM) {
            return null
        }

        if (isPremiere()) {
            return DateWrapper(getInstantFromPremiere())
        }

        val textualUploadDate = getTextualUploadDate()
        if (timeAgoParser != null && !isNullOrEmpty(textualUploadDate)) {
            try {
                return timeAgoParser.parse(textualUploadDate)
            } catch (e: ParsingException) {
                throw ParsingException("Could not get upload date", e)
            }
        }
        return null
    }

    @Throws(ParsingException::class)
    override fun getViewCount(): Long {
        if (isPremium() || isPremiere()) {
            return -1
        }

        val viewCountText = getTextFromObject(videoInfo.getObject("viewCountText"))
        if (!isNullOrEmpty(viewCountText)) {
            try {
                return getViewCountFromViewCountText(viewCountText!!, false)
            } catch (ignored: Exception) {
            }
        }

        if (getStreamType() != StreamType.LIVE_STREAM) {
            try {
                return getViewCountFromAccessibilityData()
            } catch (ignored: Exception) {
            }
        }

        if (videoInfo.containsKey("videoInfo")) {
            try {
                return getViewCountFromViewCountText(
                    videoInfo.getObject("videoInfo")!!
                        .getArray("runs")!!
                        .getObject(0)!!
                        .getString("text", "")!!, true
                )
            } catch (ignored: Exception) {
            }
        }

        if (videoInfo.containsKey("shortViewCountText")) {
            try {
                val shortViewCountText = getTextFromObject(videoInfo.getObject("shortViewCountText"))
                if (!isNullOrEmpty(shortViewCountText)) {
                    return getViewCountFromViewCountText(shortViewCountText!!, true)
                }
            } catch (ignored: Exception) {
            }
        }

        return -1
    }

    @Throws(NumberFormatException::class, ParsingException::class)
    private fun getViewCountFromViewCountText(viewCountText: String, isMixedNumber: Boolean): Long {
        if (viewCountText.lowercase().contains(NO_VIEWS_LOWERCASE)) {
            return 0
        } else if (viewCountText.lowercase().contains("recommended")) {
            return -1
        }

        return if (isMixedNumber) Utils.mixedNumberWordToLong(viewCountText)
        else java.lang.Long.parseLong(Utils.removeNonDigitCharacters(viewCountText))
    }

    @Throws(NumberFormatException::class, org.schabi.newpipe.extractor.utils.Parser.RegexException::class)
    private fun getViewCountFromAccessibilityData(): Long {
        val videoInfoTitleAccessibilityData = videoInfo.getObject("title")!!
            .getObject("accessibility")!!
            .getObject("accessibilityData")!!
            .getString("label", "") ?: ""

        if (videoInfoTitleAccessibilityData.lowercase().endsWith(NO_VIEWS_LOWERCASE)) {
            return 0
        }

        return java.lang.Long.parseLong(
            Utils.removeNonDigitCharacters(
                org.schabi.newpipe.extractor.utils.Parser.matchGroup1(
                    ACCESSIBILITY_DATA_VIEW_COUNT_REGEX,
                    videoInfoTitleAccessibilityData
                )
            )
        )
    }

    @Throws(ParsingException::class)
    override fun getThumbnails(): List<Image> {
        return getThumbnailsFromInfoItem(videoInfo)
    }

    private fun isPremium(): Boolean {
        val badges = videoInfo.getArray("badges") ?: return false
        for (badge in badges) {
            if ((badge as? JsonObject)?.getObject("metadataBadgeRenderer")
                    ?.getString("label", "") == "Premium"
            ) {
                return true
            }
        }
        return false
    }

    private fun isPremiere(): Boolean {
        if (isPremiere == null) {
            isPremiere = videoInfo.containsKey("upcomingEventData")
        }
        return isPremiere!!
    }

    @Throws(ParsingException::class)
    private fun getInstantFromPremiere(): java.time.Instant {
        val upcomingEventData = videoInfo.getObject("upcomingEventData")!!
        val startTime = upcomingEventData.getString("startTime")!!

        try {
            return java.time.Instant.ofEpochSecond(startTime.toLong())
        } catch (e: Exception) {
            throw ParsingException("Could not parse date from premiere: \"$startTime\"", e)
        }
    }

    @Throws(ParsingException::class)
    override fun getShortDescription(): String? {
        if (videoInfo.containsKey("detailedMetadataSnippets")) {
            return getTextFromObject(
                videoInfo.getArray("detailedMetadataSnippets")!!
                    .getObject(0)!!
                    .getObject("snippetText")
            )
        }

        if (videoInfo.containsKey("descriptionSnippet")) {
            return getTextFromObject(videoInfo.getObject("descriptionSnippet"))
        }

        return null
    }

    @Throws(ParsingException::class)
    override fun isShortFormContent(): Boolean {
        try {
            val webPageType = videoInfo.getObject("navigationEndpoint")!!
                .getObject("commandMetadata")!!.getObject("webCommandMetadata")!!
                .getString("webPageType")

            var isShort = !isNullOrEmpty(webPageType) && webPageType == "WEB_PAGE_TYPE_SHORTS"

            if (!isShort) {
                isShort = videoInfo.getObject("navigationEndpoint")!!.containsKey("reelWatchEndpoint")
            }

            if (!isShort) {
                if (videoInfo.containsKey("thumbnailOverlays")) {
                    isShort = (videoInfo.getArray("thumbnailOverlays") ?: JsonArray(emptyList()))
                        .filterIsInstance<JsonObject>()
                        .filter { it.containsKey("thumbnailOverlayTimeStatusRenderer") }
                        .map { it.getObject("thumbnailOverlayTimeStatusRenderer")!! }
                        .any { timeOverlay ->
                            timeOverlay.getString("style", "")
                                .equals("SHORTS", ignoreCase = true) ||
                                timeOverlay.getObject("icon")!!
                                    .getString("iconType", "")!!
                                    .lowercase()
                                    .contains("shorts")
                        }
                }
            }

            return isShort
        } catch (e: Exception) {
            throw ParsingException("Could not determine if this is short-form content", e)
        }
    }

    private fun isMembersOnly(): Boolean {
        return (videoInfo.getArray("badges") ?: JsonArray(emptyList()))
            .filterIsInstance<JsonObject>()
            .map { it.getObject("metadataBadgeRenderer")!!.getString("style") }
            .any { "BADGE_STYLE_TYPE_MEMBERS_ONLY" == it }
    }

    @Throws(ParsingException::class)
    override fun getContentAvailability(): ContentAvailability {
        if (isPremiere()) return ContentAvailability.UPCOMING
        if (isMembersOnly()) return ContentAvailability.MEMBERSHIP
        if (isPremium()) return ContentAvailability.PAID
        return ContentAvailability.AVAILABLE
    }

    companion object {
        private val ACCESSIBILITY_DATA_VIEW_COUNT_REGEX = Pattern.compile("([\\d,]+) views$")
        private const val NO_VIEWS_LOWERCASE = "no views"
    }
}
