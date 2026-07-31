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
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeChannelLinkHandlerFactory
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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.ArrayList
import java.util.stream.Collectors

/**
 * Extractor of YouTube lockup view models for stream items.
 *
 * The following features are currently not implemented:
 * - Shorts: appear in related items without a duration badge; getDuration() returns -1
 * - YouTube Premium Paid content
 */
open class YoutubeStreamInfoItemLockupExtractor(
    private val lockupViewModel: JsonObject,
    private val timeAgoParser: TimeAgoParser?
) : StreamInfoItemExtractor {

    private val cachedMetadataRows: JsonArray = lockupViewModel.getObject("metadata")!!
        .getObject("lockupMetadataViewModel")!!
        .getObject("metadata")!!
        .getObject("contentMetadataViewModel")!!
        .getArray("metadataRows")!!

    private var cachedStreamType: StreamType? = null
    private var cachedName: String? = null
    private var cachedDateText: String? = null

    private var cachedChannelImageViewModel: ChannelImageViewModel? = null

    protected open fun isChannelOrCoursePlaylistLockupItem(): Boolean = false

    @Throws(ParsingException::class)
    override fun getStreamType(): StreamType {
        cachedStreamType?.let { return it }
        cachedStreamType = determineStreamType()
        return cachedStreamType!!
    }

    @Throws(ParsingException::class)
    private fun determineStreamType(): StreamType {
        val overlays = JsonUtils.getArray(lockupViewModel, "contentImage.thumbnailViewModel.overlays")

        if (overlays.filterIsInstance<JsonObject>()
                .flatMap { overlay ->
                    (overlay.getObject("thumbnailOverlayBadgeViewModel")
                        ?.getArray("thumbnailBadges") ?: JsonArray(emptyList()))
                        .filterIsInstance<JsonObject>()
                }
                .map { it.getObject("thumbnailBadgeViewModel")!! }
                .any { vm ->
                    if ("THUMBNAIL_OVERLAY_BADGE_STYLE_LIVE" == vm.getString("badgeStyle")) {
                        return@any true
                    }
                    vm.getObject("icon")
                        ?.getArray("sources")
                        ?.filterIsInstance<JsonObject>()
                        ?.map { source ->
                            source.getObject("clientResource")?.getString("imageName")
                        }
                        ?.any { "LIVE" == it } ?: false
                }
        ) {
            return StreamType.LIVE_STREAM
        }

        if (overlays.filterIsInstance<JsonObject>()
                .flatMap { overlay ->
                    (overlay.getObject("thumbnailBottomOverlayViewModel")
                        ?.getArray("badges") ?: JsonArray(emptyList()))
                        .filterIsInstance<JsonObject>()
                }
                .map { it.getObject("thumbnailBadgeViewModel")!! }
                .any { vm ->
                    "THUMBNAIL_OVERLAY_BADGE_STYLE_LIVE" == vm.getString("badgeStyle")
                }
        ) {
            return StreamType.LIVE_STREAM
        }

        return StreamType.VIDEO_STREAM
    }

    @Throws(ParsingException::class)
    override fun isAd(): Boolean {
        val name = getName()
        return "[Private video]" == name || "[Deleted video]" == name
    }

    @Throws(ParsingException::class)
    override fun getUrl(): String {
        try {
            var videoId = lockupViewModel.getString("contentId")
            if (isNullOrEmpty(videoId)) {
                videoId = JsonUtils.getString(
                    lockupViewModel,
                    "rendererContext.commandContext.onTap.innertubeCommand.watchEndpoint.videoId"
                )
            }
            return YoutubeStreamLinkHandlerFactory.getInstance().getUrl(videoId)
        } catch (e: Exception) {
            throw ParsingException("Could not get url", e)
        }
    }

    @Throws(ParsingException::class)
    override fun getName(): String {
        cachedName?.let { return it }

        val name = JsonUtils.getString(lockupViewModel, "metadata.lockupMetadataViewModel.title.content")
        if (!isNullOrEmpty(name)) {
            cachedName = name
            return name
        }
        throw ParsingException("Could not get name")
    }

    @Throws(ParsingException::class)
    override fun getDuration(): Long {
        if (isLive() || isPremiere()) {
            return -1
        }

        val potentialDurations = JsonUtils.getArray(lockupViewModel, "contentImage.thumbnailViewModel.overlays")
            .filterIsInstance<JsonObject>()
            .flatMap { jsonObject ->
                (jsonObject.getObject("thumbnailBottomOverlayViewModel")
                    ?.getArray("badges") ?: JsonArray(emptyList()))
                    .filterIsInstance<JsonObject>()
            }
            .map { it.getObject("thumbnailBadgeViewModel")!!.getString("text") ?: "" }

        if (potentialDurations.isEmpty()) {
            return -1
        }

        var parsingException: ParsingException? = null
        for (potentialDuration in potentialDurations) {
            if (potentialDuration == null || !potentialDuration.matches(Regex(".*\\d.*"))) {
                continue
            }
            try {
                return YoutubeParsingHelper.parseDurationString(potentialDuration).toLong()
            } catch (ex: ParsingException) {
                parsingException = ex
            }
        }

        if (parsingException == null) {
            return -1
        }

        throw ParsingException("Could not get duration", parsingException)
    }

    @Throws(ParsingException::class)
    override fun getUploaderName(): String {
        val metadataRows = getMetadataPartsFromMetadataRows()
        if (metadataRows.isEmpty()) {
            throw ParsingException("Could not get uploader name: no metadata row")
        }

        val uploaderName = getTextContentFromMetadataPart(metadataRows[0].getObject(0)!!)
        if (isNullOrEmpty(uploaderName)) {
            throw ParsingException("Could not get uploader name")
        }

        return uploaderName!!
    }

    @Throws(ParsingException::class)
    override fun getUploaderUrl(): String {
        val innerTubeCommand = channelImageViewModel()
            .forUploaderUrlExtraction()
            .getObject("rendererContext")!!
            .getObject("commandContext")!!
            .getObject("onTap")!!
            .getObject("innertubeCommand")!!
        val browseEndpoint = innerTubeCommand.getObject("browseEndpoint")!!
        val channelId = browseEndpoint.getString("browseId")

        if (channelId != null && channelId.startsWith("UC")) {
            return YoutubeChannelLinkHandlerFactory.getInstance().getUrl("channel/$channelId")
        }

        val canonicalBaseUrl = browseEndpoint.getString("canonicalBaseUrl")
        if (!isNullOrEmpty(canonicalBaseUrl)) {
            return resolveUploaderUrlFromRelativeUrl(canonicalBaseUrl!!)
        }

        val webCommandMetadataUrl = innerTubeCommand.getObject("commandMetadata")!!
            .getObject("webCommandMetadata")!!
            .getString("url")
        if (!isNullOrEmpty(webCommandMetadataUrl)) {
            return resolveUploaderUrlFromRelativeUrl(webCommandMetadataUrl!!)
        }

        throw ParsingException("Could not get uploader url")
    }

    @Throws(ParsingException::class)
    private fun resolveUploaderUrlFromRelativeUrl(relativeUrl: String): String {
        return YoutubeChannelLinkHandlerFactory.getInstance().getUrl(
            if (relativeUrl.startsWith("/")) relativeUrl.substring(1) else relativeUrl
        )
    }

    @Throws(ParsingException::class)
    override fun getUploaderAvatars(): List<Image> {
        return YoutubeParsingHelper.getImagesFromThumbnailsArray(
            JsonUtils.getArray(channelImageViewModel().forAvatarExtraction(), "avatarViewModel.image.sources")
        )
    }

    @Throws(ParsingException::class)
    override fun isUploaderVerified(): Boolean {
        val metadataRows = getMetadataPartsFromMetadataRows()
        if (metadataRows.isEmpty()) {
            throw ParsingException("Could not get uploader verified status: no metadata row")
        }

        return YoutubeParsingHelper.hasArtistOrVerifiedIconBadgeAttachment(
            metadataRows[0].getObject(0)!!
                .getObject("text")!!
                .getArray("attachmentRuns")!!
        )
    }

    @Throws(ParsingException::class)
    override fun getTextualUploadDate(): String? {
        if (isLive()) {
            return null
        }

        val dateText = getDateText()

        if (isPremiere()) {
            return getDateFromPremiere(dateText)
        }

        return dateText
    }

    private fun getDateFromPremiere(dateText: String): String {
        return dateText.replace(PREMIERES_VIDEOS_TEXT, "").replace(PREMIERES_LIVES_TEXT, "")
    }

    @Throws(ParsingException::class)
    override fun getUploadDate(): DateWrapper? {
        if (timeAgoParser == null) {
            return null
        }

        val textualUploadDate = getTextualUploadDate() ?: return null

        if (isPremiere()) {
            val premiereDate = getDateFromPremiere(getDateText())
            try {
                val dateTime = LocalDateTime.parse(premiereDate, PREMIERES_DATE_FORMATTER)
                return DateWrapper(dateTime.atZone(ZoneOffset.UTC).toInstant(), false)
            } catch (e: DateTimeParseException) {
                throw ParsingException("Could not parse premiere upload date", e)
            }
        }

        return timeAgoParser.parse(textualUploadDate)
    }

    @Throws(ParsingException::class)
    override fun getViewCount(): Long {
        if (isChannelsMembersOnlyOrFirst()) {
            return -1
        }

        val metadataPartsRows = getMetadataPartsFromMetadataRows()
        if (metadataPartsRows.isEmpty()) {
            if (isLive() && isChannelOrCoursePlaylistLockupItem()) {
                return 0
            }
            throw ParsingException("Could not get view count: no metadata part from metadata rows")
        }

        if (isPremiere()) {
            return -1
        }

        if (isLive() && metadataPartsRows.size == 1 && !isChannelOrCoursePlaylistLockupItem()) {
            return 0
        }

        val metadataPartsRow = metadataPartsRows[metadataPartsRows.size - 1]
        if (metadataPartsRow.isEmpty()) {
            throw ParsingException("Could not get view count: no metadata part in the metadata parts array")
        }

        val viewCountText = getTextContentFromMetadataPart(metadataPartsRow.getObject(0)!!)
        if (isNullOrEmpty(viewCountText)) {
            throw ParsingException("Could not get view count")
        }
        return getViewCountFromViewCountText(viewCountText!!)
    }

    @Throws(NumberFormatException::class, ParsingException::class)
    private fun getViewCountFromViewCountText(viewCountText: String): Long {
        if (viewCountText.lowercase().contains(NO_VIEWS_LOWERCASE)) {
            return 0
        } else if (viewCountText.lowercase().contains("recommended")) {
            return -1
        }
        return Utils.mixedNumberWordToLong(viewCountText)
    }

    @Throws(ParsingException::class)
    override fun getThumbnails(): List<Image> {
        return YoutubeParsingHelper.getImagesFromThumbnailsArray(
            JsonUtils.getArray(lockupViewModel, "contentImage.thumbnailViewModel.image.sources")
        )
    }

    @Throws(ParsingException::class)
    override fun getContentAvailability(): ContentAvailability {
        if (isChannelsMembersOnlyOrFirst()) {
            return ContentAvailability.MEMBERSHIP
        }
        if (isLive()) {
            return ContentAvailability.AVAILABLE
        }
        if (isPremiere()) {
            return ContentAvailability.UPCOMING
        }
        return ContentAvailability.AVAILABLE
    }

    @Throws(ParsingException::class)
    private fun channelImageViewModel(): ChannelImageViewModel {
        if (cachedChannelImageViewModel == null) {
            cachedChannelImageViewModel = determineChannelImageViewModel()
        }
        return cachedChannelImageViewModel!!
    }

    @Throws(ParsingException::class)
    private fun determineChannelImageViewModel(): ChannelImageViewModel {
        val image = lockupViewModel.getObject("metadata")!!
            .getObject("lockupMetadataViewModel")!!
            .getObject("image")!!

        val single = image.getObject("decoratedAvatarViewModel")
        if (single != null) {
            return SingleChannelImageViewModel(single)
        }

        val multi = image.getObject("avatarStackViewModel")
        if (multi != null) {
            return MultiChannelImageViewModel(multi)
        }

        throw ParsingException("Failed to determine channel image view model")
    }

    private fun getTextContentFromMetadataPart(metadataPart: JsonObject): String? {
        return metadataPart.getObject("text")?.getString("content")
    }

    @Throws(ParsingException::class)
    private fun isLive(): Boolean {
        return getStreamType() != StreamType.VIDEO_STREAM
    }

    private fun isChannelsMembersOnlyOrFirst(): Boolean {
        return cachedMetadataRows.filterIsInstance<JsonObject>()
            .flatMap { jsonObject -> (jsonObject.getArray("badges") ?: JsonArray(emptyList())).filterIsInstance<JsonObject>() }
            .map { badge -> badge.getObject("badgeViewModel")?.getString("badgeStyle") }
            .any { "BADGE_MEMBERS_ONLY" == it }
    }

    @Throws(ParsingException::class)
    private fun isPremiere(): Boolean {
        val dateText = getDateText()
        return dateText.contains(PREMIERES_VIDEOS_TEXT) || dateText.contains(PREMIERES_LIVES_TEXT)
    }

    @Throws(ParsingException::class)
    private fun getDateText(): String {
        cachedDateText?.let { return it }

        val metadataPartsRows = getMetadataPartsFromMetadataRows()
        if (metadataPartsRows.isEmpty()) {
            throw ParsingException("Could not get date text: no metadata part from metadata rows")
        }

        val metadataPartsRow = metadataPartsRows[metadataPartsRows.size - 1]
        if (metadataPartsRow.isEmpty()) {
            throw ParsingException("Could not get date text: no metadata part in the metadata parts array")
        }

        cachedDateText = getTextContentFromMetadataPart(metadataPartsRow.getObject(metadataPartsRow.size - 1)!!)
        return cachedDateText!!
    }

    private fun getMetadataPartsFromMetadataRows(): List<JsonArray> {
        val metadataParts = ArrayList<JsonArray>()
        for (i in 0 until cachedMetadataRows.size) {
            val metadataRow = cachedMetadataRows.getObject(i)
            if (metadataRow != null && metadataRow.containsKey("metadataParts")) {
                metadataParts.add(metadataRow.getArray("metadataParts")!!)
            }
        }
        return metadataParts
    }

    abstract class ChannelImageViewModel(protected var viewModel: JsonObject) {
        abstract fun forUploaderUrlExtraction(): JsonObject
        abstract fun forAvatarExtraction(): JsonObject
    }

    class SingleChannelImageViewModel(viewModel: JsonObject) : ChannelImageViewModel(viewModel) {
        override fun forUploaderUrlExtraction(): JsonObject = viewModel
        override fun forAvatarExtraction(): JsonObject = viewModel.getObject("avatar")!!
    }

    class MultiChannelImageViewModel(viewModel: JsonObject) : ChannelImageViewModel(viewModel) {
        override fun forUploaderUrlExtraction(): JsonObject {
            return viewModel.getObject("rendererContext")!!
                .getObject("commandContext")!!
                .getObject("onTap")!!
                .getObject("innertubeCommand")!!
                .getObject("showDialogCommand")!!
                .getObject("panelLoadingStrategy")!!
                .getObject("inlineContent")!!
                .getObject("dialogViewModel")!!
                .getObject("customContent")!!
                .getObject("listViewModel")!!
                .getArray("listItems")!!
                .filterIsInstance<JsonObject>()
                .map { it.getObject("listItemViewModel")!! }
                .first()
        }

        override fun forAvatarExtraction(): JsonObject {
            return viewModel.getArray("avatars")!!.getObject(0)!!
        }
    }

    companion object {
        private const val NO_VIEWS_LOWERCASE = "no views"
        private const val PREMIERES_VIDEOS_TEXT = "Premieres "
        private const val PREMIERES_LIVES_TEXT = "Scheduled for "
        private val PREMIERES_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy, HH:mm")
    }
}
