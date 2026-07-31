package org.schabi.newpipe.extractor.services.youtube.sabr

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

class SabrOnesieData private constructor(
    private val encrypted: Boolean,
    private val payloadBytes: Int,
    private val header: SabrOnesieHeader?,
    private val innertubeResponse: SabrOnesieInnertubeResponse?
) {
    companion object {
        @JvmStatic
        internal fun fromPart(
            data: ByteArray,
            encrypted: Boolean,
            header: SabrOnesieHeader?
        ): SabrOnesieData {
            return SabrOnesieData(
                encrypted, data.size, header,
                tryDecodeInnertubeResponse(data, encrypted, header)
            )
        }

        private fun tryDecodeInnertubeResponse(
            data: ByteArray,
            encrypted: Boolean,
            header: SabrOnesieHeader?
        ): SabrOnesieInnertubeResponse? {
            if (encrypted || header == null || header.getType() != 0 || header.hasEncryptionMaterial()) {
                return null
            }
            val decodedData = maybeDecompress(data, header) ?: return null
            return try {
                SabrOnesieInnertubeResponse.decode(decodedData)
            } catch (ignored: SabrProtocolException) {
                null
            }
        }

        private fun maybeDecompress(data: ByteArray, header: SabrOnesieHeader): ByteArray? {
            if (header.getCryptoCompressionType() < 0 || header.getCryptoCompressionType() == 0) {
                return data
            }
            if (header.getCryptoCompressionType() != 1) {
                return null
            }
            try {
                GZIPInputStream(ByteArrayInputStream(data)).use { input ->
                    ByteArrayOutputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                        }
                        return output.toByteArray()
                    }
                }
            } catch (ignored: IOException) {
                return null
            }
        }
    }

    fun isEncrypted(): Boolean = encrypted

    fun getPayloadBytes(): Int = payloadBytes

    fun getHeader(): SabrOnesieHeader? = header

    fun getInnertubeResponse(): SabrOnesieInnertubeResponse? = innertubeResponse

    fun summarize(): String {
        if (header == null) {
            return "encrypted=$encrypted, payloadBytes=$payloadBytes, header=null, innertubeResponse=null"
        }
        return "encrypted=$encrypted" +
            ", payloadBytes=$payloadBytes" +
            ", headerType=${header.getTypeSummary()}" +
            ", headerItag=${header.getItag() ?: "null"}" +
            ", headerSeq=${header.getSequenceNumber()}" +
            ", headerCrypto=${header.hasCryptoParams()}" +
            ", headerEncrypted=${header.hasEncryptionMaterial()}" +
            ", innertubeResponse=" + if (innertubeResponse == null) "null"
        else
            "[${innertubeResponse.summarize()}]"
    }
}
