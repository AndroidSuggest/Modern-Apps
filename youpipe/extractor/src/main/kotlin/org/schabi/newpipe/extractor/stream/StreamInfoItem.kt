package org.schabi.newpipe.extractor.stream

import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.localization.DateWrapper

open class StreamInfoItem(
    serviceId: Int,
    url: String,
    name: String,
    private val streamType: StreamType
) : InfoItem(InfoType.STREAM, serviceId, url, name) {

    private var uploaderName: String? = null
    private var shortDescription: String? = null
    private var textualUploadDate: String? = null
    private var uploadDate: DateWrapper? = null
    private var viewCount: Long = -1
    private var duration: Long = -1
    private var uploaderUrl: String? = null
    private var uploaderAvatars: List<Image> = emptyList()
    private var uploaderVerified: Boolean = false
    private var shortFormContent: Boolean = false
    private var contentAvailability: ContentAvailability = ContentAvailability.AVAILABLE

    fun getStreamType(): StreamType = streamType

    fun getUploaderName(): String? = uploaderName
    fun setUploaderName(uploaderName: String?) {
        this.uploaderName = uploaderName
    }

    fun getViewCount(): Long = viewCount
    fun setViewCount(viewCount: Long) {
        this.viewCount = viewCount
    }

    fun getDuration(): Long = duration
    fun setDuration(duration: Long) {
        this.duration = duration
    }

    fun getUploaderUrl(): String? = uploaderUrl
    fun setUploaderUrl(uploaderUrl: String?) {
        this.uploaderUrl = uploaderUrl
    }

    fun getUploaderAvatars(): List<Image> = uploaderAvatars
    fun setUploaderAvatars(uploaderAvatars: List<Image>) {
        this.uploaderAvatars = uploaderAvatars
    }

    fun getShortDescription(): String? = shortDescription
    fun setShortDescription(shortDescription: String?) {
        this.shortDescription = shortDescription
    }

    fun getTextualUploadDate(): String? = textualUploadDate
    fun setTextualUploadDate(textualUploadDate: String?) {
        this.textualUploadDate = textualUploadDate
    }

    fun getUploadDate(): DateWrapper? = uploadDate
    fun setUploadDate(uploadDate: DateWrapper?) {
        this.uploadDate = uploadDate
    }

    fun isUploaderVerified(): Boolean = uploaderVerified
    fun setUploaderVerified(uploaderVerified: Boolean) {
        this.uploaderVerified = uploaderVerified
    }

    fun isShortFormContent(): Boolean = shortFormContent
    fun setShortFormContent(shortFormContent: Boolean) {
        this.shortFormContent = shortFormContent
    }

    fun getContentAvailability(): ContentAvailability = contentAvailability
    fun setContentAvailability(availability: ContentAvailability) {
        this.contentAvailability = availability
    }

    override fun toString(): String =
        "StreamInfoItem{streamType=$streamType, uploaderName='$uploaderName', " +
                "textualUploadDate='$textualUploadDate', viewCount=$viewCount, duration=$duration, " +
                "uploaderUrl='$uploaderUrl', infoType=${infoType}, serviceId=${serviceId}, " +
                "url='${url}', name='${name}', thumbnails='${thumbnails}', " +
                "uploaderVerified='${isUploaderVerified()}'}"
}
