package com.vayunmathur.youpipe.util

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.vayunmathur.youpipe.data.CachedRelatedVideo
import com.vayunmathur.youpipe.data.DownloadedVideo
import com.vayunmathur.youpipe.ui.AudioStream
import com.vayunmathur.youpipe.ui.Comment
import com.vayunmathur.youpipe.ui.SubtitleTrack
import com.vayunmathur.youpipe.ui.VideoChapter
import com.vayunmathur.youpipe.ui.VideoData
import com.vayunmathur.youpipe.ui.VideoStream
import com.vayunmathur.youpipe.ui.fromHTML
import com.vayunmathur.youpipe.ui.getAudioCodecName
import com.vayunmathur.youpipe.ui.getVideoCodecName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import kotlin.time.Clock
import kotlin.time.toKotlinInstant

private const val TAG = "YouPipeViewModel"

fun YouPipeViewModel.loadVideo(videoID: Long, downloadedVideo: DownloadedVideo?) {
    videoJob?.cancel()
    sponsorJob?.cancel()
    _videoState.value = VideoState()

    // Sponsor segments load in parallel.
    sponsorJob = viewModelScope.launch(Dispatchers.IO) {
        val segs = getSponsorSegments(videoID)
        _videoState.update { it.copy(sponsorSegments = segs) }
    }

    fetchDeArrowForVideos(listOf(videoID))

    videoJob = viewModelScope.launch {
        val url = videoIDtoURL(videoID)
        val youtubeService = ServiceList.YouTube
        try {
            withContext(Dispatchers.IO) {
                val ex = youtubeService.getStreamExtractor(url)
                ex.fetchPage()

                val videoStreams: List<VideoStream>
                val audioStreams: List<AudioStream>
                val segments: List<VideoChapter>
                val subtitles: List<SubtitleTrack>

                if (downloadedVideo == null) {
                    segments = ex.getStreamSegments().map {
                        VideoChapter(it.getStartTimeSeconds() * 1000, it.getTitle(), it.getPreviewUrl())
                    }
                    subtitles = ex.getSubtitles(org.schabi.newpipe.extractor.MediaFormat.VTT).map { it.toSubtitleTrack() }
                    val rawVideoOnly = ex.getVideoOnlyStreams()
                    val rawAudio = ex.getAudioStreams()

                    val sabrMethod =
                        org.schabi.newpipe.extractor.stream.DeliveryMethod.SABR
                    val progVideoOnly = rawVideoOnly.filter { it.getDeliveryMethod() != sabrMethod }
                    val progAudio = rawAudio.filter { it.getDeliveryMethod() != sabrMethod }
                    val sabrVideoOnly = rawVideoOnly.filter { it.getDeliveryMethod() == sabrMethod }
                    val sabrAudio = rawAudio.filter { it.getDeliveryMethod() == sabrMethod }
                    val videoId = ex.getId()

                    android.util.Log.d(
                        "YouPipeSabr",
                        "video $videoId streams prog(v=${progVideoOnly.size}," +
                            "a=${progAudio.size}) sabr(v=${sabrVideoOnly.size}," +
                            "a=${sabrAudio.size})"
                    )

                    // Register SABR session metadata for the playback service if present.
                    // NOTE: do NOT prewarm the content PoToken here — doing so regressed SABR
                    // bootstrap (403 on init range / "no exact indexes"). The token is minted
                    // at play time by createSourceSpec, which is the known-working ordering.
                    if (sabrVideoOnly.isNotEmpty()) {
                        sabrVideoOnly.firstOrNull {
                            it.getDeliveryMethodInfo() is
                                org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrInfo
                        }?.let {
                            val sabrInfo = it.getDeliveryMethodInfo() as
                                org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrInfo
                            com.vayunmathur.youpipe.util.sabr.SabrNgSessionStore.putExtractorInfo(
                                videoId, sabrInfo
                            )
                        }
                    }

                    if (progVideoOnly.isNotEmpty() || sabrVideoOnly.isNotEmpty()) {
                        // Combine progressive + SABR video-only, then keep exactly one stream
                        // per resolution: highest fps wins, then best codec (av1 > vp9 > avc).
                        // Do NOT filter >1080p.
                        videoStreams = (
                            progVideoOnly.map { it.toDomain() } +
                                sabrVideoOnly.map { it.toSabrDomain(videoId) }
                            )
                            .groupBy { it.height }
                            .map { (_, streamsAtRes) -> streamsAtRes.maxWith(videoStreamPreference) }
                            .sortedByDescending { it.height }

                        // Audio: opus only (F-Droid friendly, higher quality). Filter before sort.
                        val rawAudioCandidates = if (progAudio.isNotEmpty()) {
                            progAudio.map { it.toDomain() }
                        } else {
                            sabrAudio.map { it.toSabrDomain(videoId) }
                        }
                        audioStreams = rawAudioCandidates
                            .filter { it.codec == "opus" }
                            .let { opusList ->
                                // Fallback to all if no opus (rare, but avoid empty track list)
                                if (opusList.isEmpty()) rawAudioCandidates else opusList
                            }
                            .sortedWith(
                                compareByDescending<AudioStream> { it.bitrate }
                            )
                    } else {
                        videoStreams = ex.getVideoStreams().map { it.toDomain() }
                            .groupBy { it.height }
                            .map { (_, streamsAtRes) -> streamsAtRes.maxWith(videoStreamPreference) }
                            .sortedByDescending { it.height }
                        audioStreams = emptyList()
                    }
                } else {
                    val (vs, as_) = downloadedStreams(downloadedVideo)
                    videoStreams = vs
                    audioStreams = as_
                    segments = emptyList()
                    subtitles = emptyList()
                }

                val data = VideoData(
                    ex.getName().decodeHtml(),
                    ex.getViewCount(),
                    ex.getLength(),
                    ex.getUploadDate()!!.instant.toKotlinInstant(),
                    ex.getThumbnails().first().url,
                    ex.getUploaderName().decodeHtml(),
                    channelURLtoID(ex.getUploaderUrl()),
                    ex.getUploaderAvatars().first().url,
                    ex.getDescription().getContent().fromHTML()
                )
                // Related videos are optional; upstream fetches them via
                // ExtractorHelper.getRelatedItemsOrLogError precisely so a failure here
                // cannot take down playback. Calling getRelatedItems() bare meant one bad
                // renderer surfaced as "Video load error" on a video that had extracted fine.
                val related = try {
                    ex.getRelatedItems()?.getItems()?.filterIsInstance<StreamInfoItem>()
                        ?.mapNotNull { it.toVideoInfo() } ?: emptyList()
                } catch (e: Exception) {
                    Log.w("YouPipeViewModel", "Could not get related videos", e)
                    emptyList()
                }

                _videoState.update {
                    it.copy(
                        data = data,
                        videoStreams = videoStreams,
                        audioStreams = audioStreams,
                        segments = segments,
                        subtitles = subtitles,
                        relatedVideos = related,
                    )
                }

                fetchDeArrowForVideos(related.map { it.videoID })

                if (related.isNotEmpty()) {
                    repository.upsertCachedRelatedVideos(related.map {
                        CachedRelatedVideo(
                            sourceVideoID = videoID,
                            videoItem = it,
                            cachedAt = Clock.System.now()
                        )
                    })
                }
            }
            withContext(Dispatchers.IO) {
                val cex = youtubeService.getCommentsExtractor(url)
                    ?: return@withContext
                cex.fetchPage()
                val comments = cex.getInitialPage().getItems().map { c ->
                    val content = if (c.getCommentText().getType() == Description.HTML) {
                        c.getCommentText().getContent().fromHTML()
                    } else {
                        c.getCommentText().getContent().decodeHtml()
                    }
                    Comment(
                        content,
                        c.getUploaderName().orEmpty().decodeHtml(),
                        c.getLikeCount(),
                        0
                    )
                }
                _videoState.update { it.copy(comments = comments) }
            }
        } catch (e: Exception) {
            if (downloadedVideo != null) {
                val video = downloadedVideo
                val data = VideoData(
                    video.videoItem.name,
                    video.videoItem.views,
                    video.videoItem.duration,
                    video.videoItem.uploadDate,
                    video.videoItem.thumbnailURL,
                    video.videoItem.author,
                    "",
                    "",
                    ""
                )
                val (vs, as_) = downloadedStreams(video)
                _videoState.update { it.copy(data = data, videoStreams = vs, audioStreams = as_) }
            } else {
                _videoState.update { it.copy(error = true) }
                Log.e(TAG, "Video load error", e)
            }
        }
    }
}

/**
 * When a download completes while the user is on the video page, swap the
 * playable streams over to the on-disk copy (mirrors the original
 * `LaunchedEffect(downloadedVideo)` in VideoPage).
 */
fun YouPipeViewModel.applyDownloadedStreams(downloadedVideo: DownloadedVideo) {
    val (vs, as_) = downloadedStreams(downloadedVideo)
    _videoState.update { it.copy(videoStreams = vs, audioStreams = as_, segments = emptyList()) }
}

fun YouPipeViewModel.clearVideoError() {
    _videoState.update { it.copy(error = false) }
}

internal fun downloadedStreams(dv: DownloadedVideo): Pair<List<VideoStream>, List<AudioStream>> {
    val video = listOf(VideoStream(dv.filePath, 1920, 1080, 0, 30, "Downloaded", "avc", 0L))
    // Keep as opus for consistency with opus-only filter in UI
    val audio = if (dv.audioPath != null) listOf(AudioStream(dv.audioPath, 0, "Default", "opus", 0L)) else emptyList()
    return video to audio
}

internal fun org.schabi.newpipe.extractor.stream.VideoStream.toDomain() = VideoStream(
    getContent(), getWidth(), getHeight(), getBitrate(), getFps(), "${getHeight()}p",
    getVideoCodecName(getCodec() ?: ""), getItagItem()?.contentLength ?: 0L
)

internal fun org.schabi.newpipe.extractor.stream.VideoStream.toSabrDomain(videoId: String): VideoStream {
    val itag = getItagItem()?.id ?: 0
    val url = if (getDeliveryMethod() == org.schabi.newpipe.extractor.stream.DeliveryMethod.SABR) {
        "sabr://$videoId?v=$itag"
    } else {
        getContent()
    }
    return VideoStream(
        url, getWidth(), getHeight(), getBitrate(), getFps(), "${getHeight()}p",
        getVideoCodecName(getCodec() ?: ""), getItagItem()?.contentLength ?: 0L
    )
}

internal fun org.schabi.newpipe.extractor.stream.AudioStream.toDomain() = AudioStream(
    getContent(), getBitrate(), getAudioLocale()?.language ?: "Default",
    getAudioCodecName(getCodec() ?: ""), getItagItem()?.contentLength ?: 0L,
    audioTrackId = getAudioTrackId(),
    displayName = getAudioTrackName() ?: getAudioLocale()?.displayLanguage,
)

internal fun org.schabi.newpipe.extractor.stream.AudioStream.toSabrDomain(videoId: String): AudioStream {
    val itag = getItagItem()?.id ?: 0
    val url = if (getDeliveryMethod() == org.schabi.newpipe.extractor.stream.DeliveryMethod.SABR) {
        "sabr://$videoId?a=$itag"
    } else {
        getContent()
    }
    return AudioStream(
        url, getBitrate(),
        getAudioLocale()?.language ?: getAudioLocale()?.toLanguageTag() ?: "Default",
        getAudioCodecName(getCodec() ?: ""), getItagItem()?.contentLength ?: 0L,
        audioTrackId = getAudioTrackId(),
        displayName = getAudioTrackName() ?: getAudioLocale()?.displayLanguage,
    )
}

internal fun SubtitlesStream.toSubtitleTrack() = SubtitleTrack(
    url = getContent(),
    languageTag = getLanguageTag(),
    displayName = getDisplayLanguageName() + if (isAutoGenerated()) " (auto)" else "",
    autoGenerated = isAutoGenerated(),
    mimeType = getFormat()?.mimeType ?: "application/ttml+xml",
)

internal fun codecPriority(codec: String): Int = when (codec) {
    "av1" -> 3; "vp9" -> 2; "avc" -> 1; else -> 0
}

internal val videoStreamPreference: Comparator<VideoStream> =
    compareBy<VideoStream> { it.fps }.thenBy { codecPriority(it.codec) }
