package org.schabi.newpipe.extractor.services.youtube.sabr

final class SabrRequestIdentifier private constructor(
    private val token: String?
) {
    internal companion object {
        @JvmStatic
        @Throws(SabrProtocolException::class)
        fun decode(data: ByteArray): SabrRequestIdentifier {
            var token: String? = null
            for (field in SabrProto.readFields(data)) {
                if (field.number == 1 && field.wireType == SabrProto.WIRE_LENGTH_DELIMITED) {
                    token = field.getString()
                }
            }
            return SabrRequestIdentifier(token)
        }
    }

    fun getToken(): String? = token

    fun summarize(): String {
        return "tokenLength=" + (token?.length ?: 0)
    }
}
