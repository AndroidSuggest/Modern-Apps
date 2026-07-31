package org.schabi.newpipe.extractor.services.youtube.sabr

class SabrSeek private constructor(
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
                    field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_VARINT ->
                        seekMediaTime = field.getVarint()
                    field.getNumber() == 2 && field.getWireType() == SabrProto.WIRE_VARINT ->
                        seekMediaTimescale = field.getVarint().toInt()
                    field.getNumber() == 3 && field.getWireType() == SabrProto.WIRE_VARINT ->
                        seekSource = field.getVarint().toInt()
                }
            }
            return SabrSeek(seekMediaTime, seekMediaTimescale, seekSource)
        }
    }

    fun getSeekMediaTime(): Long = seekMediaTime
    fun getSeekMediaTimescale(): Int = seekMediaTimescale
    fun getSeekSource(): Int = seekSource

    fun summarize(): String = "seek=$seekMediaTime/$seekMediaTimescale, source=$seekSource"
}
