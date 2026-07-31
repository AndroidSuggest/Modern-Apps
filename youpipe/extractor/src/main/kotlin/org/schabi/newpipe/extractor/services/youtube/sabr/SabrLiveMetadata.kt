package org.schabi.newpipe.extractor.services.youtube.sabr

class SabrLiveMetadata private constructor(
    private val broadcastId: String?,
    private val headSequenceNumber: Long,
    private val headTimeMs: Long,
    private val wallTimeMs: Long,
    private val videoId: String?,
    private val postLiveDvr: Boolean,
    private val headm: Long,
    private val minSeekableTimeTicks: Long,
    private val minSeekableTimescale: Int,
    private val maxSeekableTimeTicks: Long,
    private val maxSeekableTimescale: Int
) {
    /** Latest segment the live edge has reached, or -1 if unknown. */
    fun getHeadSequenceNumber(): Long = headSequenceNumber

    /** Wall-clock-ish position (ms) of the live head, or -1 if unknown. */
    fun getHeadTimeMs(): Long = headTimeMs

    fun getWallTimeMs(): Long = wallTimeMs

    /** True for an ended live stream still seekable as DVR. */
    fun isPostLiveDvr(): Boolean = postLiveDvr

    fun getBroadcastId(): String? = broadcastId

    fun getMinSeekableTimeTicks(): Long = minSeekableTimeTicks

    fun getMinSeekableTimescale(): Int = minSeekableTimescale

    fun getMaxSeekableTimeTicks(): Long = maxSeekableTimeTicks

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
                when (field.getNumber()) {
                    1 -> broadcastId = field.getString()
                    3 -> headSequenceNumber = field.getVarint()
                    4 -> headTimeMs = field.getVarint()
                    5 -> wallTimeMs = field.getVarint()
                    6 -> videoId = field.getString()
                    8 -> postLiveDvr = field.getVarint() != 0L
                    10 -> headm = field.getVarint()
                    12 -> minSeekableTimeTicks = field.getVarint()
                    13 -> minSeekableTimescale = field.getVarint().toInt()
                    14 -> maxSeekableTimeTicks = field.getVarint()
                    15 -> maxSeekableTimescale = field.getVarint().toInt()
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
