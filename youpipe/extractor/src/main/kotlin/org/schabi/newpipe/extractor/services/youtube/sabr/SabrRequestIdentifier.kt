package org.schabi.newpipe.extractor.services.youtube.sabr

class SabrRequestIdentifier private constructor(
    private val token: String?
) {
    internal companion object {
        @JvmStatic
        @Throws(SabrProtocolException::class)
        fun decode(data: ByteArray): SabrRequestIdentifier {
            var token: String? = null
            for (field in SabrProto.readFields(data)) {
                if (field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                    token = field.getString()
                }
            }
            return SabrRequestIdentifier(token)
        }
    }

    fun getToken(): String? = token

    fun summarize(): String = "tokenLength=" + (token?.length ?: 0)
}
