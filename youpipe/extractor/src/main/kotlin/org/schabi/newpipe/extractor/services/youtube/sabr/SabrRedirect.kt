package org.schabi.newpipe.extractor.services.youtube.sabr

class SabrRedirect private constructor(
    private val url: String?
) {
    internal companion object {
        @JvmStatic
        @Throws(SabrProtocolException::class)
        fun decode(data: ByteArray): SabrRedirect {
            var url: String? = null
            for (field in SabrProto.readFields(data)) {
                if (field.number == 1 && field.wireType == SabrProto.WIRE_LENGTH_DELIMITED) {
                    url = field.getString()
                }
            }
            return SabrRedirect(url)
        }
    }

    fun getUrl(): String? = url

    fun summarize(): String = "urlLength=" + (url?.length ?: 0)
}
