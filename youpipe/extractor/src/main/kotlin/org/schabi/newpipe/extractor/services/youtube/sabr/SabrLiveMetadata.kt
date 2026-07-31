package org.schabi.newpipe.extractor.services.youtube.sabr

class SabrLiveMetadata private constructor(
    val broadcastId: String?,
    val headSequenceNumber: Long,
    val headTimeMs: Long,
    val wallTimeMs: Long,
    private val videoId: String?,
    private val postLiveDvr: Boolean,
    private val headm: Long,
    val minSeekableTimeTicks: Long,
    val minSeekableTimescale: Int,
    val maxSeekableTimeTicks: Long,
    private val maxSeekableTimescale: Int
) {
    /** Latest segment the live edge has reached, or -1 if unknown. */

    /** Wall-clock-ish position (ms) of the live head, or -1 if unknown. */

    /** True for an ended live stream still seekable as DVR. */
    fun isPostLiveDvr(): Boolean = postLiveDvr

    fun getMaxSeekableTimescale(): Int = maxSeekableTimescale

    fun summarize(): String =
        "broadcastIdLength=${broadcastId?.length ?: 0}, headSeq=$headSequenceNumber, " +
            "headTimeMs=$headTimeMs, wallTimeMs=$wallTimeMs, " +
            "videoIdLength=${videoId?.length ?: 0}, postLiveDvr=$postLiveDvr, headm=$headm, " +
            "minSeekable=$minSeekableTimeTicks/$minSeekableTimescale, " +
            "maxSeekable=$maxSeekableTimeTicks/$maxSeekableTimescale"

    companion object {
        @JvmStatic
        fun normalized(
            broadcastId: String?,
            headSequenceNumber: Long,
            headTimeMs: Long,
            wallTimeMs: Long,
            videoId: String?,
            postLiveDvr: Boolean,
            headm: Long,
            minSeekableTimeTicks: Long,
            minSeekableTimescale: Int,
            maxSeekableTimeTicks: Long,
            maxSeekableTimescale: Int
        ): SabrLiveMetadata {
            if (headSequenceNumber < -1 || headTimeMs < -1 || wallTimeMs < -1
                || minSeekableTimeTicks < -1 || minSeekableTimescale < -1
                || maxSeekableTimeTicks < -1 || maxSeekableTimescale < -1
            ) {
                throw IllegalArgumentException("Invalid normalized SABR live metadata")
            }
            return SabrLiveMetadata(
                broadcastId, headSequenceNumber, headTimeMs, wallTimeMs,
                videoId, postLiveDvr, headm, minSeekableTimeTicks, minSeekableTimescale,
                maxSeekableTimeTicks, maxSeekableTimescale
            )
        }

        @JvmStatic
        @Throws(SabrProtocolException::class)
        internal fun decode(data: ByteArray): SabrLiveMetadata {
            var broadcastId: String? = null
            var headSequenceNumber: Long = -1
            var headTimeMs: Long = -1
            var wallTimeMs: Long = -1
            var videoId: String? = null
            var postLiveDvr = false
            var headm: Long = -1
            var minSeekableTimeTicks: Long = -1
            var minSeekableTimescale = -1
            var maxSeekableTimeTicks: Long = -1
            var maxSeekableTimescale = -1
            for (field in SabrProto.readFields(data)) {
                when (field.number) {
                    1 -> broadcastId = field.getString()
                    3 -> headSequenceNumber = field.varint
                    4 -> headTimeMs = field.varint
                    5 -> wallTimeMs = field.varint
                    6 -> videoId = field.getString()
                    8 -> postLiveDvr = field.varint != 0L
                    10 -> headm = field.varint
                    12 -> minSeekableTimeTicks = field.varint
                    13 -> minSeekableTimescale = field.varint.toInt()
                    14 -> maxSeekableTimeTicks = field.varint
                    15 -> maxSeekableTimescale = field.varint.toInt()
                }
            }
            return SabrLiveMetadata(
                broadcastId, headSequenceNumber, headTimeMs, wallTimeMs,
                videoId, postLiveDvr, headm, minSeekableTimeTicks, minSeekableTimescale,
                maxSeekableTimeTicks, maxSeekableTimescale
            )
        }
    }
}
