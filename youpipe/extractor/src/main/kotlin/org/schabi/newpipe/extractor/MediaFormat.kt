package org.schabi.newpipe.extractor

import java.util.Arrays
import java.util.function.Function
import java.util.stream.Collectors
import javax.annotation.Nonnull
import javax.annotation.Nullable

enum class MediaFormat(
    @JvmField val id: Int,
    @JvmField @field:Nonnull val name: String,
    @JvmField @field:Nonnull val suffix: String,
    @JvmField @field:Nonnull val mimeType: String
) {
    MPEG_4(0x0, "MPEG-4", "mp4", "video/mp4"),
    v3GPP(0x10, "3GPP", "3gp", "video/3gpp"),
    WEBM(0x20, "WebM", "webm", "video/webm"),
    M4A(0x100, "m4a", "m4a", "audio/mp4"),
    WEBMA(0x200, "WebM", "webm", "audio/webm"),
    MP3(0x300, "MP3", "mp3", "audio/mpeg"),
    MP2(0x310, "MP2", "mp2", "audio/mpeg"),
    OPUS(0x400, "opus", "opus", "audio/opus"),
    OGG(0x500, "ogg", "ogg", "audio/ogg"),
    WEBMA_OPUS(0x200, "WebM Opus", "webm", "audio/webm"),
    AIFF(0x600, "AIFF", "aiff", "audio/aiff"),
    AIF(0x600, "AIFF", "aif", "audio/aiff"),
    WAV(0x700, "WAV", "wav", "audio/wav"),
    FLAC(0x800, "FLAC", "flac", "audio/flac"),
    ALAC(0x900, "ALAC", "alac", "audio/alac"),
    VTT(0x1000, "WebVTT", "vtt", "text/vtt"),
    TTML(0x2000, "Timed Text Markup Language", "ttml", "application/ttml+xml"),
    TRANSCRIPT1(0x3000, "TranScript v1", "srv1", "text/xml"),
    TRANSCRIPT2(0x4000, "TranScript v2", "srv2", "text/xml"),
    TRANSCRIPT3(0x5000, "TranScript v3", "srv3", "text/xml"),
    SRT(0x6000, "SubRip file format", "srt", "text/srt");

    @Nonnull
    fun getName(): String = name

    @Nonnull
    fun getSuffix(): String = suffix

    @Nonnull
    fun getMimeType(): String = mimeType

    companion object {
        private fun <T> getById(id: Int, field: Function<MediaFormat, T>, orElse: T): T =
            Arrays.stream(values())
                .filter { it.id == id }
                .map(field)
                .findFirst()
                .orElse(orElse)

        @JvmStatic
        @Nonnull
        fun getNameById(id: Int): String = getById(id, Function { it.name }, "")

        @JvmStatic
        @Nonnull
        fun getSuffixById(id: Int): String = getById(id, Function { it.suffix }, "")

        @JvmStatic
        @Nullable
        fun getMimeById(id: Int): String? = getById(id, Function { it.mimeType }, null)

        @JvmStatic
        @Nullable
        fun getFromMimeType(mimeType: String): MediaFormat? =
            Arrays.stream(values())
                .filter { it.mimeType == mimeType }
                .findFirst()
                .orElse(null)

        @JvmStatic
        @Nonnull
        fun getAllFromMimeType(mimeType: String): List<MediaFormat> =
            Arrays.stream(values())
                .filter { it.mimeType == mimeType }
                .collect(Collectors.toList())

        @JvmStatic
        @Nullable
        fun getFormatById(id: Int): MediaFormat? =
            getById(id, Function { it }, null)

        @JvmStatic
        @Nullable
        fun getFromSuffix(suffix: String): MediaFormat? =
            Arrays.stream(values())
                .filter { it.suffix == suffix }
                .findFirst()
                .orElse(null)
    }
}
