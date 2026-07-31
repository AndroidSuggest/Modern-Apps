package org.schabi.newpipe.extractor.services.youtube.sabr

class SabrSnackbarMessage private constructor(
    private val id: Int
) {
    fun getId(): Int = id

    fun summarize(): String = "id=$id"

    companion object {
        @JvmStatic
        @Throws(SabrProtocolException::class)
        internal fun decode(data: ByteArray): SabrSnackbarMessage {
            var id = -1
            for (field in SabrProto.readFields(data)) {
                if (field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_VARINT) {
                    id = field.getVarint().toInt()
                }
            }
            return SabrSnackbarMessage(id)
        }
    }
}
