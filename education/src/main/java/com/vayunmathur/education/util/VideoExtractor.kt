package com.vayunmathur.education.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList

/**
 * Streams resolved for playback. If [audioUrl] is null, [videoUrl] is a muxed
 * (progressive) stream; otherwise the two are combined at playback time.
 */
data class ResolvedStreams(
    val title: String,
    val videoUrl: String,
    val audioUrl: String?,
)

/**
 * Resolves a YouTube video id to playable stream URLs via the NewPipe
 * extractor (FOSS, no Google APIs) — same mechanism :youpipe uses.
 */
object VideoExtractor {
    suspend fun resolve(youtubeId: String): ResolvedStreams = withContext(Dispatchers.IO) {
        val ex = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$youtubeId")
        ex.fetchPage()

        // The extractor is Kotlin now, so these are plain functions — there is no synthetic
        // property sugar (that only applies to Java getters) and no nullable elements.
        // Prefer a single muxed progressive stream (simplest to play).
        val muxed = ex.getVideoStreams().filter { it.getContent().isNotBlank() }
        if (muxed.isNotEmpty()) {
            val best = muxed.maxByOrNull { it.getHeight() }!!
            return@withContext ResolvedStreams(ex.getName(), best.getContent(), null)
        }

        // Otherwise combine the best video-only + audio streams.
        val video = ex.getVideoOnlyStreams().filter { it.getContent().isNotBlank() }
            .maxByOrNull { it.getHeight() } ?: error("No playable video stream")
        val audio = ex.getAudioStreams().filter { it.getContent().isNotBlank() }
            .maxByOrNull { it.getBitrate() }
        ResolvedStreams(ex.getName(), video.getContent(), audio?.getContent())
    }
}
