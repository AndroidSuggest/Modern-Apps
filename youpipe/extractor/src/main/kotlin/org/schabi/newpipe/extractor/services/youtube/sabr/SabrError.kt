package org.schabi.newpipe.extractor.services.youtube.sabr

class SabrError private constructor(
    val type: String?,
    private val code: Int
) {

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
                    field.number == 1 && field.wireType == SabrProto.WIRE_LENGTH_DELIMITED -> {
                        type = field.getString()
                    }
                    field.number == 2 && field.wireType == SabrProto.WIRE_VARINT -> {
                        code = field.varint.toInt()
                    }
                }
            }
            return SabrError(type, code)
        }
    }
}
