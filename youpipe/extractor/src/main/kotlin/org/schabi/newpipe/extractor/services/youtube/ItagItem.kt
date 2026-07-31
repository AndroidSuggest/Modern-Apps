package org.schabi.newpipe.extractor.services.youtube

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.MediaFormat.M4A
import org.schabi.newpipe.extractor.MediaFormat.MPEG_4
import org.schabi.newpipe.extractor.MediaFormat.WEBM
import org.schabi.newpipe.extractor.MediaFormat.WEBMA
import org.schabi.newpipe.extractor.MediaFormat.WEBMA_OPUS
import org.schabi.newpipe.extractor.MediaFormat.v3GPP
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.services.youtube.ItagItem.ItagType.AUDIO
import org.schabi.newpipe.extractor.services.youtube.ItagItem.ItagType.VIDEO
import org.schabi.newpipe.extractor.services.youtube.ItagItem.ItagType.VIDEO_ONLY
import org.schabi.newpipe.extractor.stream.AudioTrackType
import java.io.Serializable
import java.util.Locale
import javax.annotation.Nonnull
import javax.annotation.Nullable

class ItagItem : Serializable {

    /*//////////////////////////////////////////////////////////////////////////
    // Static constants
    //////////////////////////////////////////////////////////////////////////*/

    companion object {
        /**
         * List can be found here:
         * https://github.com/ytdl-org/youtube-dl/blob/e988fa4/youtube_dl/extractor/youtube.py#L1195
         */
        private val ITAG_LIST: Array<ItagItem> = arrayOf(
            /////////////////////////////////////////////////////
            // VIDEO     ID  Type   Format  Resolution  FPS  ////
            /////////////////////////////////////////////////////
            ItagItem(17, VIDEO, v3GPP, "144p"),
            ItagItem(36, VIDEO, v3GPP, "240p"),

            ItagItem(18, VIDEO, MPEG_4, "360p"),
            ItagItem(34, VIDEO, MPEG_4, "360p"),
            ItagItem(35, VIDEO, MPEG_4, "480p"),
            ItagItem(59, VIDEO, MPEG_4, "480p"),
            ItagItem(78, VIDEO, MPEG_4, "480p"),
            ItagItem(22, VIDEO, MPEG_4, "720p"),
            ItagItem(37, VIDEO, MPEG_4, "1080p"),
            ItagItem(38, VIDEO, MPEG_4, "1080p"),

            ItagItem(43, VIDEO, WEBM, "360p"),
            ItagItem(44, VIDEO, WEBM, "480p"),
            ItagItem(45, VIDEO, WEBM, "720p"),
            ItagItem(46, VIDEO, WEBM, "1080p"),

            //////////////////////////////////////////////////////////////////
            // AUDIO     ID      ItagType          Format        Bitrate    //
            //////////////////////////////////////////////////////////////////
            ItagItem(171, AUDIO, WEBMA, 128),
            ItagItem(172, AUDIO, WEBMA, 256),
            ItagItem(599, AUDIO, M4A, 32),
            ItagItem(139, AUDIO, M4A, 48),
            ItagItem(140, AUDIO, M4A, 128),
            ItagItem(141, AUDIO, M4A, 256),
            ItagItem(600, AUDIO, WEBMA_OPUS, 35),
            ItagItem(249, AUDIO, WEBMA_OPUS, 50),
            ItagItem(250, AUDIO, WEBMA_OPUS, 70),
            ItagItem(251, AUDIO, WEBMA_OPUS, 160),

            /// VIDEO ONLY ////////////////////////////////////////////
            //           ID      Type     Format  Resolution  FPS  ////
            ///////////////////////////////////////////////////////////
            ItagItem(160, VIDEO_ONLY, MPEG_4, "144p"),
            ItagItem(394, VIDEO_ONLY, MPEG_4, "144p"),
            ItagItem(133, VIDEO_ONLY, MPEG_4, "240p"),
            ItagItem(395, VIDEO_ONLY, MPEG_4, "240p"),
            ItagItem(134, VIDEO_ONLY, MPEG_4, "360p"),
            ItagItem(396, VIDEO_ONLY, MPEG_4, "360p"),
            ItagItem(135, VIDEO_ONLY, MPEG_4, "480p"),
            ItagItem(212, VIDEO_ONLY, MPEG_4, "480p"),
            ItagItem(397, VIDEO_ONLY, MPEG_4, "480p"),
            ItagItem(136, VIDEO_ONLY, MPEG_4, "720p"),
            ItagItem(398, VIDEO_ONLY, MPEG_4, "720p"),
            ItagItem(298, VIDEO_ONLY, MPEG_4, "720p60", 60),
            ItagItem(137, VIDEO_ONLY, MPEG_4, "1080p"),
            ItagItem(399, VIDEO_ONLY, MPEG_4, "1080p"),
            ItagItem(299, VIDEO_ONLY, MPEG_4, "1080p60", 60),
            ItagItem(400, VIDEO_ONLY, MPEG_4, "1440p"),
            ItagItem(266, VIDEO_ONLY, MPEG_4, "2160p"),
            ItagItem(401, VIDEO_ONLY, MPEG_4, "2160p"),

            ItagItem(278, VIDEO_ONLY, WEBM, "144p"),
            ItagItem(242, VIDEO_ONLY, WEBM, "240p"),
            ItagItem(243, VIDEO_ONLY, WEBM, "360p"),
            ItagItem(244, VIDEO_ONLY, WEBM, "480p"),
            ItagItem(245, VIDEO_ONLY, WEBM, "480p"),
            ItagItem(246, VIDEO_ONLY, WEBM, "480p"),
            ItagItem(247, VIDEO_ONLY, WEBM, "720p"),
            ItagItem(248, VIDEO_ONLY, WEBM, "1080p"),
            ItagItem(271, VIDEO_ONLY, WEBM, "1440p"),
            // #272 is either 3840x2160 (e.g. RtoitU2A-3E) or 7680x4320 (sLprVF6d7Ug)
            ItagItem(272, VIDEO_ONLY, WEBM, "2160p"),
            ItagItem(302, VIDEO_ONLY, WEBM, "720p60", 60),
            ItagItem(303, VIDEO_ONLY, WEBM, "1080p60", 60),
            ItagItem(308, VIDEO_ONLY, WEBM, "1440p60", 60),
            ItagItem(313, VIDEO_ONLY, WEBM, "2160p"),
            ItagItem(315, VIDEO_ONLY, WEBM, "2160p60", 60)
        )

        const val AVERAGE_BITRATE_UNKNOWN = -1
        const val SAMPLE_RATE_UNKNOWN = -1
        const val FPS_NOT_APPLICABLE_OR_UNKNOWN = -1
        const val TARGET_DURATION_SEC_UNKNOWN = -1
        const val AUDIO_CHANNELS_NOT_APPLICABLE_OR_UNKNOWN = -1
        const val CONTENT_LENGTH_UNKNOWN = -1L
        const val APPROX_DURATION_MS_UNKNOWN = -1L
        const val LAST_MODIFIED_UNKOWN = -1L

        @JvmStatic
        fun isSupported(itag: Int): Boolean {
            for (item in ITAG_LIST) {
                if (itag == item.id) {
                    return true
                }
            }
            return false
        }

        @JvmStatic
        @Nonnull
        @Throws(ParsingException::class)
        fun getItag(itagId: Int): ItagItem {
            for (item in ITAG_LIST) {
                if (itagId == item.id) {
                    return ItagItem(item)
                }
            }
            throw ParsingException("itag $itagId is not supported")
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Constructors and misc
    //////////////////////////////////////////////////////////////////////////*/

    enum class ItagType {
        AUDIO,
        VIDEO,
        VIDEO_ONLY
    }

    private val mediaFormat: MediaFormat

    @JvmField
    val id: Int

    @JvmField
    val itagType: ItagType

    // Audio fields
    @Deprecated("Use getAverageBitrate() instead.")
    @JvmField
    var avgBitrate: Int = AVERAGE_BITRATE_UNKNOWN

    private var _sampleRate: Int = SAMPLE_RATE_UNKNOWN
    private var _audioChannels: Int = AUDIO_CHANNELS_NOT_APPLICABLE_OR_UNKNOWN

    // Video fields
    @Deprecated("Use getResolutionString() instead.")
    @JvmField
    var resolutionString: String? = null

    @Deprecated("Use getFps() and setFps(int) instead.")
    @JvmField
    var fps: Int = FPS_NOT_APPLICABLE_OR_UNKNOWN

    // Fields for Dash
    private var _bitrate: Int = 0
    private var _width: Int = 0
    private var _height: Int = 0
    private var _initStart: Int = 0
    private var _initEnd: Int = 0
    private var _indexStart: Int = 0
    private var _indexEnd: Int = 0
    private var _quality: String? = null
    private var _codec: String? = null
    private var _targetDurationSec: Int = TARGET_DURATION_SEC_UNKNOWN
    private var _approxDurationMs: Long = APPROX_DURATION_MS_UNKNOWN
    private var _contentLength: Long = CONTENT_LENGTH_UNKNOWN
    private var _audioTrackId: String? = null
    private var _audioTrackName: String? = null
    @Nullable
    private var _audioTrackType: AudioTrackType? = null
    @Nullable
    private var _audioLocale: Locale? = null
    private var _isDrc: Boolean = false
    private var _lastModified: Long = 0L
    private var _xtags: String? = null

    /**
     * Call [ItagItem] with the fps set to 30.
     */
    constructor(
        id: Int,
        type: ItagType,
        format: MediaFormat,
        resolution: String
    ) {
        this.id = id
        this.itagType = type
        this.mediaFormat = format
        this.resolutionString = resolution
        this.fps = 30
    }

    /**
     * Constructor for videos.
     */
    constructor(
        id: Int,
        type: ItagType,
        format: MediaFormat,
        resolution: String,
        fps: Int
    ) {
        this.id = id
        this.itagType = type
        this.mediaFormat = format
        this.resolutionString = resolution
        this.fps = fps
    }

    constructor(
        id: Int,
        type: ItagType,
        format: MediaFormat,
        avgBitrate: Int
    ) {
        this.id = id
        this.itagType = type
        this.mediaFormat = format
        this.avgBitrate = avgBitrate
    }

    /**
     * Copy constructor of the [ItagItem] class.
     *
     * @param itagItem the [ItagItem] to copy its properties into a new [ItagItem]
     */
    constructor(@Nonnull itagItem: ItagItem) {
        this.mediaFormat = itagItem.mediaFormat
        this.id = itagItem.id
        this.itagType = itagItem.itagType
        this.avgBitrate = itagItem.avgBitrate
        this._sampleRate = itagItem._sampleRate
        this._audioChannels = itagItem._audioChannels
        this.resolutionString = itagItem.resolutionString
        this.fps = itagItem.fps
        this._bitrate = itagItem._bitrate
        this._width = itagItem._width
        this._height = itagItem._height
        this._initStart = itagItem._initStart
        this._initEnd = itagItem._initEnd
        this._indexStart = itagItem._indexStart
        this._indexEnd = itagItem._indexEnd
        this._quality = itagItem._quality
        this._codec = itagItem._codec
        this._targetDurationSec = itagItem._targetDurationSec
        this._approxDurationMs = itagItem._approxDurationMs
        this._contentLength = itagItem._contentLength
        this._audioTrackId = itagItem._audioTrackId
        this._audioTrackName = itagItem._audioTrackName
        this._audioTrackType = itagItem._audioTrackType
        this._audioLocale = itagItem._audioLocale
        this._isDrc = itagItem._isDrc
        this._lastModified = itagItem._lastModified
        this._xtags = itagItem._xtags
    }

    fun getMediaFormat(): MediaFormat = mediaFormat

    fun getBitrate(): Int = _bitrate
    fun setBitrate(bitrate: Int) {
        _bitrate = bitrate
    }

    fun getWidth(): Int = _width
    fun setWidth(width: Int) {
        _width = width
    }

    fun getHeight(): Int = _height
    fun setHeight(height: Int) {
        _height = height
    }

    /**
     * Get the frame rate.
     *
     * It is set to the `fps` value returned in the corresponding itag in the YouTube player
     * response.
     *
     * It defaults to the standard value associated with this itag.
     *
     * Note that this value is only known for video itags, so [FPS_NOT_APPLICABLE_OR_UNKNOWN] is returned for non video itags.
     *
     * @return the frame rate or [FPS_NOT_APPLICABLE_OR_UNKNOWN]
     */
    fun getFps(): Int = fps

    /**
     * Set the frame rate.
     *
     * It is only known for video itags, so [FPS_NOT_APPLICABLE_OR_UNKNOWN] is set/used for
     * non video itags or if the sample rate value is less than or equal to 0.
     *
     * @param fps the frame rate
     */
    fun setFps(fps: Int) {
        this.fps = if (fps > 0) fps else FPS_NOT_APPLICABLE_OR_UNKNOWN
    }

    fun getInitStart(): Int = _initStart
    fun setInitStart(initStart: Int) {
        _initStart = initStart
    }

    fun getInitEnd(): Int = _initEnd
    fun setInitEnd(initEnd: Int) {
        _initEnd = initEnd
    }

    fun getIndexStart(): Int = _indexStart
    fun setIndexStart(indexStart: Int) {
        _indexStart = indexStart
    }

    fun getIndexEnd(): Int = _indexEnd
    fun setIndexEnd(indexEnd: Int) {
        _indexEnd = indexEnd
    }

    fun getQuality(): String? = _quality
    fun setQuality(quality: String?) {
        _quality = quality
    }

    /**
     * Get the resolution string associated with this `ItagItem`.
     *
     * It is only known for video itags.
     *
     * @return the resolution string associated with this `ItagItem` or `null`.
     */
    @Nullable
    fun getResolutionString(): String? = resolutionString

    fun getCodec(): String? = _codec
    fun setCodec(codec: String?) {
        _codec = codec
    }

    /**
     * Get the average bitrate.
     *
     * It is only known for audio itags, so [AVERAGE_BITRATE_UNKNOWN] is always returned for
     * other itag types.
     *
     * Bitrate of video itags and precise bitrate of audio itags can be known using
     * [getBitrate].
     *
     * @return the average bitrate or [AVERAGE_BITRATE_UNKNOWN]
     * @see getBitrate
     */
    fun getAverageBitrate(): Int = avgBitrate

    /**
     * Get the sample rate.
     *
     * It is only known for audio itags, so [SAMPLE_RATE_UNKNOWN] is returned for non audio
     * itags, or if the sample rate is unknown.
     *
     * @return the sample rate or [SAMPLE_RATE_UNKNOWN]
     */
    fun getSampleRate(): Int = _sampleRate

    /**
     * Set the sample rate.
     *
     * It is only known for audio itags, so [SAMPLE_RATE_UNKNOWN] is set/used for non audio
     * itags, or if the sample rate value is less than or equal to 0.
     *
     * @param sampleRate the sample rate of an audio itag
     */
    fun setSampleRate(sampleRate: Int) {
        _sampleRate = if (sampleRate > 0) sampleRate else SAMPLE_RATE_UNKNOWN
    }

    /**
     * Get the number of audio channels.
     *
     * It is only known for audio itags, so [AUDIO_CHANNELS_NOT_APPLICABLE_OR_UNKNOWN] is
     * returned for non audio itags, or if it is unknown.
     *
     * @return the number of audio channels or [AUDIO_CHANNELS_NOT_APPLICABLE_OR_UNKNOWN]
     */
    fun getAudioChannels(): Int = _audioChannels

    /**
     * Set the number of audio channels.
     *
     * It is only known for audio itags, so [AUDIO_CHANNELS_NOT_APPLICABLE_OR_UNKNOWN] is
     * set/used for non audio itags, or if the `audioChannels` value is less than or equal to
     * 0.
     *
     * @param audioChannels the number of audio channels of an audio itag
     */
    fun setAudioChannels(audioChannels: Int) {
        _audioChannels = if (audioChannels > 0) audioChannels else AUDIO_CHANNELS_NOT_APPLICABLE_OR_UNKNOWN
    }

    /**
     * Get the `targetDurationSec` value.
     *
     * This value is the average time in seconds of the duration of sequences of livestreams and
     * ended livestreams. It is only returned by YouTube for these stream types, and makes no sense
     * for videos, so [TARGET_DURATION_SEC_UNKNOWN] is returned for those.
     *
     * @return the `targetDurationSec` value or [TARGET_DURATION_SEC_UNKNOWN]
     */
    fun getTargetDurationSec(): Int = _targetDurationSec

    /**
     * Set the `targetDurationSec` value.
     *
     * This value is the average time in seconds of the duration of sequences of livestreams and
     * ended livestreams.
     *
     * It is only returned for these stream types by YouTube and makes no sense for videos, so
     * [TARGET_DURATION_SEC_UNKNOWN] will be set/used for video streams or if this value is
     * less than or equal to 0.
     *
     * @param targetDurationSec the target duration of a segment of streams which are using the
     *                          live delivery method type
     */
    fun setTargetDurationSec(targetDurationSec: Int) {
        _targetDurationSec = if (targetDurationSec > 0) targetDurationSec else TARGET_DURATION_SEC_UNKNOWN
    }

    /**
     * Get the `approxDurationMs` value.
     *
     * It is only known for DASH progressive streams, so [APPROX_DURATION_MS_UNKNOWN] is
     * returned for other stream types or if this value is less than or equal to 0.
     *
     * @return the `approxDurationMs` value or [APPROX_DURATION_MS_UNKNOWN]
     */
    fun getApproxDurationMs(): Long = _approxDurationMs

    /**
     * Set the `approxDurationMs` value.
     *
     * It is only known for DASH progressive streams, so [APPROX_DURATION_MS_UNKNOWN] is
     * set/used for other stream types or if this value is less than or equal to 0.
     *
     * @param approxDurationMs the approximate duration of a DASH progressive stream, in
     *                         milliseconds
     */
    fun setApproxDurationMs(approxDurationMs: Long) {
        _approxDurationMs = if (approxDurationMs > 0) approxDurationMs else APPROX_DURATION_MS_UNKNOWN
    }

    /**
     * Get the `contentLength` value.
     *
     * It is only known for DASH progressive streams, so [CONTENT_LENGTH_UNKNOWN] is
     * returned for other stream types or if this value is less than or equal to 0.
     *
     * @return the `contentLength` value or [CONTENT_LENGTH_UNKNOWN]
     */
    fun getContentLength(): Long = _contentLength

    /**
     * Set the content length of stream.
     *
     * It is only known for DASH progressive streams, so [CONTENT_LENGTH_UNKNOWN] is
     * set/used for other stream types or if this value is less than or equal to 0.
     *
     * @param contentLength the content length of a DASH progressive stream
     */
    fun setContentLength(contentLength: Long) {
        _contentLength = if (contentLength > 0) contentLength else CONTENT_LENGTH_UNKNOWN
    }

    /**
     * Get the `audioTrackId` of the stream, if present.
     *
     * @return the `audioTrackId` of the stream or null
     */
    @Nullable
    fun getAudioTrackId(): String? = _audioTrackId

    /**
     * Set the `audioTrackId` of the stream.
     *
     * @param audioTrackId the `audioTrackId` of the stream
     */
    fun setAudioTrackId(@Nullable audioTrackId: String?) {
        _audioTrackId = audioTrackId
    }

    /**
     * Get the `audioTrackName` of the stream, if present.
     *
     * @return the `audioTrackName` of the stream or `null`
     */
    @Nullable
    fun getAudioTrackName(): String? = _audioTrackName

    /**
     * Set the `audioTrackName` of the stream, if present.
     *
     * @param audioTrackName the `audioTrackName` of the stream or `null`
     */
    fun setAudioTrackName(@Nullable audioTrackName: String?) {
        _audioTrackName = audioTrackName
    }

    /**
     * Get the [AudioTrackType] of the stream.
     *
     * @return the [AudioTrackType] of the stream or `null`
     */
    @Nullable
    fun getAudioTrackType(): AudioTrackType? = _audioTrackType

    /**
     * Set the [AudioTrackType] of the stream, if present.
     *
     * @param audioTrackType the [AudioTrackType] of the stream or `null`
     */
    fun setAudioTrackType(@Nullable audioTrackType: AudioTrackType?) {
        _audioTrackType = audioTrackType
    }

    /**
     * Return the audio [Locale] of the stream, if known.
     *
     * @return the audio [Locale] of the stream, if known, or `null` if that's not the
     * case
     */
    @Nullable
    fun getAudioLocale(): Locale? = _audioLocale

    /**
     * Set the audio [Locale] of the stream.
     *
     * If it is unknown, `null` could be passed, which is the default value.
     *
     * @param audioLocale the audio [Locale] of the stream, which could be `null`
     */
    fun setAudioLocale(@Nullable audioLocale: Locale?) {
        _audioLocale = audioLocale
    }

    /**
     * Whether the audio is using dynamic range compression (DRC).
     *
     * https://en.wikipedia.org/wiki/Dynamic_range_compression
     *
     * @return whether the audio is using DRC
     */
    fun isDrc(): Boolean = _isDrc

    /**
     * Sets whether the audio is using dynamic range compression (DRC).
     *
     * https://en.wikipedia.org/wiki/Dynamic_range_compression
     *
     * @param isDrc whether the audio has DRC applied
     */
    fun setIsDrc(isDrc: Boolean) {
        _isDrc = isDrc
    }

    /**
     * When the stream was last modified.
     *
     * If the timestamp is unknown, [LAST_MODIFIED_UNKOWN] is returned.
     *
     * @return unix timestamp of when the stream was last modified or
     * [LAST_MODIFIED_UNKOWN] if the timestamp is unknown.
     */
    fun getLastModified(): Long = _lastModified

    /**
     * Sets the timestamp when the stream was last modified.
     *
     * @param lastModified unix timestamp of when the stream was last modified
     */
    fun setLastModified(lastModified: Long) {
        _lastModified = lastModified
    }

    /**
     * Extra tags about the stream.
     *
     * Contains a Base64 encoded protobuf key-value list of additional tags for the stream,
     * such as whether the stream is using [isDrc].
     *
     * @return Base64-encoded extra tags.
     */
    fun getXtags(): String? = _xtags

    /**
     * Sets extra tags of the stream.
     *
     * @param xtags extra tags of the stream
     */
    fun setXtags(xtags: String?) {
        _xtags = xtags
    }
}
