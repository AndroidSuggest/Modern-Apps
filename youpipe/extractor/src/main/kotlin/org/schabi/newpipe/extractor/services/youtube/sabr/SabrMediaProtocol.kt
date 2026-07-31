package org.schabi.newpipe.extractor.services.youtube.sabr

/** Host-facing media envelope operations. Control policy code never receives media payloads. */
interface SabrMediaProtocol {
    fun getHeaderPartType(): Int
    fun getMediaPartType(): Int
    fun getEndPartType(): Int

    @Throws(SabrProtocolException::class)
    fun decodeHeader(payload: ByteArray): SabrMediaHeader

    companion object {
        @JvmStatic
        fun builtin(): SabrMediaProtocol = BuiltinHolder.INSTANCE
    }

    class BuiltinHolder private constructor() {
        companion object {
            val INSTANCE: SabrMediaProtocol = object : SabrMediaProtocol {
                override fun getHeaderPartType(): Int = SabrResponseDecoder.MEDIA_HEADER
                override fun getMediaPartType(): Int = SabrResponseDecoder.MEDIA
                override fun getEndPartType(): Int = SabrResponseDecoder.MEDIA_END

                @Throws(SabrProtocolException::class)
                override fun decodeHeader(payload: ByteArray): SabrMediaHeader =
                    SabrMediaHeader.decode(payload)
            }
        }
    }
}
