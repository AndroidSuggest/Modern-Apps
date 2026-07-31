package org.schabi.newpipe.extractor.services.youtube.sabr

class SabrStreamProtectionStatus private constructor(
    val status: Int,
    val maxRetries: Int,
    private val unknownFields: String
) {
    fun getUnknownFields(): String = unknownFields

    fun summarize(): String {
        return "status=$status, maxRetries=$maxRetries" +
            (if ("none" == unknownFields) "" else ", unknown=$unknownFields")
    }

    companion object {
        @JvmStatic
        @Throws(SabrProtocolException::class)
        internal fun decode(data: ByteArray): SabrStreamProtectionStatus {
            var status = -1
            var maxRetries = -1
            val unknownFields = SabrProto.summarizeUnknownFields(data, 1, 2)
            for (field in SabrProto.readFields(data)) {
                when {
                    field.number == 1 && field.wireType == SabrProto.WIRE_VARINT -> {
                        status = field.varint.toInt()
                    }
                    field.number == 2 && field.wireType == SabrProto.WIRE_VARINT -> {
                        maxRetries = field.varint.toInt()
                    }
                }
            }
            return SabrStreamProtectionStatus(status, maxRetries, unknownFields)
        }
    }
}
