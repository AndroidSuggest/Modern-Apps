package org.schabi.newpipe.extractor.services.youtube.sabr

class SabrMediaHeader private constructor(
    val headerId: Int,
    val videoId: String?,
    val itag: Int,
    val lastModified: Long,
    val xtags: String?,
    val startRange: Long,
    val compressionAlgorithm: Int,
    private val initSegment: Boolean,
    val sequenceNumber: Int,
    val bitrateBps: Long,
    val startMs: Long,
    val durationMs: Long,
    val contentLength: Long,
    val timeRangeStartTicks: Long,
    val timeRangeDurationTicks: Long,
    val timeRangeTimescale: Int,
    private val sequenceLastModified: Long
) {

    fun isInitSegment(): Boolean = initSegment

    fun getSequenceLastModified(): Long = sequenceLastModified

    fun summarize(): String =
        "id=$headerId, itag=$itag, init=$initSegment, seq=$sequenceNumber, " +
            "startRange=$startRange, startMs=$startMs, durationMs=$durationMs, " +
            "contentLength=$contentLength, compression=$compressionAlgorithm, " +
            "bitrateBps=$bitrateBps, timeRange=$timeRangeStartTicks+$timeRangeDurationTicks/" +
            "$timeRangeTimescale, sequenceLmt=$sequenceLastModified"

    private class FormatId(val itag: Int, val lastModified: Long, val xtags: String?)

    private class TimeRange(val startTicks: Long, val durationTicks: Long, val timescale: Int)

    companion object {
        @JvmStatic
        fun normalized(
            headerId: Int,
            videoId: String?,
            itag: Int,
            lastModified: Long,
            xtags: String?,
            startRange: Long,
            compressionAlgorithm: Int,
            initSegment: Boolean,
            sequenceNumber: Int,
            bitrateBps: Long,
            startMs: Long,
            durationMs: Long,
            contentLength: Long,
            timeRangeStartTicks: Long,
            timeRangeDurationTicks: Long,
            timeRangeTimescale: Int,
            sequenceLastModified: Long
        ): SabrMediaHeader {
            return SabrMediaHeader(
                headerId, videoId, itag, lastModified, xtags, startRange,
                compressionAlgorithm, initSegment, sequenceNumber, bitrateBps, startMs, durationMs,
                contentLength, timeRangeStartTicks, timeRangeDurationTicks, timeRangeTimescale,
                sequenceLastModified
            )
        }

        @JvmStatic
        @Throws(SabrProtocolException::class)
        internal fun decode(data: ByteArray): SabrMediaHeader {
            var headerId = -1
            var videoId: String? = null
            var itag = -1
            var lastModified: Long = -1
            var xtags: String? = null
            var startRange: Long = -1
            var compressionAlgorithm = -1
            var initSegment = false
            var sequenceNumber = -1
            var bitrateBps: Long = -1
            var startMs: Long = -1
            var durationMs: Long = -1
            var contentLength: Long = -1
            var timeRangeStartTicks: Long = -1
            var timeRangeDurationTicks: Long = -1
            var timeRangeTimescale = -1
            var sequenceLastModified: Long = -1

            for (field in SabrProto.readFields(data)) {
                when (field.number) {
                    1 -> headerId = field.varint.toInt()
                    2 -> videoId = field.getString()
                    3 -> itag = field.varint.toInt()
                    4 -> lastModified = field.varint
                    5 -> xtags = field.getString()
                    6 -> startRange = field.varint
                    7 -> compressionAlgorithm = field.varint.toInt()
                    8 -> initSegment = field.varint != 0L
                    9 -> sequenceNumber = field.varint.toInt()
                    10 -> bitrateBps = field.varint
                    11 -> startMs = field.varint
                    12 -> durationMs = field.varint
                    13 -> {
                        val formatId = decodeFormatId(field.getBytes())
                        if (itag < 0) itag = formatId.itag
                        if (lastModified < 0) lastModified = formatId.lastModified
                        if (xtags == null) xtags = formatId.xtags
                    }
                    14 -> contentLength = field.varint
                    15 -> {
                        val timeRange = decodeTimeRange(field.getBytes())
                        timeRangeStartTicks = timeRange.startTicks
                        timeRangeDurationTicks = timeRange.durationTicks
                        timeRangeTimescale = timeRange.timescale
                    }
                    16 -> sequenceLastModified = field.varint
                }
            }

            if (timeRangeTimescale > 0) {
                if (startMs < 0 && timeRangeStartTicks >= 0) {
                    startMs = timeRangeStartTicks * 1000L / timeRangeTimescale
                }
                if (durationMs < 0 && timeRangeDurationTicks >= 0) {
                    durationMs = timeRangeDurationTicks * 1000L / timeRangeTimescale
                }
            }

            return SabrMediaHeader(
                headerId, videoId, itag, lastModified, xtags, startRange,
                compressionAlgorithm, initSegment, sequenceNumber, bitrateBps, startMs, durationMs,
                contentLength, timeRangeStartTicks, timeRangeDurationTicks, timeRangeTimescale,
                sequenceLastModified
            )
        }

        @Throws(SabrProtocolException::class)
        private fun decodeFormatId(data: ByteArray): FormatId {
            var itag = -1
            var lastModified: Long = -1
            var xtags: String? = null
            for (field in SabrProto.readFields(data)) {
                when {
                    field.number == 1 && field.wireType == SabrProto.WIRE_VARINT -> {
                        itag = field.varint.toInt()
                    }
                    field.number == 2 && field.wireType == SabrProto.WIRE_VARINT -> {
                        lastModified = field.varint
                    }
                    field.number == 3 && field.wireType == SabrProto.WIRE_LENGTH_DELIMITED -> {
                        xtags = field.getString()
                    }
                }
            }
            return FormatId(itag, lastModified, xtags)
        }

        @Throws(SabrProtocolException::class)
        private fun decodeTimeRange(data: ByteArray): TimeRange {
            var startTicks: Long = -1
            var durationTicks: Long = -1
            var timescale = -1
            for (field in SabrProto.readFields(data)) {
                when {
                    field.number == 1 && field.wireType == SabrProto.WIRE_VARINT -> {
                        startTicks = field.varint
                    }
                    field.number == 2 && field.wireType == SabrProto.WIRE_VARINT -> {
                        durationTicks = field.varint
                    }
                    field.number == 3 && field.wireType == SabrProto.WIRE_VARINT -> {
                        timescale = field.varint.toInt()
                    }
                }
            }
            return TimeRange(startTicks, durationTicks, timescale)
        }
    }
}
