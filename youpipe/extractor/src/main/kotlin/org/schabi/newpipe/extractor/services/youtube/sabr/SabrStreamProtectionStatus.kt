package org.schabi.newpipe.extractor.services.youtube.sabr

class SabrStreamProtectionStatus private constructor(
    private val status: Int,
    private val maxRetries: Int,
    private val unknownFields: String
) {
    fun getStatus(): Int = status
    fun getMaxRetries(): Int = maxRetries
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
                    field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_VARINT -> {
                        status = field.getVarint().toInt()
                    }
                    field.getNumber() == 2 && field.getWireType() == SabrProto.WIRE_VARINT -> {
                        maxRetries = field.getVarint().toInt()
                    }
                }
            }
            return SabrStreamProtectionStatus(status, maxRetries, unknownFields)
        }
    }
}
