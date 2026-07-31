package org.schabi.newpipe.extractor.services.youtube.sabr

import java.util.Collections

class SabrSelectableFormats private constructor(
    videoFormats: List<FormatId>,
    audioFormats: List<FormatId>,
    wrappedVideoFormats: List<FormatId>,
    wrappedAudioFormats: List<FormatId>,
    private val otherFieldCount: Int
) {
    private val videoFormats: List<FormatId> = Collections.unmodifiableList(ArrayList(videoFormats))
    private val audioFormats: List<FormatId> = Collections.unmodifiableList(ArrayList(audioFormats))
    private val wrappedVideoFormats: List<FormatId> = Collections.unmodifiableList(ArrayList(wrappedVideoFormats))
    private val wrappedAudioFormats: List<FormatId> = Collections.unmodifiableList(ArrayList(wrappedAudioFormats))

    internal companion object {
        @JvmStatic
        @Throws(SabrProtocolException::class)
        fun decode(data: ByteArray): SabrSelectableFormats {
            val videoFormats = mutableListOf<FormatId>()
            val audioFormats = mutableListOf<FormatId>()
            val wrappedVideoFormats = mutableListOf<FormatId>()
            val wrappedAudioFormats = mutableListOf<FormatId>()
            var otherFieldCount = 0
            for (field in SabrProto.readFields(data)) {
                if (field.wireType != SabrProto.WIRE_LENGTH_DELIMITED) {
                    otherFieldCount++
                    continue
                }
                when (field.number) {
                    1 -> videoFormats.add(FormatId.decode(field.getBytes()))
                    2 -> audioFormats.add(FormatId.decode(field.getBytes()))
                    4 -> wrappedVideoFormats.add(decodeWrappedFormatId(field.getBytes()))
                    5 -> wrappedAudioFormats.add(decodeWrappedFormatId(field.getBytes()))
                    else -> otherFieldCount++
                }
            }
            return SabrSelectableFormats(videoFormats, audioFormats, wrappedVideoFormats, wrappedAudioFormats, otherFieldCount)
        }

        @Throws(SabrProtocolException::class)
        private fun decodeWrappedFormatId(data: ByteArray): FormatId {
            for (field in SabrProto.readFields(data)) {
                if (field.number == 1 && field.wireType == SabrProto.WIRE_LENGTH_DELIMITED) {
                    return FormatId.decode(field.getBytes())
                }
            }
            return FormatId.empty()
        }

        private fun summarizeFormats(formats: List<FormatId>): String {
            val builder = StringBuilder().append(formats.size).append('[')
            val sampleSize = minOf(6, formats.size)
            for (i in 0 until sampleSize) {
                if (i > 0) builder.append(',')
                builder.append(formats[i].summarize())
            }
            if (formats.size > sampleSize) builder.append(",...")
            return builder.append(']').toString()
        }
    }

    fun getVideoFormats(): List<FormatId> = videoFormats
    fun getAudioFormats(): List<FormatId> = audioFormats
    fun getWrappedVideoFormats(): List<FormatId> = wrappedVideoFormats
    fun getWrappedAudioFormats(): List<FormatId> = wrappedAudioFormats
    fun getOtherFieldCount(): Int = otherFieldCount

    fun summarize(): String =
        "video=" + summarizeFormats(videoFormats) +
            ", audio=" + summarizeFormats(audioFormats) +
            ", wrappedVideo=" + summarizeFormats(wrappedVideoFormats) +
            ", wrappedAudio=" + summarizeFormats(wrappedAudioFormats) +
            ", otherFields=" + otherFieldCount

    class FormatId private constructor(
        val itag: Int,
        val lastModified: Long,
        private val xtags: String?
    ) {
        companion object {
            @JvmStatic
            @Throws(SabrProtocolException::class)
            internal fun decode(data: ByteArray): FormatId {
                var itag = -1
                var lastModified: Long = -1
                var xtags: String? = null
                for (field in SabrProto.readFields(data)) {
                    when {
                        field.number == 1 && field.wireType == SabrProto.WIRE_VARINT ->
                            itag = field.varint.toInt()
                        field.number == 2 && field.wireType == SabrProto.WIRE_VARINT ->
                            lastModified = field.varint
                        field.number == 3 && field.wireType == SabrProto.WIRE_LENGTH_DELIMITED ->
                            xtags = field.getString()
                    }
                }
                return FormatId(itag, lastModified, xtags)
            }

            @JvmStatic
            internal fun empty(): FormatId = FormatId(-1, -1, null)
        }

        fun getXtags(): String? = xtags

        internal fun summarize(): String =
            "itag:$itag" + (if (lastModified >= 0) "+lm" else "") + (if (xtags != null) "+xtags" else "")
    }
}
