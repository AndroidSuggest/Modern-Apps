package org.schabi.newpipe.extractor.stream

import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.Info
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MetaInfo
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.utils.ExtractorHelper
import org.schabi.newpipe.extractor.utils.ExtractorLogger
import org.schabi.newpipe.extractor.utils.Utils
import java.io.IOException
import java.util.Locale

open class StreamInfo : Info {

    class StreamExtractException(message: String) : ExtractionException(message)

    private var streamType: StreamType
    private var thumbnails: List<Image> = emptyList()
    private var textualUploadDate: String? = null
    private var uploadDate: DateWrapper? = null
    private var duration: Long = -1
    private var ageLimit: Int
    private var description: Description? = null

    private var viewCount: Long = -1
    private var likeCount: Long = -1
    private var dislikeCount: Long = -1

    private var uploaderName: String = ""
    private var uploaderUrl: String = ""
    private var uploaderAvatars: List<Image> = emptyList()
    private var uploaderVerified: Boolean = false
    private var uploaderSubscriberCount: Long = -1

    private var subChannelName: String = ""
    private var subChannelUrl: String = ""
    private var subChannelAvatars: List<Image> = emptyList()

    private var videoStreams: List<VideoStream> = emptyList()
    private var audioStreams: List<AudioStream> = emptyList()
    private var videoOnlyStreams: List<VideoStream> = emptyList()

    private var dashMpdUrl: String = ""
    private var hlsUrl: String = ""
    private var relatedItems: List<InfoItem> = emptyList()

    private var startPosition: Long = 0
    private var subtitles: List<SubtitlesStream> = emptyList()

    private var host: String = ""
    private var privacy: StreamExtractor.Privacy? = null
    private var category: String = ""
    private var licence: String = ""
    private var supportInfo: String = ""
    private var language: Locale? = null
    private var tags: List<String> = emptyList()
    private var streamSegments: List<StreamSegment> = emptyList()
    private var metaInfo: List<MetaInfo> = emptyList()
    private var shortFormContent: Boolean = false
    private var contentAvailability: ContentAvailability = ContentAvailability.AVAILABLE
    private var previewFrames: List<Frameset> = emptyList()

    constructor(
        serviceId: Int,
        url: String,
        originalUrl: String,
        streamType: StreamType,
        id: String,
        name: String,
        ageLimit: Int
    ) : super(serviceId, id, url, originalUrl, name) {
        this.streamType = streamType
        this.ageLimit = ageLimit
        ExtractorLogger.d(TAG, "Created {}", this)
    }

    override fun toString(): String =
        "$TAG[serviceId=${serviceId}, url='${url}', originalUrl='${originalUrl}', " +
                "id='${id}', name='${name}', streamType=$streamType, ageLimit=$ageLimit]"

    fun getStreamType(): StreamType = streamType
    fun setStreamType(streamType: StreamType) { this.streamType = streamType }

    fun getThumbnails(): List<Image> = thumbnails
    fun setThumbnails(thumbnails: List<Image>) { this.thumbnails = thumbnails }

    fun getTextualUploadDate(): String? = textualUploadDate
    fun setTextualUploadDate(textualUploadDate: String?) { this.textualUploadDate = textualUploadDate }

    fun getUploadDate(): DateWrapper? = uploadDate
    fun setUploadDate(uploadDate: DateWrapper?) { this.uploadDate = uploadDate }

    fun getDuration(): Long = duration
    fun setDuration(duration: Long) { this.duration = duration }

    fun getAgeLimit(): Int = ageLimit
    fun setAgeLimit(ageLimit: Int) { this.ageLimit = ageLimit }

    fun getDescription(): Description? = description
    fun setDescription(description: Description?) { this.description = description }

    fun getViewCount(): Long = viewCount
    fun setViewCount(viewCount: Long) { this.viewCount = viewCount }

    fun getLikeCount(): Long = likeCount
    fun setLikeCount(likeCount: Long) { this.likeCount = likeCount }

    fun getDislikeCount(): Long = dislikeCount
    fun setDislikeCount(dislikeCount: Long) { this.dislikeCount = dislikeCount }

    fun getUploaderName(): String = uploaderName
    fun setUploaderName(uploaderName: String) { this.uploaderName = uploaderName }

    fun getUploaderUrl(): String = uploaderUrl
    fun setUploaderUrl(uploaderUrl: String) { this.uploaderUrl = uploaderUrl }

    fun getUploaderAvatars(): List<Image> = uploaderAvatars
    fun setUploaderAvatars(uploaderAvatars: List<Image>) { this.uploaderAvatars = uploaderAvatars }

    fun isUploaderVerified(): Boolean = uploaderVerified
    fun setUploaderVerified(uploaderVerified: Boolean) { this.uploaderVerified = uploaderVerified }

    fun getUploaderSubscriberCount(): Long = uploaderSubscriberCount
    fun setUploaderSubscriberCount(uploaderSubscriberCount: Long) { this.uploaderSubscriberCount = uploaderSubscriberCount }

    fun getSubChannelName(): String = subChannelName
    fun setSubChannelName(subChannelName: String) { this.subChannelName = subChannelName }

    fun getSubChannelUrl(): String = subChannelUrl
    fun setSubChannelUrl(subChannelUrl: String) { this.subChannelUrl = subChannelUrl }

    fun getSubChannelAvatars(): List<Image> = subChannelAvatars
    fun setSubChannelAvatars(subChannelAvatars: List<Image>) { this.subChannelAvatars = subChannelAvatars }

    fun getVideoStreams(): List<VideoStream> = videoStreams
    fun setVideoStreams(videoStreams: List<VideoStream>) { this.videoStreams = videoStreams }

    fun getAudioStreams(): List<AudioStream> = audioStreams
    fun setAudioStreams(audioStreams: List<AudioStream>) { this.audioStreams = audioStreams }

    fun getVideoOnlyStreams(): List<VideoStream> = videoOnlyStreams
    fun setVideoOnlyStreams(videoOnlyStreams: List<VideoStream>) { this.videoOnlyStreams = videoOnlyStreams }

    fun getDashMpdUrl(): String = dashMpdUrl
    fun setDashMpdUrl(dashMpdUrl: String) { this.dashMpdUrl = dashMpdUrl }

    fun getHlsUrl(): String = hlsUrl
    fun setHlsUrl(hlsUrl: String) { this.hlsUrl = hlsUrl }

    fun getRelatedItems(): List<InfoItem> = relatedItems

    @Deprecated("Use getRelatedItems()")
    fun getRelatedStreams(): List<InfoItem> = getRelatedItems()

    fun setRelatedItems(relatedItems: List<InfoItem>) { this.relatedItems = relatedItems }

    @Deprecated("Use setRelatedItems")
    fun setRelatedStreams(relatedItemsToSet: List<InfoItem>) { setRelatedItems(relatedItemsToSet) }

    fun getStartPosition(): Long = startPosition
    fun setStartPosition(startPosition: Long) { this.startPosition = startPosition }

    fun getSubtitles(): List<SubtitlesStream> = subtitles
    fun setSubtitles(subtitles: List<SubtitlesStream>) { this.subtitles = subtitles }

    fun getHost(): String = host
    fun setHost(host: String) { this.host = host }

    fun getPrivacy(): StreamExtractor.Privacy? = privacy
    fun setPrivacy(privacy: StreamExtractor.Privacy?) { this.privacy = privacy }

    fun getCategory(): String = category
    fun setCategory(category: String) { this.category = category }

    fun getLicence(): String = licence
    fun setLicence(licence: String) { this.licence = licence }

    fun getLanguageInfo(): Locale? = language
    fun setLanguageInfo(locale: Locale?) { this.language = locale }

    fun getTags(): List<String> = tags
    fun setTags(tags: List<String>) { this.tags = tags }

    fun setSupportInfo(support: String) { this.supportInfo = support }
    fun getSupportInfo(): String = supportInfo

    fun getStreamSegments(): List<StreamSegment> = streamSegments
    fun setStreamSegments(streamSegments: List<StreamSegment>) { this.streamSegments = streamSegments }

    fun setMetaInfo(metaInfo: List<MetaInfo>) { this.metaInfo = metaInfo }

    fun getPreviewFrames(): List<Frameset> = previewFrames
    fun setPreviewFrames(previewFrames: List<Frameset>) { this.previewFrames = previewFrames }

    fun getMetaInfo(): List<MetaInfo> = metaInfo

    fun isShortFormContent(): Boolean = shortFormContent
    fun setShortFormContent(isShortFormContent: Boolean) { this.shortFormContent = isShortFormContent }

    fun getContentAvailability(): ContentAvailability = contentAvailability
    fun setContentAvailability(availability: ContentAvailability) { this.contentAvailability = availability }

    companion object {
        private val TAG = StreamInfo::class.java.simpleName

        @JvmStatic
        @Throws(IOException::class, ExtractionException::class)
        fun getInfo(url: String): StreamInfo {
            ExtractorLogger.d(TAG, "getInfo({url})", url)
            return getInfo(NewPipe.getServiceByUrl(url), url)
        }

        @JvmStatic
        @Throws(IOException::class, ExtractionException::class)
        fun getInfo(service: StreamingService, url: String): StreamInfo {
            ExtractorLogger.d(TAG, "getInfo({service},{url})", service, url)
            return getInfo(service.getStreamExtractor(url))
        }

        @JvmStatic
        @Throws(ExtractionException::class, IOException::class)
        fun getInfo(extractor: StreamExtractor): StreamInfo {
            ExtractorLogger.d(TAG, "getInfo({extractor})", extractor)
            extractor.fetchPage()
            val streamInfo: StreamInfo
            try {
                streamInfo = extractImportantData(extractor)
                extractStreams(streamInfo, extractor)
                extractOptionalData(streamInfo, extractor)
                return streamInfo
            } catch (e: ExtractionException) {
                val errorMessage = extractor.getErrorMessage()
                if (Utils.isNullOrEmpty(errorMessage)) {
                    throw e
                } else {
                    throw ContentNotAvailableException(errorMessage, e)
                }
            }
        }

        private fun extractImportantData(extractor: StreamExtractor): StreamInfo {
            val url = extractor.getUrl()
            val streamType = extractor.getStreamType()
            val id = extractor.getId()
            val name = extractor.getName()
            val ageLimit = extractor.getAgeLimit()

            if (streamType == StreamType.NONE
                || Utils.isNullOrEmpty(url)
                || Utils.isNullOrEmpty(id)
                || name == null
                || ageLimit == -1
            ) {
                throw ExtractionException("Some important stream information was not given.")
            }

            return StreamInfo(
                extractor.getServiceId(), url, extractor.getOriginalUrl(),
                streamType, id, name, ageLimit
            )
        }

        private fun extractStreams(streamInfo: StreamInfo, extractor: StreamExtractor) {
            try {
                streamInfo.setDashMpdUrl(extractor.getDashMpdUrl())
            } catch (e: Exception) {
                streamInfo.addError(ExtractionException("Couldn't get DASH manifest", e))
            }
            try {
                streamInfo.setHlsUrl(extractor.getHlsUrl())
            } catch (e: Exception) {
                streamInfo.addError(ExtractionException("Couldn't get HLS manifest", e))
            }
            try {
                streamInfo.setAudioStreams(extractor.getAudioStreams())
            } catch (e: ContentNotSupportedException) {
                throw e
            } catch (e: Exception) {
                streamInfo.addError(ExtractionException("Couldn't get audio streams", e))
            }
            try {
                streamInfo.setVideoStreams(extractor.getVideoStreams())
            } catch (e: Exception) {
                streamInfo.addError(ExtractionException("Couldn't get video streams", e))
            }
            try {
                streamInfo.setVideoOnlyStreams(extractor.getVideoOnlyStreams())
            } catch (e: Exception) {
                streamInfo.addError(ExtractionException("Couldn't get video only streams", e))
            }

            if (streamInfo.videoStreams.isEmpty() && streamInfo.audioStreams.isEmpty()
                && Utils.isNullOrEmpty(streamInfo.dashMpdUrl) && Utils.isNullOrEmpty(streamInfo.hlsUrl)
            ) {
                throw StreamExtractException(
                    "Could not get any stream. See error variable to get further details."
                )
            }
        }

        private fun extractOptionalData(streamInfo: StreamInfo, extractor: StreamExtractor) {
            try { streamInfo.setThumbnails(extractor.getThumbnails()) } catch (e: Exception) { streamInfo.addError(e) }
            try { streamInfo.setDuration(extractor.getLength()) } catch (e: Exception) { streamInfo.addError(e) }
            try { streamInfo.setUploaderName(extractor.getUploaderName()) } catch (e: Exception) { streamInfo.addError(e) }
            try { streamInfo.setUploaderUrl(extractor.getUploaderUrl()) } catch (e: Exception) { streamInfo.addError(e) }
            try { streamInfo.setUploaderAvatars(extractor.getUploaderAvatars()) } catch (e: Exception) { streamInfo.addError(e) }
            try { streamInfo.setUploaderVerified(extractor.isUploaderVerified()) } catch (e: Exception) { streamInfo.addError(e) }
            try { streamInfo.setUploaderSubscriberCount(extractor.getUploaderSubscriberCount()) } catch (e: Exception) { streamInfo.addError(e) }
            try { streamInfo.setSubChannelName(extractor.getSubChannelName()) } catch (e: Exception) { streamInfo.addError(e) }
            try { streamInfo.setSubChannelUrl(extractor.getSubChannelUrl()) } catch (e: Exception) { streamInfo.addError(e) }
            try { streamInfo.setSubChannelAvatars(extractor.getSubChannelAvatars()) } catch (e: Exception) { streamInfo.addError(e) }
            try { streamInfo.setDescription(extractor.getDescription()) } catch (e: Exception) { streamInfo.addError(e) }
            try { streamInfo.setViewCount(extractor.getViewCount()) } catch (e: Exception) { streamInfo.addError(e) }
            try { streamInfo.setTextualUploadDate(extractor.getTextualUploadDate()) } catch (e: Exception) { streamInfo.addError(e) }
            try { streamInfo.setUploadDate(extractor.getUploadDate()) } catch (e: Exception) { streamInfo.addError(e) }
            try { streamInfo.setStartPosition(extractor.getTimeStamp()) } catch (e: Exception) { streamInfo.addError(e) }
            try { streamInfo.setLikeCount(extractor.getLikeCount()) } catch (e: Exception) { streamInfo.addError(e) }
            try { streamInfo.setDislikeCount(extractor.getDislikeCount()) } catch (e: Exception) { streamInfo.addError(e) }
            try { streamInfo.setSubtitles(extractor.getSubtitlesDefault()) } catch (e: Exception) { streamInfo.addError(e) }
            try { streamInfo.setHost(extractor.getHost()) } catch (e: Exception) { streamInfo.addError(e) }
            try { streamInfo.setPrivacy(extractor.getPrivacy()) } catch (e: Exception) { streamInfo.addError(e) }
            try { streamInfo.setCategory(extractor.getCategory()) } catch (e: Exception) { streamInfo.addError(e) }
            try { streamInfo.setLicence(extractor.getLicence()) } catch (e: Exception) { streamInfo.addError(e) }
            try { streamInfo.setLanguageInfo(extractor.getLanguageInfo()) } catch (e: Exception) { streamInfo.addError(e) }
            try { streamInfo.setTags(extractor.getTags()) } catch (e: Exception) { streamInfo.addError(e) }
            try { streamInfo.setSupportInfo(extractor.getSupportInfo()) } catch (e: Exception) { streamInfo.addError(e) }
            try { streamInfo.setStreamSegments(extractor.getStreamSegments()) } catch (e: Exception) { streamInfo.addError(e) }
            try { streamInfo.setMetaInfo(extractor.getMetaInfo()) } catch (e: Exception) { streamInfo.addError(e) }
            try { streamInfo.setPreviewFrames(extractor.getFrames()) } catch (e: Exception) { streamInfo.addError(e) }
            try { streamInfo.setShortFormContent(extractor.isShortFormContent()) } catch (e: Exception) { streamInfo.addError(e) }
            try { streamInfo.setContentAvailability(extractor.getContentAvailability()) } catch (e: Exception) { streamInfo.addError(e) }

            streamInfo.setRelatedItems(ExtractorHelper.getRelatedItemsOrLogError(streamInfo, extractor))
        }
    }
}
