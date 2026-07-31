package org.schabi.newpipe.extractor.services.youtube.sabr

class SabrBufferedRange(
    private val itag: Int,
    private val lastModified: Long,
    private val xtags: String?,
    private val startTimeMs: Long,
    private val durationMs: Long,
    private val startSegmentIndex: Int,
    private val endSegmentIndex: Int,
    private val timescale: Int
) {
    internal fun toProto(): ByteArray = toProto(true)

    internal fun toProto(includeTimeRange: Boolean): ByteArray {
        val range = SabrProto.Writer()
        range.writeMessage(1, formatIdProto())
        range.writeUInt64(2, startTimeMs)
        range.writeUInt64(3, durationMs)
        range.writeInt32(4, startSegmentIndex)
        range.writeInt32(5, endSegmentIndex)
        if (includeTimeRange) {
            range.writeMessage(6, timeRangeProto())
        }
        return range.toByteArray()
    }

    fun summarize(): String =
        "itag=$itag:seq=$startSegmentIndex-$endSegmentIndex:time=$startTimeMs+$durationMs:timescale=$timescale"

    private fun formatIdProto(): ByteArray {
        val format = SabrProto.Writer()
        format.writeInt32(1, itag)
        if (lastModified > 0) {
            format.writeUInt64(2, lastModified)
        }
        format.writeStringIfNotEmpty(3, xtags)
        return format.toByteArray()
    }

    private fun timeRangeProto(): ByteArray {
        val timeRange = SabrProto.Writer()
        timeRange.writeUInt64(1, startTimeMs)
        timeRange.writeUInt64(2, durationMs)
        timeRange.writeInt32(3, timescale)
        return timeRange.toByteArray()
    }

    companion object {
        private const val MAX_INT32_VALUE = Int.MAX_VALUE

        @JvmStatic
        internal fun full(format: YoutubeSabrFormat): SabrBufferedRange {
            return SabrBufferedRange(
                format.getItag(),
                format.getLastModified(),
                format.getXtags(),
                0,
                MAX_INT32_VALUE.toLong(),
                MAX_INT32_VALUE,
                MAX_INT32_VALUE,
                1000
            )
        }
    }
}
