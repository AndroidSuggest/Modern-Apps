package org.schabi.newpipe.extractor.stream

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.utils.Utils
import java.io.Serializable

abstract class Stream : Serializable {

    companion object {
        const val FORMAT_ID_UNKNOWN = -1
        const val ID_UNKNOWN = " "
        const val ITAG_NOT_AVAILABLE_OR_NOT_APPLICABLE = -1

        @JvmStatic
        fun containSimilarStream(stream: Stream, streamList: List<Stream>?): Boolean {
            if (Utils.isNullOrEmpty(streamList)) return false
            for (cmpStream in streamList!!) {
                if (stream.equalStats(cmpStream)) return true
            }
            return false
        }
    }

    private val id: String
    private val mediaFormat: MediaFormat?
    private val content: String
    private val isUrl: Boolean
    private val deliveryMethod: DeliveryMethod
    private val manifestUrl: String?
    private val deliveryMethodInfo: Serializable?

    constructor(
        id: String,
        content: String,
        isUrl: Boolean,
        format: MediaFormat?,
        deliveryMethod: DeliveryMethod,
        manifestUrl: String?
    ) : this(id, content, isUrl, format, deliveryMethod, manifestUrl, null)

    constructor(
        id: String,
        content: String,
        isUrl: Boolean,
        format: MediaFormat?,
        deliveryMethod: DeliveryMethod,
        manifestUrl: String?,
        deliveryMethodInfo: Serializable?
    ) {
        this.id = id
        this.content = content
        this.isUrl = isUrl
        this.mediaFormat = format
        this.deliveryMethod = deliveryMethod
        this.manifestUrl = manifestUrl
        this.deliveryMethodInfo = deliveryMethodInfo
    }

    open fun equalStats(other: Stream?): Boolean {
        return other != null && mediaFormat != null && other.mediaFormat != null
                && mediaFormat.id == other.mediaFormat!!.id
                && deliveryMethod == other.deliveryMethod
                && isUrl == other.isUrl
    }

    fun getId(): String = id

    @Deprecated("Use getContent() instead.")
    fun getUrl(): String? = if (isUrl) content else null

    fun getContent(): String = content

    fun isUrl(): Boolean = isUrl

    fun getFormat(): MediaFormat? = mediaFormat

    fun getFormatId(): Int = mediaFormat?.id ?: FORMAT_ID_UNKNOWN

    fun getDeliveryMethod(): DeliveryMethod = deliveryMethod

    fun getDeliveryMethodInfo(): Serializable? = deliveryMethodInfo

    fun getManifestUrl(): String? = manifestUrl

    abstract fun getItagItem(): ItagItem?
}
