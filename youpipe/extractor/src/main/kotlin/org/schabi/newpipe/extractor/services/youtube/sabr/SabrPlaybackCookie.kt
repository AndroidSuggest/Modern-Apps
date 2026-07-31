package org.schabi.newpipe.extractor.services.youtube.sabr


class SabrPlaybackCookie private constructor(
    val resolution: Int,
    val field2: Int,
    val videoItag: Int,
    val videoLastModified: Long,
    private val videoXtagsPresent: Boolean,
    val audioItag: Int,
    val audioLastModified: Long,
    private val audioXtagsPresent: Boolean,
    extraVarints: Map<Int, Long>,
    private val extraFields: String
) {
    private val extraVarints: Map<Int, Long> =
        extraVarints.toMap()

    companion object {
        @Throws(SabrProtocolException::class)
        @JvmStatic
        internal fun decode(data: ByteArray): SabrPlaybackCookie {
            var resolution = -1
            var field2 = -1
            var videoItag = -1
            var videoLastModified: Long = -1
            var videoXtagsPresent = false
            var audioItag = -1
            var audioLastModified: Long = -1
            var audioXtagsPresent = false
            val extraVarints: MutableMap<Int, Long> = LinkedHashMap()
            val extraFields = SabrProto.summarizeUnknownFields(data, 1, 2, 7, 8)

            for (field in SabrProto.readFields(data)) {
                when (field.number) {
                    1 -> resolution = field.varint.toInt()
                    2 -> field2 = field.varint.toInt()
                    7 -> {
                        val video = decodeFormatId(field.getBytes())
                        videoItag = video.itag
                        videoLastModified = video.lastModified
                        videoXtagsPresent = video.xtagsPresent
                    }
                    8 -> {
                        val audio = decodeFormatId(field.getBytes())
                        audioItag = audio.itag
                        audioLastModified = audio.lastModified
                        audioXtagsPresent = audio.xtagsPresent
                    }
                    else -> {
                        if (field.wireType == SabrProto.WIRE_VARINT) {
                            extraVarints[field.number] = field.varint
                        }
                    }
                }
            }
            return SabrPlaybackCookie(
                resolution, field2, videoItag, videoLastModified,
                videoXtagsPresent, audioItag, audioLastModified, audioXtagsPresent,
                extraVarints, extraFields
            )
        }

        @Throws(SabrProtocolException::class)
        private fun decodeFormatId(data: ByteArray): FormatId {
            var itag = -1
            var lastModified: Long = -1
            var xtagsPresent = false
            for (field in SabrProto.readFields(data)) {
                if (field.number == 1 && field.wireType == SabrProto.WIRE_VARINT) {
                    itag = field.varint.toInt()
                } else if (field.number == 2 && field.wireType == SabrProto.WIRE_VARINT) {
                    lastModified = field.varint
                } else if (field.number == 3 && field.wireType == SabrProto.WIRE_LENGTH_DELIMITED) {
                    xtagsPresent = field.getBytes().isNotEmpty()
                }
            }
            return FormatId(itag, lastModified, xtagsPresent)
        }
    }

    fun isVideoXtagsPresent(): Boolean = videoXtagsPresent

    fun isAudioXtagsPresent(): Boolean = audioXtagsPresent

    fun getExtraVarints(): Map<Int, Long> = extraVarints

    fun getExtraFields(): String = extraFields

    fun summarize(): String {
        return "resolution=$resolution" +
            ", field2=$field2" +
            ", videoItag=$videoItag" + (if (videoXtagsPresent) "+xtags" else "") +
            ", audioItag=$audioItag" + (if (audioXtagsPresent) "+xtags" else "") +
            ", extraVarints=$extraVarints" +
            (if ("none" == extraFields) "" else ", extraFields=$extraFields")
    }

    private class FormatId(
        val itag: Int,
        val lastModified: Long,
        val xtagsPresent: Boolean
    )
}
