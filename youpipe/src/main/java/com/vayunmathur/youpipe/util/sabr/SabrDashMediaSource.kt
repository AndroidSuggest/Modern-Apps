package com.vayunmathur.youpipe.util.sabr

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.common.Timeline
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrStreamState
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Locale

class SabrDashMediaSource
@Throws(IOException::class)
constructor(
    context: Context,
    private val mediaItem: MediaItem,
    private val spec: SabrSourceSpec
) : CompositeMediaSource<Int>() {

    private val localization: Localization
    private val manifestState: YoutubeSabrStreamState
    private val sessionHandle: SabrSessionHandle
    private val durationUs: Long
    private val childSource: DashMediaSource
    private val playbackState = PlaybackState()

    init {
        try {
            localization = spec.localization
            manifestState = spec.newStreamState()
            if (!manifestState.hasSegmentIndex(spec.audioFormat) ||
                !manifestState.hasSegmentIndex(spec.videoFormat)
            ) {
                throw IOException(
                    "Refusing to publish guessed SABR DASH timeline for ${spec.videoId}"
                )
            }
            sessionHandle = SabrSessionHandle(context, spec)
            playbackState.setReaderOwner(this)
            val durationMs = spec.getDurationMs()
            durationUs = if (durationMs > 0) durationMs * 1000L else C.TIME_UNSET
            val sabrDataSourceFactory = DataSource.Factory {
                SabrSegmentDataSource(
                    sessionHandle, playbackState.getReaderOwner(), localization,
                    /* prependInit= */ false
                )
            }
            val manifest = buildManifest(spec, manifestState, durationMs)
            childSource = DashMediaSource.Factory(
                DefaultDashChunkSource.Factory(sabrDataSourceFactory),
                /* manifestDataSourceFactory= */ null
            ).createMediaSource(manifest, mediaItem)
            Log.d(
                TAG,
                "create source video=${spec.videoId}" +
                    " videoItag=${spec.videoFormat.itag}" +
                    " audioItag=${spec.audioFormat.itag}"
            )
        } catch (e: Throwable) {
            spec.discardPreparedSession()
            throw e
        }
    }

    override fun getMediaItem(): MediaItem = mediaItem

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        prepareChildSource(0, childSource)
    }

    override fun onChildSourceInfoRefreshed(
        id: Int?,
        mediaSource: MediaSource,
        timeline: Timeline
    ) {
        refreshSourceInfo(timeline)
    }

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long
    ): MediaPeriod {
        sessionHandle.onPeriodCreated(maxOf(0, startPositionUs / 1000L))
        try {
            val child = childSource.createPeriod(id, allocator, startPositionUs)
            val period = SabrDashMediaPeriod(child)
            playbackState.setReaderOwner(period)
            Log.d(TAG, "createPeriod video=${spec.videoId} startUs=$startPositionUs")
            return period
        } catch (e: RuntimeException) {
            sessionHandle.onPeriodReleased()
            throw e
        }
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        Log.d(TAG, "releasePeriod video=${spec.videoId}")
        val period = mediaPeriod as SabrDashMediaPeriod
        period.release()
        try {
            childSource.releasePeriod(period.child)
        } finally {
            sessionHandle.onPeriodReleased()
        }
    }

    override fun releaseSourceInternal() {
        Log.d(TAG, "release source video=${spec.videoId}")
        sessionHandle.close()
    }

    private inner class SabrDashMediaPeriod(val child: MediaPeriod) : MediaPeriod {
        private var callback: MediaPeriod.Callback? = null
        private var preparedPositionUs = C.TIME_UNSET
        private var initialPositionApplied = false

        override fun prepare(cb: MediaPeriod.Callback, positionUs: Long) {
            callback = cb
            preparedPositionUs = positionUs
            playbackState.setReaderOwner(this)
            child.prepare(
                object : MediaPeriod.Callback {
                    override fun onPrepared(mediaPeriod: MediaPeriod) {
                        cb.onPrepared(this@SabrDashMediaPeriod)
                    }

                    override fun onContinueLoadingRequested(source: MediaPeriod) {
                        cb.onContinueLoadingRequested(this@SabrDashMediaPeriod)
                    }
                },
                positionUs
            )
        }

        @Throws(IOException::class)
        override fun maybeThrowPrepareError() {
            child.maybeThrowPrepareError()
        }

        override fun getTrackGroups(): TrackGroupArray = child.trackGroups

        override fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> =
            child.getStreamKeys(trackSelections)

        override fun selectTracks(
            selections: Array<out ExoTrackSelection?>,
            mayRetainStreamFlags: BooleanArray,
            streams: Array<SampleStream?>,
            streamResetFlags: BooleanArray,
            positionUs: Long
        ): Long {
            playbackState.setReaderOwner(this)
            val hasActiveTracks = updateActiveTracks(selections)
            // Initial mid-starts near the next video boundary are cheaper if SABR starts on that
            // boundary; keep regular seeks on Media3's requested position/tolerance path.
            val normalizedPositionUs = if (initialPositionApplied || !hasActiveTracks) {
                normalizeSeekPositionUs(positionUs)
            } else {
                normalizeInitialStartPositionUs(positionUs)
            }
            applyInitialStartPosition(normalizedPositionUs, hasActiveTracks)
            return child.selectTracks(
                selections, mayRetainStreamFlags, streams, streamResetFlags, normalizedPositionUs
            )
        }

        private fun updateActiveTracks(selections: Array<out ExoTrackSelection?>): Boolean {
            var videoActive = false
            var audioActive = false
            for (selection in selections) {
                val format = selection?.selectedFormat ?: continue
                if (spec.videoFormat.itag.toString() == format.id) {
                    videoActive = true
                } else if (spec.audioFormat.itag.toString() == format.id) {
                    audioActive = true
                }
            }
            sessionHandle.setActiveTracks(this, videoActive, audioActive)
            Log.d(
                TAG,
                "activeTracks video=${spec.videoId} video=$videoActive audio=$audioActive"
            )
            return videoActive || audioActive
        }

        private fun applyInitialStartPosition(positionUs: Long, hasActiveTracks: Boolean) {
            if (initialPositionApplied || !hasActiveTracks) {
                return
            }
            initialPositionApplied = true
            val targetUs = maxOf(validPositionUs(preparedPositionUs), validPositionUs(positionUs))
            if (targetUs <= 0) {
                return
            }
            val normalizedTargetUs = normalizeSeekPositionUs(targetUs)
            Log.d(TAG, "initialStart video=${spec.videoId} positionUs=$normalizedTargetUs")
            sessionHandle.requestSeek(normalizedTargetUs / 1000L)
        }

        private fun validPositionUs(positionUs: Long): Long =
            if (positionUs == C.TIME_UNSET) 0 else maxOf(0, positionUs)

        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
            child.discardBuffer(positionUs, toKeyframe)
        }

        override fun readDiscontinuity(): Long = child.readDiscontinuity()

        override fun seekToUs(positionUs: Long): Long {
            playbackState.setReaderOwner(this)
            sessionHandle.advanceReaderGeneration(this)
            val normalizedPositionUs = normalizeSeekPositionUs(positionUs)
            sessionHandle.requestSeek(normalizedPositionUs / 1000L)
            return child.seekToUs(normalizedPositionUs)
        }

        override fun getAdjustedSeekPositionUs(
            positionUs: Long,
            seekParameters: SeekParameters
        ): Long {
            val normalizedPositionUs = normalizeSeekPositionUs(positionUs)
            return child.getAdjustedSeekPositionUs(
                adjustSeekForwardToNearSegmentBoundary(normalizedPositionUs, seekParameters),
                seekParameters
            )
        }

        private fun normalizeSeekPositionUs(positionUs: Long): Long {
            val normalizedPositionUs = maxOf(0, positionUs)
            if (durationUs == C.TIME_UNSET || durationUs <= 0 ||
                normalizedPositionUs < durationUs
            ) {
                return normalizedPositionUs
            }
            return maxOf(0, durationUs - END_SEEK_BACKOFF_US)
        }

        private fun normalizeInitialStartPositionUs(positionUs: Long): Long =
            snapForwardToNearSegmentBoundary(
                normalizeSeekPositionUs(positionUs), START_POSITION_FORWARD_SNAP_US
            )

        private fun adjustSeekForwardToNearSegmentBoundary(
            positionUs: Long,
            seekParameters: SeekParameters
        ): Long {
            if (seekParameters.toleranceAfterUs <= 0) {
                return positionUs
            }
            return snapForwardToNearSegmentBoundary(
                positionUs,
                minOf(SEEK_FORWARD_SYNC_TOLERANCE_US, seekParameters.toleranceAfterUs)
            )
        }

        private fun snapForwardToNearSegmentBoundary(
            positionUs: Long,
            toleranceUs: Long
        ): Long {
            if (toleranceUs <= 0) {
                return positionUs
            }
            val positionMs = maxOf(0, positionUs / 1000L)
            val currentSequence =
                manifestState.getSegmentNumberAtOrAfterTimeMs(spec.videoFormat, positionMs)
            val nextStartMs =
                manifestState.getSegmentStartMs(spec.videoFormat, currentSequence + 1)
            val nextStartUs = nextStartMs * 1000L
            if (nextStartUs > positionUs && nextStartUs - positionUs <= toleranceUs) {
                return nextStartUs
            }
            return positionUs
        }

        override fun getBufferedPositionUs(): Long = child.bufferedPositionUs

        override fun getNextLoadPositionUs(): Long = child.nextLoadPositionUs

        override fun continueLoading(loadingInfo: LoadingInfo): Boolean =
            child.continueLoading(loadingInfo)

        override fun isLoading(): Boolean = child.isLoading

        override fun reevaluateBuffer(positionUs: Long) {
            child.reevaluateBuffer(positionUs)
        }

        fun release() {
            sessionHandle.releaseTracks(this)
            callback = null
        }
    }

    private class PlaybackState {
        private var readerOwner: Any = Any()

        @Synchronized
        fun setReaderOwner(readerOwner: Any) {
            this.readerOwner = readerOwner
        }

        @Synchronized
        fun getReaderOwner(): Any = readerOwner
    }

    private companion object {
        private const val TAG = "SabrDashMediaSource"
        private const val SEEK_FORWARD_SYNC_TOLERANCE_US = 2_000_000L
        private const val START_POSITION_FORWARD_SNAP_US = 500_000L
        private const val END_SEEK_BACKOFF_US = 1_000L

        @Throws(IOException::class)
        private fun buildManifest(
            spec: SabrSourceSpec,
            state: YoutubeSabrStreamState,
            durationMs: Long
        ): DashManifest {
            val mpd = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" " +
                "profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" " +
                "minBufferTime=\"PT1.5S\" mediaPresentationDuration=\"" +
                formatDuration(durationMs) + "\">" +
                "<Period id=\"0\" start=\"PT0S\">" +
                adaptationSet(state, spec.videoFormat, C.TRACK_TYPE_VIDEO) +
                adaptationSet(state, spec.audioFormat, C.TRACK_TYPE_AUDIO) +
                "</Period></MPD>"
            try {
                return DashManifestParser().parse(
                    Uri.parse("sabr://${spec.videoId}"),
                    ByteArrayInputStream(mpd.toByteArray(Charsets.UTF_8))
                )
            } catch (e: IOException) {
                throw IOException("Error when parsing generated SABR DASH manifest", e)
            }
        }

        private fun adaptationSet(
            state: YoutubeSabrStreamState,
            format: YoutubeSabrFormat,
            trackType: Int
        ): String {
            val mime = containerMimeType(format)
            val codecs = codecs(format)
            val contentType = if (trackType == C.TRACK_TYPE_AUDIO) "audio" else "video"
            val builder = StringBuilder()
                .append("<AdaptationSet id=\"").append(format.itag)
                .append("\" contentType=\"").append(contentType)
                .append("\" mimeType=\"").append(xml(mime))
                .append("\" segmentAlignment=\"true\" startWithSAP=\"1\">")
                .append("<Representation id=\"").append(format.itag)
                .append("\" bandwidth=\"").append(maxOf(1, format.bitrate)).append("\"")
            if (!codecs.isNullOrEmpty()) {
                builder.append(" codecs=\"").append(xml(codecs)).append("\"")
            }
            if (trackType == C.TRACK_TYPE_VIDEO) {
                builder.append(" width=\"").append(maxOf(1, format.width))
                    .append("\" height=\"").append(maxOf(1, format.height)).append("\"")
            } else {
                builder.append(" audioSamplingRate=\"48000\"")
            }
            builder.append(">")
                .append("<BaseURL>sabrseg://").append(format.itag).append("/</BaseURL>")
                .append(segmentTemplate(state, format))
                .append("</Representation></AdaptationSet>")
            return builder.toString()
        }

        private fun segmentTemplate(
            state: YoutubeSabrStreamState,
            format: YoutubeSabrFormat
        ): String {
            val endSegment = state.getEndSegment(format)
            check(endSegment > 0 && endSegment <= 10_000) {
                "Invalid exact SABR segment count: itag=${format.itag}, count=$endSegment"
            }
            val builder = StringBuilder()
                .append("<SegmentTemplate timescale=\"1000\" startNumber=\"1\" ")
                .append("initialization=\"init\" media=\"\$Number\$\">")
                .append("<SegmentTimeline>")
            for (sequence in 1..endSegment.toInt()) {
                val startMs = state.getSegmentStartMs(format, sequence)
                val endMs = state.getSegmentEndMs(format, sequence)
                val durationMs = maxOf(1, endMs - startMs)
                builder.append("<S t=\"").append(maxOf(0, startMs))
                    .append("\" d=\"").append(durationMs).append("\"/>")
            }
            return builder.append("</SegmentTimeline></SegmentTemplate>").toString()
        }

        private fun formatDuration(durationMs: Long): String {
            val safeDurationMs = maxOf(1, durationMs)
            return "PT" + (safeDurationMs / 1000) + "." +
                String.format(Locale.US, "%03d", safeDurationMs % 1000) + "S"
        }

        private fun containerMimeType(format: YoutubeSabrFormat): String {
            val mime = format.mimeType
            if (mime.isNullOrEmpty()) {
                return if (format.isAudio) MimeTypes.AUDIO_MP4 else MimeTypes.VIDEO_MP4
            }
            val semicolon = mime.indexOf(';')
            return if (semicolon >= 0) mime.substring(0, semicolon).trim() else mime.trim()
        }

        private fun codecs(format: YoutubeSabrFormat): String? {
            val mime = format.mimeType ?: return null
            val start = mime.indexOf("codecs=")
            if (start < 0) {
                return null
            }
            return mime.substring(start + "codecs=".length).replace("\"", "").trim()
        }

        private fun xml(value: String): String =
            value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
    }
}
