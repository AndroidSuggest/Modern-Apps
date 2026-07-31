package org.schabi.newpipe.extractor.services.youtube.sabr

class SabrOnesieInnertubeResponse private constructor(
    private val proxyStatus: Int,
    private val httpStatus: Int,
    private val headerCount: Int,
    private val bodyBytes: Int
) {
    companion object {
        @Throws(SabrProtocolException::class)
        @JvmStatic
        internal fun decode(data: ByteArray): SabrOnesieInnertubeResponse {
            var proxyStatus = -1
            var httpStatus = -1
            var headerCount = 0
            var bodyBytes = 0
            for (field in SabrProto.readFields(data)) {
                if (field.number == 1 && field.wireType == SabrProto.WIRE_VARINT) {
                    proxyStatus = field.varint.toInt()
                } else if (field.number == 2 && field.wireType == SabrProto.WIRE_VARINT) {
                    httpStatus = field.varint.toInt()
                } else if (field.number == 3 && field.wireType == SabrProto.WIRE_LENGTH_DELIMITED) {
                    headerCount++
                } else if (field.number == 4 && field.wireType == SabrProto.WIRE_LENGTH_DELIMITED) {
                    bodyBytes = field.getBytes().size
                }
            }
            return SabrOnesieInnertubeResponse(proxyStatus, httpStatus, headerCount, bodyBytes)
        }
    }

    fun summarize(): String {
        return "proxyStatus=$proxyStatus, httpStatus=$httpStatus, headers=$headerCount, bodyBytes=$bodyBytes"
    }
}
