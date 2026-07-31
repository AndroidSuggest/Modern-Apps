package org.schabi.newpipe.extractor.stream

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import java.io.Serializable

class VideoStream : Stream {

    companion object {
        const val RESOLUTION_UNKNOWN = ""
    }

    @Deprecated("Use getResolution() instead.")
    val resolution: String

    @Deprecated("Use isVideoOnly() instead.")
    val isVideoOnly: Boolean

    private var itag: Int = ITAG_NOT_AVAILABLE_OR_NOT_APPLICABLE
    private var bitrate: Int = 0
    private var initStart: Int = 0
    private var initEnd: Int = 0
    private var indexStart: Int = 0
    private var indexEnd: Int = 0
    private var width: Int = 0
    private var height: Int = 0
    private var fps: Int = 0
    private var quality: String? = null
    private var codec: String? = null
    private var itagItem: ItagItem? = null

    class Builder {
        var id: String? = null; private set
        var content: String? = null; private set
        var isUrl: Boolean = false; private set
        var deliveryMethod: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP; private set
        var mediaFormat: MediaFormat? = null; private set
        var manifestUrl: String? = null; private set
        var isVideoOnly: Boolean? = null; private set
        var resolution: String? = null; private set
        var itagItem: ItagItem? = null; private set
        var deliveryMethodInfo: Serializable? = null; private set

        fun setDeliveryMethodInfo(deliveryMethodInfo: Serializable?): Builder { this.deliveryMethodInfo = deliveryMethodInfo; return this }
        fun setId(id: String): Builder { this.id = id; return this }
        fun setContent(content: String, isUrl: Boolean): Builder { this.content = content; this.isUrl = isUrl; return this }
        fun setMediaFormat(mediaFormat: MediaFormat?): Builder { this.mediaFormat = mediaFormat; return this }
        fun setDeliveryMethod(deliveryMethod: DeliveryMethod): Builder { this.deliveryMethod = deliveryMethod; return this }
        fun setManifestUrl(manifestUrl: String?): Builder { this.manifestUrl = manifestUrl; return this }
        fun setIsVideoOnly(isVideoOnly: Boolean): Builder { this.isVideoOnly = isVideoOnly; return this }
        fun setResolution(resolution: String): Builder { this.resolution = resolution; return this }
        fun setItagItem(itagItem: ItagItem?): Builder { this.itagItem = itagItem; return this }

        fun build(): VideoStream {
            if (id == null) throw IllegalStateException(
                "The identifier of the video stream has been not set or is null. If you are not able to get an identifier, use the static constant ID_UNKNOWN of the Stream class."
            )
            if (content == null) throw IllegalStateException(
                "The content of the video stream has been not set or is null. Please specify a non-null one with setContent."
            )
            if (deliveryMethod == null) throw IllegalStateException(
                "The delivery method of the video stream has been set as null, which is not allowed. Pass a valid one instead with setDeliveryMethod."
            )
            if (isVideoOnly == null) throw IllegalStateException(
                "The video stream has been not set as a video-only stream or as a video stream with embedded audio. Please specify this information with setIsVideoOnly."
            )
            if (resolution == null) throw IllegalStateException(
                "The resolution of the video stream has been not set. Please specify it with setResolution (use an empty string if you are not able to get it)."
            )
            return VideoStream(
                id!!, content!!, isUrl, mediaFormat, deliveryMethod, resolution!!,
                isVideoOnly!!, manifestUrl, itagItem, deliveryMethodInfo
            )
        }
    }

    private constructor(
        id: String,
        content: String,
        isUrl: Boolean,
        format: MediaFormat?,
        deliveryMethod: DeliveryMethod,
        resolution: String,
        isVideoOnly: Boolean,
        manifestUrl: String?,
        itagItem: ItagItem?,
        deliveryMethodInfo: Serializable?
    ) : super(id, content, isUrl, format, deliveryMethod, manifestUrl, deliveryMethodInfo) {
        if (itagItem != null) {
            this.itagItem = itagItem
            this.itag = itagItem.id
            this.bitrate = itagItem.bitrate
            this.initStart = itagItem.initStart
            this.initEnd = itagItem.initEnd
            this.indexStart = itagItem.indexStart
            this.indexEnd = itagItem.indexEnd
            this.codec = itagItem.codec
            this.height = itagItem.height
            this.width = itagItem.width
            this.quality = itagItem.quality
            this.fps = itagItem.fps
        }
        this.resolution = resolution
        this.isVideoOnly = isVideoOnly
    }

    override fun equalStats(cmp: Stream): Boolean {
        return super.equalStats(cmp) && cmp is VideoStream
                && resolution == cmp.resolution
                && isVideoOnly == cmp.isVideoOnly
    }

    fun getResolution(): String = resolution
    fun isVideoOnly(): Boolean = isVideoOnly
    fun getItag(): Int = itag
    fun getBitrate(): Int = bitrate
    fun getInitStart(): Int = initStart
    fun getInitEnd(): Int = initEnd
    fun getIndexStart(): Int = indexStart
    fun getIndexEnd(): Int = indexEnd
    fun getWidth(): Int = width
    fun getHeight(): Int = height
    fun getFps(): Int = fps
    fun getQuality(): String? = quality
    fun getCodec(): String? = codec
    override fun getItagItem(): ItagItem? = itagItem
}
