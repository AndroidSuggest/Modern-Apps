package org.schabi.newpipe.extractor.services.youtube.sabr

class SabrMediaHeader private constructor(
    private val headerId: Int,
    private val videoId: String?,
    private val itag: Int,
    private val lastModified: Long,
    private val xtags: String?,
    private val startRange: Long,
    private val compressionAlgorithm: Int,
    private val initSegment: Boolean,
    private val sequenceNumber: Int,
    private val bitrateBps: Long,
    private val startMs: Long,
    private val durationMs: Long,
    private val contentLength: Long,
    private val timeRangeStartTicks: Long,
    private val timeRangeDurationTicks: Long,
    private val timeRangeTimescale: Int,
    private val sequenceLastModified: Long
) {
    fun getHeaderId(): Int = headerId

    fun getVideoId(): String? = videoId

    fun getItag(): Int = itag

    fun getLastModified(): Long = lastModified

    fun getXtags(): String? = xtags

    fun getStartRange(): Long = startRange

    fun getCompressionAlgorithm(): Int = compressionAlgorithm

    fun isInitSegment(): Boolean = initSegment

    fun getSequenceNumber(): Int = sequenceNumber

    fun getBitrateBps(): Long = bitrateBps

    fun getStartMs(): Long = startMs

    fun getDurationMs(): Long = durationMs

    fun getContentLength(): Long = contentLength

    fun getTimeRangeStartTicks(): Long = timeRangeStartTicks

    fun getTimeRangeDurationTicks(): Long = timeRangeDurationTicks

    fun getTimeRangeTimescale(): Int = timeRangeTimescale

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
                when (field.getNumber()) {
                    1 -> headerId = field.getVarint().toInt()
                    2 -> videoId = field.getString()
                    3 -> itag = field.getVarint().toInt()
                    4 -> lastModified = field.getVarint()
                    5 -> xtags = field.getString()
                    6 -> startRange = field.getVarint()
                    7 -> compressionAlgorithm = field.getVarint().toInt()
                    8 -> initSegment = field.getVarint() != 0L
                    9 -> sequenceNumber = field.getVarint().toInt()
                    10 -> bitrateBps = field.getVarint()
                    11 -> startMs = field.getVarint()
                    12 -> durationMs = field.getVarint()
                    13 -> {
                        val formatId = decodeFormatId(field.getBytes())
                        if (itag < 0) itag = formatId.itag
                        if (lastModified < 0) lastModified = formatId.lastModified
                        if (xtags == null) xtags = formatId.xtags
                    }
                    14 -> contentLength = field.getVarint()
                    15 -> {
                        val timeRange = decodeTimeRange(field.getBytes())
                        timeRangeStartTicks = timeRange.startTicks
                        timeRangeDurationTicks = timeRange.durationTicks
                        timeRangeTimescale = timeRange.timescale
                    }
                    16 -> sequenceLastModified = field.getVarint()
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
                    field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_VARINT -> {
                        itag = field.getVarint().toInt()
                    }
                    field.getNumber() == 2 && field.getWireType() == SabrProto.WIRE_VARINT -> {
                        lastModified = field.getVarint()
                    }
                    field.getNumber() == 3 && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED -> {
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
                    field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_VARINT -> {
                        startTicks = field.getVarint()
                    }
                    field.getNumber() == 2 && field.getWireType() == SabrProto.WIRE_VARINT -> {
                        durationTicks = field.getVarint()
                    }
                    field.getNumber() == 3 && field.getWireType() == SabrProto.WIRE_VARINT -> {
                        timescale = field.getVarint().toInt()
                    }
                }
            }
            return TimeRange(startTicks, durationTicks, timescale)
        }
    }
}
