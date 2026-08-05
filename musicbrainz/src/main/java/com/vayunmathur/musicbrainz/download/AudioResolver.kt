package com.vayunmathur.musicbrainz.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import kotlin.math.abs

/** A resolved, directly fetchable audio stream. */
data class ResolvedAudio(
    val url: String,
    val suffix: String,
    val mimeType: String,
    val bitrate: Int,
    val sourceTitle: String,
)

/**
 * Finds a downloadable audio stream for a MusicBrainz track.
 *
 * MusicBrainz catalogues music but hosts none of it, so the audio comes from YouTube via
 * the extractor YouPipe already vendors.
 *
 * Only progressive streams are used. YouTube also serves audio over SABR, which needs a
 * stateful session and the PO-token machinery that lives in the YouPipe app; a track that
 * is SABR-only is reported as unavailable rather than dragging that whole stack in here.
 */
object AudioResolver {

    private const val SEARCH_RESULTS_CONSIDERED = 8
    private const val DURATION_TOLERANCE_SECONDS = 12L

    /**
     * @param durationMs the MusicBrainz recording length, used to pick between candidates.
     *   Search alone happily returns hour-long compilations and sped-up edits of the same
     *   title, and duration is the one signal that separates them from the actual track.
     */
    suspend fun resolve(
        artist: String,
        title: String,
        album: String?,
        durationMs: Int?,
    ): ResolvedAudio? = withContext(Dispatchers.IO) {
        val candidates = search(buildQuery(artist, title, album))
        if (candidates.isEmpty()) return@withContext null
        val ordered = rank(candidates, durationMs)
        for (candidate in ordered) {
            val audio = audioFor(candidate.url) ?: continue
            return@withContext audio.copy(sourceTitle = candidate.name)
        }
        null
    }

    private fun buildQuery(artist: String, title: String, album: String?): String = buildString {
        append(artist)
        append(" - ")
        append(title)
        if (!album.isNullOrBlank() && !title.contains(album, ignoreCase = true)) {
            append(' ')
            append(album)
        }
    }

    private fun search(query: String): List<StreamInfoItem> = try {
        val extractor = ServiceList.YouTube.getSearchExtractor(query)
        extractor.fetchPage()
        extractor.getInitialPage()
            .getItems()
            .filterIsInstance<StreamInfoItem>()
            .take(SEARCH_RESULTS_CONSIDERED)
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Orders candidates by how close their runtime is to the catalogued one.
     *
     * Anything within a few seconds is treated as equally good and left in YouTube's own
     * relevance order, which is a better tie-breaker than an arbitrarily closer duration.
     */
    private fun rank(candidates: List<StreamInfoItem>, durationMs: Int?): List<StreamInfoItem> {
        if (durationMs == null || durationMs <= 0) return candidates
        val target = durationMs / 1000L
        return candidates
            .withIndex()
            .sortedWith(
                compareBy(
                    { (_, item) ->
                        val delta = abs(item.getDuration() - target)
                        if (delta <= DURATION_TOLERANCE_SECONDS) 0L else delta
                    },
                    { (index, _) -> index },
                ),
            )
            .map { it.value }
    }

    private fun audioFor(videoUrl: String): ResolvedAudio? = try {
        val extractor = ServiceList.YouTube.getStreamExtractor(videoUrl)
        extractor.fetchPage()
        val progressive = extractor.getAudioStreams().filter {
            it.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP &&
                it.isUrl() &&
                it.getContent().isNotBlank()
        }
        // M4A first: it is an MP4 container, which is the one format the tag writer can
        // annotate, so preferring it is what lets downloads carry their MusicBrainz IDs.
        val best = progressive.filter { it.getFormat() == MediaFormat.M4A }
            .maxByOrNull { it.effectiveBitrate() }
            ?: progressive.maxByOrNull { it.effectiveBitrate() }
        best?.let {
            val format = it.getFormat()
            ResolvedAudio(
                url = it.getContent(),
                suffix = format?.getSuffix() ?: "m4a",
                mimeType = format?.mimeType ?: "audio/mp4",
                bitrate = it.effectiveBitrate(),
                sourceTitle = "",
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun AudioStream.effectiveBitrate(): Int =
        getAverageBitrate().takeIf { it > 0 } ?: getBitrate()
}
