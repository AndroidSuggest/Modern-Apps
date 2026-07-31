package org.schabi.newpipe.extractor.services.youtube.sabr

class SabrContextUpdate private constructor(
    val type: Int,
    val scope: Int,
    value: ByteArray,
    private val sendByDefault: Boolean,
    val writePolicy: Int,
    private val decodedValue: SabrContextValue?
) {
    private val value: ByteArray = value.clone()

    fun toStreamerContextProto(): ByteArray {
        val context = SabrProto.Writer()
        context.writeInt32(1, type)
        context.writeBytes(2, value)
        return context.toByteArray()
    }

    fun getValue(): ByteArray = value.clone()

    internal fun getValueLength(): Int = value.size

    fun isSendByDefault(): Boolean = sendByDefault

    fun getDecodedValue(): SabrContextValue? = decodedValue

    fun summarize(): String =
        "type=$type, scope=$scope, valueBytes=${value.size}, sendByDefault=$sendByDefault, " +
            "writePolicy=$writePolicy, value=${decodedValue?.summarize() ?: "undecoded"}"

    companion object {
        const val WRITE_POLICY_OVERWRITE: Int = 1
        const val WRITE_POLICY_KEEP_EXISTING: Int = 2

        @JvmStatic
        fun normalized(
            type: Int,
            scope: Int,
            value: ByteArray,
            sendByDefault: Boolean,
            writePolicy: Int
        ): SabrContextUpdate {
            if (type < 0 || value.isEmpty() || value.size > 64 * 1024) {
                throw IllegalArgumentException("Invalid normalized SABR context update")
            }
            return SabrContextUpdate(type, scope, value, sendByDefault, writePolicy, null)
        }

        @JvmStatic
        @Throws(SabrProtocolException::class)
        internal fun decode(data: ByteArray): SabrContextUpdate {
            var type = -1
            var scope = -1
            var value = ByteArray(0)
            var sendByDefault = false
            var writePolicy = -1
            var decodedValue: SabrContextValue? = null

            for (field in SabrProto.readFields(data)) {
                when (field.number) {
                    1 -> type = field.varint.toInt()
                    2 -> scope = field.varint.toInt()
                    3 -> {
                        value = field.getBytes()
                        decodedValue = try {
                            SabrContextValue.decode(value)
                        } catch (ignored: SabrProtocolException) {
                            null
                        }
                    }
                    4 -> sendByDefault = field.varint != 0L
                    5 -> writePolicy = field.varint.toInt()
                }
            }

            return SabrContextUpdate(type, scope, value, sendByDefault, writePolicy, decodedValue)
        }
    }
}
