package org.schabi.newpipe.extractor.services.youtube.sabr

class SabrFormatSelectionConfig private constructor(
    itags: List<Int>,
    private val videoId: String?,
    private val resolution: Int
) {
    private val itags: List<Int> = itags.toList()

    fun getItags(): List<Int> = itags

    fun getVideoId(): String? = videoId

    fun getResolution(): Int = resolution

    fun summarize(): String {
        val builder = StringBuilder()
        builder.append("itags=").append(itags.size).append('[')
        val sampleSize = minOf(8, itags.size)
        for (i in 0 until sampleSize) {
            if (i > 0) builder.append(',')
            builder.append(itags[i])
        }
        if (itags.size > sampleSize) {
            builder.append(",...")
        }
        builder.append(']')
            .append(", videoIdLength=").append(videoId?.length ?: 0)
            .append(", resolution=").append(resolution)
        return builder.toString()
    }

    companion object {
        @JvmStatic
        @Throws(SabrProtocolException::class)
        internal fun decode(data: ByteArray): SabrFormatSelectionConfig {
            val itags = mutableListOf<Int>()
            var videoId: String? = null
            var resolution = 0
            for (field in SabrProto.readFields(data)) {
                when (field.getNumber()) {
                    2 -> {
                        if (field.getWireType() == SabrProto.WIRE_VARINT) {
                            itags.add(field.getVarint().toInt())
                        } else if (field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                            for (itag in SabrProto.readPackedVarints(field.getBytes())) {
                                itags.add(itag.toInt())
                            }
                        }
                    }
                    3 -> if (field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                        videoId = field.getString()
                    }
                    4 -> if (field.getWireType() == SabrProto.WIRE_VARINT) {
                        resolution = field.getVarint().toInt()
                    }
                }
            }
            return SabrFormatSelectionConfig(itags, videoId, resolution)
        }
    }
}
