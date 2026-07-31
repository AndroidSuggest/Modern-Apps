package org.schabi.newpipe.extractor.services.youtube.sabr

class SabrContextValue private constructor(
    private val timingInfo: TimingInfo?,
    private val signatureLength: Int,
    private val field5: Int
) {
    fun getTimingInfo(): TimingInfo? = timingInfo

    fun getSignatureLength(): Int = signatureLength

    fun getField5(): Int = field5

    fun summarize(): String =
        "timing=${timingInfo?.summarize() ?: "null"}, signatureBytes=$signatureLength, field5=$field5"

    companion object {
        @JvmStatic
        @Throws(SabrProtocolException::class)
        internal fun decode(data: ByteArray): SabrContextValue {
            var timingInfo: TimingInfo? = null
            var signatureLength = 0
            var field5 = -1
            for (field in SabrProto.readFields(data)) {
                when {
                    field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED -> {
                        timingInfo = TimingInfo.decode(field.getBytes())
                    }
                    field.getNumber() == 2 && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED -> {
                        signatureLength = field.getBytes().size
                    }
                    field.getNumber() == 5 && field.getWireType() == SabrProto.WIRE_VARINT -> {
                        field5 = field.getVarint().toInt()
                    }
                }
            }
            return SabrContextValue(timingInfo, signatureLength, field5)
        }
    }

    class TimingInfo private constructor(
        private val timestampMs: Long,
        private val durationMs: Int,
        private val contentInfo: ContentInfo?
    ) {
        fun getTimestampMs(): Long = timestampMs

        fun getDurationMs(): Int = durationMs

        fun getContentInfo(): ContentInfo? = contentInfo

        internal fun summarize(): String =
            "timestampMs=$timestampMs/durationMs=$durationMs/content=${contentInfo?.summarize() ?: "null"}"

        companion object {
            @Throws(SabrProtocolException::class)
            internal fun decode(data: ByteArray): TimingInfo {
                var timestampMs: Long = -1
                var durationMs = -1
                var contentInfo: ContentInfo? = null
                for (field in SabrProto.readFields(data)) {
                    when {
                        field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_VARINT -> {
                            timestampMs = field.getVarint()
                        }
                        field.getNumber() == 2 && field.getWireType() == SabrProto.WIRE_VARINT -> {
                            durationMs = field.getVarint().toInt()
                        }
                        field.getNumber() == 3 && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED -> {
                            contentInfo = ContentInfo.decode(field.getBytes())
                        }
                    }
                }
                return TimingInfo(timestampMs, durationMs, contentInfo)
            }
        }
    }

    class ContentInfo private constructor(
        private val contentId: String?,
        private val contentType: Int
    ) {
        fun getContentId(): String? = contentId

        fun getContentType(): Int = contentType

        internal fun summarize(): String {
            val len = contentId?.length ?: 0
            return "contentIdLength=$len/contentType=$contentType"
        }

        companion object {
            @Throws(SabrProtocolException::class)
            internal fun decode(data: ByteArray): ContentInfo {
                var contentId: String? = null
                var contentType = -1
                for (field in SabrProto.readFields(data)) {
                    when {
                        field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED -> {
                            contentId = field.getString()
                        }
                        field.getNumber() == 2 && field.getWireType() == SabrProto.WIRE_VARINT -> {
                            contentType = field.getVarint().toInt()
                        }
                    }
                }
                return ContentInfo(contentId, contentType)
            }
        }
    }
}
