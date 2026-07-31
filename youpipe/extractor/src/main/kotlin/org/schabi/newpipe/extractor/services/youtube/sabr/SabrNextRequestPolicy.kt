package org.schabi.newpipe.extractor.services.youtube.sabr

class SabrNextRequestPolicy private constructor(
    val targetAudioReadaheadMs: Int,
    val targetVideoReadaheadMs: Int,
    val maxTimeSinceLastRequestMs: Int,
    val backoffTimeMs: Int,
    val minAudioReadaheadMs: Int,
    val minVideoReadaheadMs: Int,
    playbackCookie: ByteArray?,
    val decodedPlaybackCookie: SabrPlaybackCookie?,
    val videoId: String?,
    private val unknownFields: String
) {
    private val playbackCookie: ByteArray? = playbackCookie?.clone()

    companion object {
        @JvmStatic
        fun normalized(
            targetAudioReadaheadMs: Int,
            targetVideoReadaheadMs: Int,
            maxTimeSinceLastRequestMs: Int,
            backoffTimeMs: Int,
            minAudioReadaheadMs: Int,
            minVideoReadaheadMs: Int,
            playbackCookie: ByteArray?,
            videoId: String?
        ): SabrNextRequestPolicy {
            if (playbackCookie != null && playbackCookie.size > 64 * 1024) {
                throw IllegalArgumentException("SABR playback cookie exceeded Host limit")
            }
            return SabrNextRequestPolicy(
                targetAudioReadaheadMs, targetVideoReadaheadMs,
                maxTimeSinceLastRequestMs, backoffTimeMs, minAudioReadaheadMs,
                minVideoReadaheadMs, playbackCookie, null, videoId, "policy"
            )
        }

        @Throws(SabrProtocolException::class)
        @JvmStatic
        internal fun decode(data: ByteArray): SabrNextRequestPolicy {
            var targetAudioReadaheadMs = -1
            var targetVideoReadaheadMs = -1
            var maxTimeSinceLastRequestMs = -1
            var backoffTimeMs = -1
            var minAudioReadaheadMs = -1
            var minVideoReadaheadMs = -1
            var playbackCookie: ByteArray? = null
            var decodedPlaybackCookie: SabrPlaybackCookie? = null
            var videoId: String? = null
            val unknownFields = SabrProto.summarizeUnknownFields(data, 1, 2, 3, 4, 5, 6, 7, 8)

            for (field in SabrProto.readFields(data)) {
                when (field.number) {
                    1 -> targetAudioReadaheadMs = field.varint.toInt()
                    2 -> targetVideoReadaheadMs = field.varint.toInt()
                    3 -> maxTimeSinceLastRequestMs = field.varint.toInt()
                    4 -> backoffTimeMs = field.varint.toInt()
                    5 -> minAudioReadaheadMs = field.varint.toInt()
                    6 -> minVideoReadaheadMs = field.varint.toInt()
                    7 -> {
                        playbackCookie = field.getBytes()
                        decodedPlaybackCookie = SabrPlaybackCookie.decode(playbackCookie)
                    }
                    8 -> videoId = field.getString()
                    else -> {}
                }
            }

            return SabrNextRequestPolicy(
                targetAudioReadaheadMs, targetVideoReadaheadMs,
                maxTimeSinceLastRequestMs, backoffTimeMs, minAudioReadaheadMs,
                minVideoReadaheadMs, playbackCookie, decodedPlaybackCookie, videoId,
                unknownFields
            )
        }
    }

    fun getPlaybackCookie(): ByteArray? = playbackCookie?.clone()

    internal fun getRawPlaybackCookie(): ByteArray? = playbackCookie

    fun getUnknownFields(): String = unknownFields

    fun summarize(): String {
        return "targetAudio=$targetAudioReadaheadMs" +
            ", targetVideo=$targetVideoReadaheadMs" +
            ", maxSinceLast=$maxTimeSinceLastRequestMs" +
            ", backoff=$backoffTimeMs" +
            ", minAudio=$minAudioReadaheadMs" +
            ", minVideo=$minVideoReadaheadMs" +
            ", cookie=" + if (decodedPlaybackCookie == null)
            "bytes(${playbackCookie?.size ?: 0})"
        else
            decodedPlaybackCookie.summarize() +
                ", videoIdLength=${videoId?.length ?: 0}" +
                (if ("none" == unknownFields) "" else ", unknown=$unknownFields")
    }
}
