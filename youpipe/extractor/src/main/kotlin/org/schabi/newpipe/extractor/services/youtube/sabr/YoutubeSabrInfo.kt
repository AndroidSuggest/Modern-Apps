package org.schabi.newpipe.extractor.services.youtube.sabr

import java.io.Serializable
import java.util.Collections
import javax.annotation.Nonnull
import javax.annotation.Nullable

class YoutubeSabrInfo internal constructor(
    @field:Nonnull
    private val profile: YoutubeSabrClientProfile,
    @field:Nonnull
    private val videoId: String,
    @field:Nonnull
    private val cpn: String,
    @field:Nonnull
    private val clientVersion: String,
    @field:Nullable
    private val visitorData: String?,
    @field:Nullable
    private val serverAbrStreamingUrl: String?,
    @field:Nullable
    private val videoPlaybackUstreamerConfig: String?,
    @field:Nonnull
    private val formats: List<YoutubeSabrFormat>
) : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    @Nonnull
    fun getProfile(): YoutubeSabrClientProfile = profile

    @Nonnull
    fun getVideoId(): String = videoId

    @Nonnull
    fun getCpn(): String = cpn

    @Nonnull
    fun getClientVersion(): String = clientVersion

    @Nullable
    fun getVisitorData(): String? = visitorData

    @Nullable
    fun getServerAbrStreamingUrl(): String? = serverAbrStreamingUrl

    @Nullable
    fun getVideoPlaybackUstreamerConfig(): String? = videoPlaybackUstreamerConfig

    @Nonnull
    fun getFormats(): List<YoutubeSabrFormat> = Collections.unmodifiableList(formats)

    @Nullable
    fun findBestAudioFormat(): YoutubeSabrFormat? {
        var best: YoutubeSabrFormat? = null
        for (format in formats) {
            if (!format.isAudio) {
                continue
            }
            if (best == null) {
                best = format
                continue
            }
            // Prefer the original-language track over auto-dubs, then the highest bitrate. Keeps the
            // current behaviour (highest bitrate) when there is no original-marked track.
            val preferForTrack = format.isOriginalAudio && !best.isOriginalAudio
            val preferForBitrate = format.isOriginalAudio == best.isOriginalAudio
                    && format.bitrate > best.bitrate
            if (preferForTrack || preferForBitrate) {
                best = format
            }
        }
        return best
    }

    @Nullable
    fun findLowestVideoFormat(): YoutubeSabrFormat? {
        var lowest: YoutubeSabrFormat? = null
        for (format in formats) {
            if (format.isVideo && (lowest == null || format.height < lowest.height)) {
                lowest = format
            }
        }
        return lowest
    }

    @Nullable
    fun findFormatByItag(itag: Int): YoutubeSabrFormat? {
        for (format in formats) {
            if (format.itag == itag) {
                return format
            }
        }
        return null
    }
}
