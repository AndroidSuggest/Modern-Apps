package org.schabi.newpipe.extractor.services.youtube.sabr

class SabrReloadPlayerResponse private constructor(
    private val reloadPlaybackParamsToken: String?
) {
    internal companion object {
        @JvmStatic
        @Throws(SabrProtocolException::class)
        fun decode(data: ByteArray): SabrReloadPlayerResponse {
            var token: String? = null
            for (field in SabrProto.readFields(data)) {
                if (field.number == 1 && field.wireType == SabrProto.WIRE_LENGTH_DELIMITED) {
                    token = decodeReloadPlaybackContext(field.getBytes())
                }
            }
            return SabrReloadPlayerResponse(token)
        }

        @Throws(SabrProtocolException::class)
        private fun decodeReloadPlaybackContext(data: ByteArray): String? {
            for (field in SabrProto.readFields(data)) {
                if (field.number == 1 && field.wireType == SabrProto.WIRE_LENGTH_DELIMITED) {
                    return decodeReloadPlaybackParams(field.getBytes())
                }
            }
            return null
        }

        @Throws(SabrProtocolException::class)
        private fun decodeReloadPlaybackParams(data: ByteArray): String? {
            for (field in SabrProto.readFields(data)) {
                if (field.number == 1 && field.wireType == SabrProto.WIRE_LENGTH_DELIMITED) {
                    return field.getString()
                }
            }
            return null
        }
    }

    fun getReloadPlaybackParamsToken(): String? = reloadPlaybackParamsToken

    fun summarize(): String =
        "reloadPlaybackParamsTokenLength=" + (reloadPlaybackParamsToken?.length ?: 0)
}
