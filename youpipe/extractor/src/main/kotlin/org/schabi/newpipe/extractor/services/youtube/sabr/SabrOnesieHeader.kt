package org.schabi.newpipe.extractor.services.youtube.sabr

class SabrOnesieHeader private constructor(
    private val type: Int,
    private val videoId: String?,
    private val itag: String?,
    private val cryptoParamsBytes: Int,
    private val cryptoHmacBytes: Int,
    private val cryptoIvBytes: Int,
    private val cryptoCompressionType: Int,
    private val lastModified: Long,
    private val expectedMediaSizeBytes: Long,
    private val restrictedFormatCount: Int,
    private val xtags: String?,
    private val sequenceNumber: Long,
    private val field23VideoIdLength: Int,
    private val field34ItagDenylistCount: Int
) {
    companion object {
        @Throws(SabrProtocolException::class)
        @JvmStatic
        internal fun decode(data: ByteArray): SabrOnesieHeader {
            var type = -1
            var videoId: String? = null
            var itag: String? = null
            var cryptoParamsBytes = 0
            var cryptoHmacBytes = 0
            var cryptoIvBytes = 0
            var cryptoCompressionType = -1
            var lastModified: Long = -1
            var expectedMediaSizeBytes: Long = -1
            var restrictedFormatCount = 0
            var xtags: String? = null
            var sequenceNumber: Long = -1
            var field23VideoIdLength = 0
            var field34ItagDenylistCount = 0
            for (field in SabrProto.readFields(data)) {
                when (field.number) {
                    1 -> type = field.varint.toInt()
                    2 -> videoId = field.getString()
                    3 -> itag = field.getString()
                    4 -> {
                        val cryptoParams = field.getBytes()
                        cryptoParamsBytes = cryptoParams.size
                        val cryptoParamsSummary = decodeCryptoParams(cryptoParams)
                        cryptoHmacBytes = cryptoParamsSummary.hmacBytes
                        cryptoIvBytes = cryptoParamsSummary.ivBytes
                        cryptoCompressionType = cryptoParamsSummary.compressionType
                    }
                    5 -> lastModified = field.varint
                    7 -> expectedMediaSizeBytes = field.varint
                    11 -> restrictedFormatCount++
                    15 -> xtags = field.getString()
                    18 -> sequenceNumber = field.varint
                    23 -> field23VideoIdLength = decodeField23VideoIdLength(field.getBytes())
                    34 -> field34ItagDenylistCount = decodeField34ItagDenylistCount(field.getBytes())
                    else -> {}
                }
            }
            return SabrOnesieHeader(
                type, videoId, itag, cryptoParamsBytes,
                cryptoHmacBytes, cryptoIvBytes, cryptoCompressionType, lastModified,
                expectedMediaSizeBytes, restrictedFormatCount, xtags, sequenceNumber,
                field23VideoIdLength, field34ItagDenylistCount
            )
        }

        @Throws(SabrProtocolException::class)
        private fun decodeCryptoParams(data: ByteArray): CryptoParamsSummary {
            var hmacBytes = 0
            var ivBytes = 0
            var compressionType = -1
            for (field in SabrProto.readFields(data)) {
                if (field.number == 4 && field.wireType == SabrProto.WIRE_LENGTH_DELIMITED) {
                    hmacBytes = field.getBytes().size
                } else if (field.number == 5 && field.wireType == SabrProto.WIRE_LENGTH_DELIMITED) {
                    ivBytes = field.getBytes().size
                } else if (field.number == 6 && field.wireType == SabrProto.WIRE_VARINT) {
                    compressionType = field.varint.toInt()
                }
            }
            return CryptoParamsSummary(hmacBytes, ivBytes, compressionType)
        }

        @Throws(SabrProtocolException::class)
        private fun decodeField23VideoIdLength(data: ByteArray): Int {
            for (field in SabrProto.readFields(data)) {
                if (field.number == 2 && field.wireType == SabrProto.WIRE_LENGTH_DELIMITED) {
                    return field.getString().length
                }
            }
            return 0
        }

        @Throws(SabrProtocolException::class)
        private fun decodeField34ItagDenylistCount(data: ByteArray): Int {
            var count = 0
            for (field in SabrProto.readFields(data)) {
                if (field.number == 1) {
                    count++
                }
            }
            return count
        }
    }

    fun summarize(): String {
        return "type=${getTypeSummary()}" +
            ", videoIdLength=${videoId?.length ?: 0}" +
            ", itag=${itag ?: "null"}" +
            ", cryptoParamsBytes=$cryptoParamsBytes" +
            ", cryptoHmacBytes=$cryptoHmacBytes" +
            ", cryptoIvBytes=$cryptoIvBytes" +
            ", cryptoCompression=$cryptoCompressionType" +
            ", lastModified=$lastModified" +
            ", expectedMediaSizeBytes=$expectedMediaSizeBytes" +
            ", restrictedFormats=$restrictedFormatCount" +
            ", xtags=${xtags != null}" +
            ", sequenceNumber=$sequenceNumber" +
            ", field23VideoIdLength=$field23VideoIdLength" +
            ", field34ItagDenylistCount=$field34ItagDenylistCount"
    }

    internal fun getType(): Int = type

    internal fun getTypeSummary(): String = "$type/${getTypeName()}"

    internal fun getTypeName(): String = when (type) {
        0 -> "ONESIE_PLAYER_RESPONSE"
        1 -> "MEDIA"
        2 -> "MEDIA_DECRYPTION_KEY"
        3 -> "CLEAR_MEDIA"
        4 -> "CLEAR_INIT_SEGMENT"
        5 -> "ACK"
        6 -> "MEDIA_STREAMER_HOSTNAME"
        7 -> "MEDIA_SIZE_HINT"
        8 -> "PLAYER_SERVICE_RESPONSE_PUSH_URL"
        9 -> "LAST_HIGH_PRIORITY_HINT"
        16 -> "STREAM_METADATA"
        25 -> "ENCRYPTED_INNERTUBE_RESPONSE_PART"
        else -> "UNKNOWN"
    }

    internal fun getItag(): String? = itag

    internal fun getSequenceNumber(): Long = sequenceNumber

    internal fun hasCryptoParams(): Boolean = cryptoParamsBytes > 0

    internal fun hasEncryptionMaterial(): Boolean = cryptoHmacBytes > 0 || cryptoIvBytes > 0

    internal fun getCryptoCompressionType(): Int = cryptoCompressionType

    private class CryptoParamsSummary(
        val hmacBytes: Int,
        val ivBytes: Int,
        val compressionType: Int
    )
}
