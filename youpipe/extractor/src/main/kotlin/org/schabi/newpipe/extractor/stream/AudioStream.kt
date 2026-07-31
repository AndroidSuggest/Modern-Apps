package org.schabi.newpipe.extractor.stream

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import java.io.Serializable
import java.util.Locale
import java.util.Objects

class AudioStream : Stream {

    companion object {
        const val UNKNOWN_BITRATE = -1
    }

    private val averageBitrate: Int

    private var itag: Int = ITAG_NOT_AVAILABLE_OR_NOT_APPLICABLE
    private var bitrate: Int = 0
    private var initStart: Int = 0
    private var initEnd: Int = 0
    private var indexStart: Int = 0
    private var indexEnd: Int = 0
    private var quality: String? = null
    private var codec: String? = null

    private val audioTrackId: String?
    private val audioTrackName: String?
    private val audioLocale: Locale?
    private val audioTrackType: AudioTrackType?

    private var itagItem: ItagItem? = null

    class Builder {
        var id: String? = null
            private set
        var content: String? = null
            private set
        var isUrl: Boolean = false
            private set
        var deliveryMethod: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP
            private set
        var mediaFormat: MediaFormat? = null
            private set
        var manifestUrl: String? = null
            private set
        var averageBitrate: Int = UNKNOWN_BITRATE
            private set
        var audioTrackId: String? = null
            private set
        var audioTrackName: String? = null
            private set
        var audioLocale: Locale? = null
            private set
        var audioTrackType: AudioTrackType? = null
            private set
        var itagItem: ItagItem? = null
            private set
        var deliveryMethodInfo: Serializable? = null
            private set

        fun setId(id: String): Builder { this.id = id; return this }
        fun setContent(content: String, isUrl: Boolean): Builder { this.content = content; this.isUrl = isUrl; return this }
        fun setMediaFormat(mediaFormat: MediaFormat?): Builder { this.mediaFormat = mediaFormat; return this }
        fun setDeliveryMethod(deliveryMethod: DeliveryMethod): Builder { this.deliveryMethod = deliveryMethod; return this }
        fun setManifestUrl(manifestUrl: String?): Builder { this.manifestUrl = manifestUrl; return this }
        fun setAverageBitrate(averageBitrate: Int): Builder { this.averageBitrate = averageBitrate; return this }
        fun setAudioTrackId(audioTrackId: String?): Builder { this.audioTrackId = audioTrackId; return this }
        fun setAudioTrackName(audioTrackName: String?): Builder { this.audioTrackName = audioTrackName; return this }
        fun setAudioTrackType(audioTrackType: AudioTrackType?): Builder { this.audioTrackType = audioTrackType; return this }
        fun setAudioLocale(audioLocale: Locale?): Builder { this.audioLocale = audioLocale; return this }
        fun setItagItem(itagItem: ItagItem?): Builder { this.itagItem = itagItem; return this }
        fun setDeliveryMethodInfo(deliveryMethodInfo: Serializable?): Builder { this.deliveryMethodInfo = deliveryMethodInfo; return this }

        fun build(): AudioStream {
            validateBuild()
            return AudioStream(this)
        }

        fun validateBuild() {
            if (id == null) throw IllegalStateException(
                "The identifier of the audio stream has been not set or is null. If you are not able to get an identifier, use the static constant ID_UNKNOWN of the Stream class."
            )
            if (content == null) throw IllegalStateException(
                "The content of the audio stream has been not set or is null. Please specify a non-null one with setContent."
            )
            if (deliveryMethod == null) throw IllegalStateException(
                "The delivery method of the audio stream has been set as null, which is not allowed. Pass a valid one instead with setDeliveryMethod."
            )
        }
    }

    internal constructor(builder: Builder) : super(
        builder.id!!,
        builder.content!!,
        builder.isUrl,
        builder.mediaFormat,
        builder.deliveryMethod,
        builder.manifestUrl,
        builder.deliveryMethodInfo
    ) {
        if (builder.itagItem != null) {
            this.itagItem = builder.itagItem
            this.itag = builder.itagItem!!.id
            this.quality = builder.itagItem!!.quality
            this.bitrate = builder.itagItem!!.bitrate
            this.initStart = builder.itagItem!!.initStart
            this.initEnd = builder.itagItem!!.initEnd
            this.indexStart = builder.itagItem!!.indexStart
            this.indexEnd = builder.itagItem!!.indexEnd
            this.codec = builder.itagItem!!.codec
        }
        this.averageBitrate = builder.averageBitrate
        this.audioTrackId = builder.audioTrackId
        this.audioTrackName = builder.audioTrackName
        this.audioLocale = builder.audioLocale
        this.audioTrackType = builder.audioTrackType
    }

    override fun equalStats(cmp: Stream): Boolean {
        return super.equalStats(cmp) && cmp is AudioStream
                && averageBitrate == cmp.averageBitrate
                && Objects.equals(audioTrackId, cmp.audioTrackId)
                && audioTrackType == cmp.audioTrackType
                && Objects.equals(audioLocale, cmp.audioLocale)
    }

    fun getAverageBitrate(): Int = averageBitrate
    fun getItag(): Int = itag
    fun getBitrate(): Int = bitrate
    fun getInitStart(): Int = initStart
    fun getInitEnd(): Int = initEnd
    fun getIndexStart(): Int = indexStart
    fun getIndexEnd(): Int = indexEnd
    fun getQuality(): String? = quality
    fun getCodec(): String? = codec
    fun getAudioTrackId(): String? = audioTrackId
    fun getAudioTrackName(): String? = audioTrackName
    fun getAudioLocale(): Locale? = audioLocale
    fun getAudioTrackType(): AudioTrackType? = audioTrackType
    override fun getItagItem(): ItagItem? = itagItem
}
