package org.schabi.newpipe.extractor.services.youtube.sabr

final class SabrSeek private constructor(
    private val seekMediaTime: Long,
    private val seekMediaTimescale: Int,
    private val seekSource: Int
) {
    internal companion object {
        @JvmStatic
        @Throws(SabrProtocolException::class)
        fun decode(data: ByteArray): SabrSeek {
            var seekMediaTime: Long = -1
            var seekMediaTimescale = -1
            var seekSource = -1
            for (field in SabrProto.readFields(data)) {
                when {
                    field.number == 1 && field.wireType == SabrProto.WIRE_VARINT -> {
                        seekMediaTime = field.varint
                    }
                    field.number == 2 && field.wireType == SabrProto.WIRE_VARINT -> {
                        seekMediaTimescale = field.varint.toInt()
                    }
                    field.number == 3 && field.wireType == SabrProto.WIRE_VARINT -> {
                        seekSource = field.varint.toInt()
                    }
                }
            }
            return SabrSeek(seekMediaTime, seekMediaTimescale, seekSource)
        }
    }

    fun getSeekMediaTime(): Long = seekMediaTime

    fun getSeekMediaTimescale(): Int = seekMediaTimescale

    fun getSeekSource(): Int = seekSource

    fun summarize(): String {
        return "seek=$seekMediaTime/$seekMediaTimescale, source=$seekSource"
    }
}
