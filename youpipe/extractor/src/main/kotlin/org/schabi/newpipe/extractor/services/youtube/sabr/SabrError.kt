package org.schabi.newpipe.extractor.services.youtube.sabr

class SabrError private constructor(
    private val type: String?,
    private val code: Int
) {
    fun getType(): String? = type

    fun getCode(): Int = code

    fun summarize(): String = "type=${type ?: "null"}, code=$code"

    companion object {
        @JvmStatic
        @Throws(SabrProtocolException::class)
        internal fun decode(data: ByteArray): SabrError {
            var type: String? = null
            var code = 0
            for (field in SabrProto.readFields(data)) {
                when {
                    field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED -> {
                        type = field.getString()
                    }
                    field.getNumber() == 2 && field.getWireType() == SabrProto.WIRE_VARINT -> {
                        code = field.getVarint().toInt()
                    }
                }
            }
            return SabrError(type, code)
        }
    }
}
