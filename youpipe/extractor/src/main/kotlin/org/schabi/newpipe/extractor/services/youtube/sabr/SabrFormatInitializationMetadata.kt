package org.schabi.newpipe.extractor.services.youtube.sabr

class SabrFormatInitializationMetadata private constructor(
    val videoId: String?,
    private val rawSummaryBytes: ByteArray,
    val itag: Int,
    val lastModified: Long,
    val xtags: String?,
    private val endTimeMs: Long,
    val endSegmentNumber: Long,
    val mimeType: String?,
    val initRangeStart: Long,
    val initRangeEnd: Long,
    val indexRangeStart: Long,
    val indexRangeEnd: Long,
    val field8: Long,
    val durationUnits: Long,
    private val durationTimescale: Long
) {

    fun getDurationTimescale(): Long = durationTimescale

    fun summarize(): String {
        var unknown = "unknown-error"
        try {
            unknown = SabrProto.summarizeUnknownFields(rawSummaryBytes, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        } catch (ignored: Exception) {
        }
        return "itag=$itag, endSegment=$endSegmentNumber, endTimeMs=$endTimeMs, " +
            "mime=${mimeType ?: "null"}, init=$initRangeStart-$initRangeEnd, " +
            "index=$indexRangeStart-$indexRangeEnd, field8=$field8, " +
            "duration=$durationUnits/$durationTimescale, unknown=$unknown"
    }

    private class Range(val start: Long, val end: Long)

    companion object {
        @JvmStatic
        fun normalized(
            videoId: String?,
            itag: Int,
            lastModified: Long,
            xtags: String?,
            endTimeMs: Long,
            endSegmentNumber: Long,
            mimeType: String?,
            initRangeStart: Long,
            initRangeEnd: Long,
            indexRangeStart: Long,
            indexRangeEnd: Long,
            field8: Long,
            durationUnits: Long,
            durationTimescale: Long
        ): SabrFormatInitializationMetadata {
            if (itag <= 0 || endSegmentNumber < -1 || durationUnits < -1 || durationTimescale < -1) {
                throw IllegalArgumentException("Invalid normalized SABR format metadata")
            }
            return SabrFormatInitializationMetadata(
                videoId, ByteArray(0), itag, lastModified, xtags, endTimeMs,
                endSegmentNumber, mimeType, initRangeStart, initRangeEnd,
                indexRangeStart, indexRangeEnd, field8, durationUnits, durationTimescale
            )
        }

        @JvmStatic
        @Throws(SabrProtocolException::class)
        internal fun decode(data: ByteArray): SabrFormatInitializationMetadata {
            var videoId: String? = null
            var itag = -1
            var lastModified: Long = -1
            var xtags: String? = null
            var endTimeMs: Long = -1
            var endSegmentNumber: Long = -1
            var mimeType: String? = null
            var initRangeStart: Long = -1
            var initRangeEnd: Long = -1
            var indexRangeStart: Long = -1
            var indexRangeEnd: Long = -1
            var field8: Long = -1
            var durationUnits: Long = -1
            var durationTimescale: Long = -1

            for (field in SabrProto.readFields(data)) {
                when (field.number) {
                    1 -> videoId = field.getString()
                    2 -> {
                        for (formatField in SabrProto.readFields(field.getBytes())) {
                            when (formatField.number) {
                                1 -> itag = formatField.varint.toInt()
                                2 -> lastModified = formatField.varint
                                3 -> xtags = formatField.getString()
                            }
                        }
                    }
                    3 -> endTimeMs = field.varint
                    4 -> endSegmentNumber = field.varint
                    5 -> mimeType = field.getString()
                    6 -> {
                        val initRange = decodeRange(field.getBytes())
                        initRangeStart = initRange.start
                        initRangeEnd = initRange.end
                    }
                    7 -> {
                        val indexRange = decodeRange(field.getBytes())
                        indexRangeStart = indexRange.start
                        indexRangeEnd = indexRange.end
                    }
                    8 -> field8 = field.varint
                    9 -> durationUnits = field.varint
                    10 -> durationTimescale = field.varint
                }
            }

            return SabrFormatInitializationMetadata(
                videoId, data.clone(), itag, lastModified, xtags,
                endTimeMs, endSegmentNumber, mimeType, initRangeStart, initRangeEnd,
                indexRangeStart, indexRangeEnd, field8, durationUnits, durationTimescale
            )
        }

        @Throws(SabrProtocolException::class)
        private fun decodeRange(data: ByteArray): Range {
            var start: Long = -1
            var end: Long = -1
            for (field in SabrProto.readFields(data)) {
                if ((field.number == 1 || field.number == 3)
                    && field.wireType == SabrProto.WIRE_VARINT
                ) {
                    start = field.varint
                } else if ((field.number == 2 || field.number == 4)
                    && field.wireType == SabrProto.WIRE_VARINT
                ) {
                    end = field.varint
                }
            }
            return Range(start, end)
        }
    }
}
