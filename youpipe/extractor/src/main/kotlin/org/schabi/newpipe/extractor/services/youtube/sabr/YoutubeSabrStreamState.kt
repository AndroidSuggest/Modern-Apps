package org.schabi.newpipe.extractor.services.youtube.sabr

import java.util.LinkedHashMap
import java.util.LinkedHashSet

class YoutubeSabrStreamState(
    audioFormat: YoutubeSabrFormat,
    videoFormat: YoutubeSabrFormat
) {

    companion object {
        const val TRACK_MODE_VIDEO_AND_AUDIO: Int = YoutubeSabrRequestBuilder.ENABLED_TRACK_TYPES_VIDEO_AND_AUDIO
        const val TRACK_MODE_AUDIO_ONLY: Int = YoutubeSabrRequestBuilder.ENABLED_TRACK_TYPES_AUDIO_ONLY
        const val TRACK_MODE_VIDEO_ONLY: Int = YoutubeSabrRequestBuilder.ENABLED_TRACK_TYPES_VIDEO_ONLY

        // how close to the head counts as "at the live edge" (segments of slack before we wait)
        private const val LIVE_EDGE_MARGIN_SEGMENTS: Long = 2
    }

    private val audio: FormatProgress = FormatProgress(audioFormat)
    private val video: FormatProgress = FormatProgress(videoFormat)

    private val sabrContexts: MutableMap<Int, SabrContextUpdate> = LinkedHashMap()
    private val activeSabrContextTypes: MutableSet<Int> = LinkedHashSet()

    @Volatile
    private var playbackCookie: ByteArray? = null

    @Volatile
    private var poToken: ByteArray? = null

    @Volatile
    private var nextRequestPolicy: SabrNextRequestPolicy? = null

    private var playerTimeMsOverride: Long = -1

    private var audioFullyBuffered: Boolean = false
    private var videoFullyBuffered: Boolean = false
    private var audioLastOnlyRange: Boolean = false
    private var videoLastOnlyRange: Boolean = false
    private var lastOnlyRangesUseObservedTiming: Boolean = false

    @Volatile
    internal var enabledTrackTypesBitfield: Int = YoutubeSabrRequestBuilder.ENABLED_TRACK_TYPES_VIDEO_AND_AUDIO

    @Volatile
    private var selectAudioFormat: Boolean = true

    @Volatile
    private var selectVideoFormat: Boolean = true

    private var writeTopLevelPlayerTimeMs: Boolean = true
    private var clientViewportWidthInternal: Int = -1
    private var clientViewportHeightInternal: Int = -1
    private var bandwidthEstimateInternal: Long = -1
    private var playbackRateInternal: Float = 1.0f

    // Experimental knobs used by local SABR probes. Defaults preserve the normal request shape.
    private var bufferedRangeStartSegmentIndexOffset: Int = 0
    private var bufferedRangeEndSegmentIndexOffset: Int = 0

    private var clientAbrVisibilityInternal: Int? = 1
    private var writeLastManualSelectedResolution: Boolean = false
    private var writeAllPreferredFormats: Boolean = false
    private var writeOfficialWebPreferredFormats: Boolean = false
    private var selectVideoFormatBeforeAudio: Boolean = false
    private var writeBufferedRangeTimeRange: Boolean = true
    private var stickyResolutionOverrideInternal: Int? = null
    private var officialTimeSinceLastSeekOverrideInternal: Long? = null
    private var officialElapsedWallTimeOverrideInternal: Long? = null
    private var officialTimeSinceLastActionOverrideInternal: Long? = null
    private var officialField57OverrideInternal: Long? = null
    private var officialField68OverrideInternal: Long? = null
    private var sabrReportRequestCancellationInfoOverrideInternal: Int? = null
    private var writeOfficialWebClientAbrFields: Boolean = false
    private var bufferedRangesOverride: List<SabrBufferedRange>? = null

    // live: foundation only. we record what the server tells us about the live edge (via LIVE_METADATA)
    private var live: Boolean = false
    private var postLiveDvr: Boolean = false
    private var liveHeadSequenceNumber: Long = -1
    private var liveHeadTimeMs: Long = -1

    // ------------------------------------------------------------------
    // internal properties exposed to request builder (Kotlin property access)
    // ------------------------------------------------------------------

    internal val bufferedRanges: List<SabrBufferedRange>
        get() = getBufferedRanges()

    internal val requestPlayerTimeMs: Long
        get() = getRequestPlayerTimeMsInternal()

    internal val rawPlaybackCookie: ByteArray?
        get() = playbackCookie

    internal val rawPoToken: ByteArray?
        get() = poToken

    internal val activeSabrContexts: Collection<SabrContextUpdate>
        get() = getActiveSabrContexts()

    internal val unsentSabrContextTypes: Collection<Int>
        get() = getUnsentSabrContextTypes()

    internal val clientViewportWidth: Int
        get() = getClientViewportWidth()

    internal val clientViewportHeight: Int
        get() = getClientViewportHeight()

    internal val bandwidthEstimate: Long
        get() = getBandwidthEstimateInternal()

    internal val playbackRate: Float
        get() = getPlaybackRateInternal()

    internal val stickyResolutionOverride: Int?
        get() = getStickyResolutionOverride()

    internal val officialTimeSinceLastSeekOverride: Long?
        get() = getOfficialTimeSinceLastSeekOverride()

    internal val officialElapsedWallTimeOverride: Long?
        get() = getOfficialElapsedWallTimeOverride()

    internal val officialTimeSinceLastActionOverride: Long?
        get() = getOfficialTimeSinceLastActionOverride()

    internal val officialField57Override: Long?
        get() = getOfficialField57Override()

    internal val officialField68Override: Long?
        get() = getOfficialField68Override()

    internal val sabrReportRequestCancellationInfoOverride: Int?
        get() = getSabrReportRequestCancellationInfoOverride()

    internal val clientAbrVisibility: Int?
        get() = getClientAbrVisibility()

    // ------------------------------------------------------------------
    // public API – same signatures as Java for interop
    // ------------------------------------------------------------------

    fun ingest(response: SabrDecodedResponse): Boolean {
        return ingest(SabrResponseStatePatch.builtin(response))
    }

    fun ingest(patch: SabrResponseStatePatch): Boolean {
        var progressed = false
        val nextRequestPolicy = patch.nextRequestPolicy
        if (nextRequestPolicy != null) {
            this.nextRequestPolicy = nextRequestPolicy
        }
        if (nextRequestPolicy?.rawPlaybackCookie != null) {
            playbackCookie = nextRequestPolicy.rawPlaybackCookie!!.clone()
        }
        for (meta in patch.liveMetadata) {
            live = true
            postLiveDvr = meta.isPostLiveDvr
            if (meta.headSequenceNumber >= 0) {
                liveHeadSequenceNumber = meta.headSequenceNumber
            }
            if (meta.headTimeMs >= 0) {
                liveHeadTimeMs = meta.headTimeMs
            }
        }
        for (metadata in patch.formatMetadata) {
            val progress = findProgressForItag(metadata.itag)
            if (progress != null) {
                progressed = progress.observeMetadata(metadata) || progressed
            }
        }
        for (header in patch.mediaHeaders) {
            val progress = findProgressForItag(header.itag)
            if (progress != null) {
                progressed = progress.observeHeader(header) || progressed
            }
        }
        for (contextUpdate in patch.contextUpdates) {
            ingestContextUpdate(contextUpdate)
        }
        if (patch.contextSendingPolicy != null) {
            ingestContextSendingPolicy(patch.contextSendingPolicy!!)
        }
        return progressed
    }

    fun ingest(segment: SabrMediaSegment): Boolean {
        val progress = findProgressForItag(segment.header.itag)
        return progress != null && progress.observeSegment(segment)
    }

    fun ingestInitializationData(format: YoutubeSabrFormat, data: ByteArray): Boolean {
        val progress = findProgressForItag(format.itag) ?: return false
        progress.initReceived = true
        progress.observeInitializationData(data)
        return true
    }

    fun getBufferedRanges(): List<SabrBufferedRange> {
        bufferedRangesOverride?.let { return ArrayList(it) }
        val ranges = mutableListOf<SabrBufferedRange>()
        if (isAudioEnabled()) {
            if (audioFullyBuffered) {
                ranges.add(SabrBufferedRange.full(audio.format))
            } else {
                audio.addBufferedRange(
                    ranges,
                    audioLastOnlyRange,
                    lastOnlyRangesUseObservedTiming,
                    bufferedRangeStartSegmentIndexOffset,
                    bufferedRangeEndSegmentIndexOffset
                )
            }
        }
        if (isVideoEnabled()) {
            if (videoFullyBuffered) {
                ranges.add(SabrBufferedRange.full(video.format))
            } else {
                video.addBufferedRange(
                    ranges,
                    videoLastOnlyRange,
                    lastOnlyRangesUseObservedTiming,
                    bufferedRangeStartSegmentIndexOffset,
                    bufferedRangeEndSegmentIndexOffset
                )
            }
        }
        return ranges
    }

    fun setBufferedRangesOverride(bufferedRangesOverride: List<SabrBufferedRange>?) {
        this.bufferedRangesOverride = bufferedRangesOverride?.let { ArrayList(it) }
    }

    fun getPlayerTimeMs(): Long {
        if (playerTimeMsOverride >= 0) return playerTimeMsOverride
        return maxOf(audio.getBufferedEndMs(), video.getBufferedEndMs())
    }

    private fun getRequestPlayerTimeMsInternal(): Long {
        if (playerTimeMsOverride >= 0) return playerTimeMsOverride
        if ((isAudioEnabled() && !audio.initReceived) || (isVideoEnabled() && !video.initReceived)) {
            return 0
        }
        return getPlayerTimeMs()
    }

    internal fun getRequestPlayerTimeMs(): Long = getRequestPlayerTimeMsInternal()

    /** buffered end (ms) of the slower track = how far we can actually play. the weakest link wins. */
    fun getMinBufferedEndMs(): Long {
        if (!isVideoEnabled()) return audio.getBufferedEndMs()
        if (!isAudioEnabled()) return video.getBufferedEndMs()
        return minOf(audio.getBufferedEndMs(), video.getBufferedEndMs())
    }

    fun getBufferedEndMs(format: YoutubeSabrFormat): Long {
        return progressForItag(format.itag).getBufferedEndMs()
    }

    fun setPlayerTimeMs(playerTimeMs: Long) {
        playerTimeMsOverride = maxOf(0, playerTimeMs)
    }

    fun clearPlayerTimeMsOverride() {
        playerTimeMsOverride = -1
    }

    internal fun clearPlaybackCookie() {
        playbackCookie = null
    }

    internal fun isInitialized(format: YoutubeSabrFormat): Boolean {
        return progressForItag(format.itag).initReceived
    }

    internal fun resetInitialization(format: YoutubeSabrFormat) {
        progressForItag(format.itag).initReceived = false
    }

    fun getPlaybackCookie(): ByteArray? = playbackCookie?.clone()

    fun setPoToken(poToken: ByteArray?) {
        this.poToken = poToken?.clone()
    }

    fun getPoToken(): ByteArray? = poToken?.clone()

    internal fun getRawPlaybackCookie(): ByteArray? = playbackCookie

    internal fun getRawPoToken(): ByteArray? = poToken

    internal fun getActiveSabrContexts(): Collection<SabrContextUpdate> {
        val active = mutableListOf<SabrContextUpdate>()
        for (type in activeSabrContextTypes) {
            val ctx = sabrContexts[type]
            if (ctx != null) active.add(ctx)
        }
        return active
    }

    internal fun getUnsentSabrContextTypes(): Collection<Int> {
        val unsent = mutableListOf<Int>()
        for (type in sabrContexts.keys) {
            if (!activeSabrContextTypes.contains(type)) unsent.add(type)
        }
        return unsent
    }

    fun isComplete(): Boolean {
        return (!isAudioEnabled() || audio.isComplete()) && (!isVideoEnabled() || video.isComplete())
    }

    /** True once the server has sent live metadata for this stream (foundation for live support). */
    fun isLive(): Boolean = live

    /** True for an ended live stream still seekable as DVR. */
    fun isPostLiveDvr(): Boolean = postLiveDvr

    /** Latest segment the live edge has reached, or -1 if unknown / not live. */
    fun getLiveHeadSequenceNumber(): Long = liveHeadSequenceNumber

    /** Live head position in ms, or -1 if unknown / not live. */
    fun getLiveHeadTimeMs(): Long = liveHeadTimeMs

    /**
     * True when we have fetched up to (within a small margin of) the live head.
     */
    fun isAtLiveEdge(audioFormat: YoutubeSabrFormat, videoFormat: YoutubeSabrFormat): Boolean {
        if (!live || liveHeadSequenceNumber < 0) return false
        val slowerTrack = minOf(getMaxSegment(audioFormat), getMaxSegment(videoFormat)).toLong()
        return slowerTrack >= liveHeadSequenceNumber - LIVE_EDGE_MARGIN_SEGMENTS
    }

    fun getMaxSegment(format: YoutubeSabrFormat): Int = progressForItag(format.itag).maxSegment

    fun getEndSegment(format: YoutubeSabrFormat): Long = progressForItag(format.itag).endSegment

    /** True only after initialization bytes yielded an exact per-segment time index. */
    fun hasSegmentIndex(format: YoutubeSabrFormat): Boolean =
        progressForItag(format.itag).segmentIndex != null

    fun isComplete(format: YoutubeSabrFormat): Boolean = progressForItag(format.itag).isComplete()

    fun assumeBufferedUntil(format: YoutubeSabrFormat, endSegment: Int) {
        if (endSegment > 0) progressForItag(format.itag).assumeBufferedUntil(endSegment)
    }

    /**
     * Backward seek: forget buffered segments at/after [fromSegment].
     */
    fun rewindBufferedTo(format: YoutubeSabrFormat, fromSegment: Int) {
        if (fromSegment > 0) progressForItag(format.itag).rewindBufferedTo(fromSegment)
    }

    /**
     * Forward jump (cold seek far past the buffered edge).
     */
    fun jumpBufferedTo(format: YoutubeSabrFormat, fromSegment: Int) {
        if (fromSegment > 0) progressForItag(format.itag).jumpBufferedTo(fromSegment)
    }

    fun setFullyBuffered(format: YoutubeSabrFormat, fullyBuffered: Boolean) {
        when (format.itag) {
            audio.itag -> audioFullyBuffered = fullyBuffered
            video.itag -> videoFullyBuffered = fullyBuffered
            else -> throw IllegalArgumentException("Unknown SABR itag: " + format.itag)
        }
    }

    fun setLastOnlyRange(format: YoutubeSabrFormat, lastOnlyRange: Boolean) {
        when (format.itag) {
            audio.itag -> audioLastOnlyRange = lastOnlyRange
            video.itag -> videoLastOnlyRange = lastOnlyRange
            else -> throw IllegalArgumentException("Unknown SABR itag: " + format.itag)
        }
    }

    fun setLastOnlyRangesUseObservedTiming(useObservedTiming: Boolean) {
        lastOnlyRangesUseObservedTiming = useObservedTiming
    }

    fun setBufferedRangeSegmentIndexOffset(bufferedRangeSegmentIndexOffset: Int) {
        bufferedRangeStartSegmentIndexOffset = bufferedRangeSegmentIndexOffset
        bufferedRangeEndSegmentIndexOffset = bufferedRangeSegmentIndexOffset
    }

    fun setBufferedRangeSegmentIndexOffsets(startSegmentIndexOffset: Int, endSegmentIndexOffset: Int) {
        bufferedRangeStartSegmentIndexOffset = startSegmentIndexOffset
        bufferedRangeEndSegmentIndexOffset = endSegmentIndexOffset
    }

    @Synchronized
    fun setRequestTrackMode(enabledTrackTypesBitfield: Int, selectAudioFormat: Boolean, selectVideoFormat: Boolean) {
        this.enabledTrackTypesBitfield = enabledTrackTypesBitfield
        this.selectAudioFormat = selectAudioFormat
        this.selectVideoFormat = selectVideoFormat
    }

    fun setActiveTrackTypes(videoActive: Boolean, audioActive: Boolean) {
        if (audioActive && !videoActive) {
            setRequestTrackMode(YoutubeSabrRequestBuilder.ENABLED_TRACK_TYPES_AUDIO_ONLY, true, false)
        } else if (videoActive && !audioActive) {
            setRequestTrackMode(YoutubeSabrRequestBuilder.ENABLED_TRACK_TYPES_VIDEO_ONLY, false, true)
        } else if (videoActive) {
            setRequestTrackMode(YoutubeSabrRequestBuilder.ENABLED_TRACK_TYPES_VIDEO_AND_AUDIO, true, true)
        }
    }

    fun setAudioOnlyRequestMode() {
        setRequestTrackMode(TRACK_MODE_AUDIO_ONLY, true, false)
    }

    fun setVideoOnlyRequestMode() {
        setRequestTrackMode(TRACK_MODE_VIDEO_ONLY, false, true)
    }

    fun setVideoAndAudioRequestMode() {
        setRequestTrackMode(TRACK_MODE_VIDEO_AND_AUDIO, true, true)
    }

    private fun isAudioEnabled(): Boolean =
        enabledTrackTypesBitfield != YoutubeSabrRequestBuilder.ENABLED_TRACK_TYPES_VIDEO_ONLY

    private fun isVideoEnabled(): Boolean =
        enabledTrackTypesBitfield != YoutubeSabrRequestBuilder.ENABLED_TRACK_TYPES_AUDIO_ONLY

    fun setClientViewport(clientViewportWidth: Int, clientViewportHeight: Int) {
        this.clientViewportWidthInternal = clientViewportWidth
        this.clientViewportHeightInternal = clientViewportHeight
    }

    internal fun getClientViewportWidth(): Int = clientViewportWidthInternal

    internal fun getClientViewportHeight(): Int = clientViewportHeightInternal

    fun setBandwidthEstimate(bandwidthEstimate: Long) {
        this.bandwidthEstimateInternal = bandwidthEstimate
    }

    private fun getBandwidthEstimateInternal(): Long = bandwidthEstimateInternal

    fun getBandwidthEstimate(): Long = bandwidthEstimateInternal

    fun getNextRequestPolicy(): SabrNextRequestPolicy? = nextRequestPolicy

    fun setPlaybackRate(playbackRate: Float) {
        if (playbackRate > 0.0f) this.playbackRateInternal = playbackRate
    }

    private fun getPlaybackRateInternal(): Float = playbackRateInternal

    fun getPlaybackRate(): Float = playbackRateInternal

    internal fun getEnabledTrackTypesBitfield(): Int = enabledTrackTypesBitfield

    internal fun shouldSelectAudioFormat(): Boolean = selectAudioFormat

    internal fun shouldSelectVideoFormat(): Boolean = selectVideoFormat

    fun setWriteTopLevelPlayerTimeMs(writeTopLevelPlayerTimeMs: Boolean) {
        this.writeTopLevelPlayerTimeMs = writeTopLevelPlayerTimeMs
    }

    internal fun shouldWriteTopLevelPlayerTimeMs(): Boolean = writeTopLevelPlayerTimeMs

    fun setClientAbrVisibility(clientAbrVisibility: Int?) {
        this.clientAbrVisibilityInternal = clientAbrVisibility
    }

    internal fun getClientAbrVisibility(): Int? = clientAbrVisibilityInternal

    fun setWriteLastManualSelectedResolution(writeLastManualSelectedResolution: Boolean) {
        this.writeLastManualSelectedResolution = writeLastManualSelectedResolution
    }

    internal fun shouldWriteLastManualSelectedResolution(): Boolean = writeLastManualSelectedResolution

    fun setWriteAllPreferredFormats(writeAllPreferredFormats: Boolean) {
        this.writeAllPreferredFormats = writeAllPreferredFormats
    }

    internal fun shouldWriteAllPreferredFormats(): Boolean = writeAllPreferredFormats

    fun setWriteOfficialWebPreferredFormats(writeOfficialWebPreferredFormats: Boolean) {
        this.writeOfficialWebPreferredFormats = writeOfficialWebPreferredFormats
    }

    internal fun shouldWriteOfficialWebPreferredFormats(): Boolean = writeOfficialWebPreferredFormats

    fun setSelectVideoFormatBeforeAudio(selectVideoFormatBeforeAudio: Boolean) {
        this.selectVideoFormatBeforeAudio = selectVideoFormatBeforeAudio
    }

    internal fun shouldSelectVideoFormatBeforeAudio(): Boolean = selectVideoFormatBeforeAudio

    fun setWriteBufferedRangeTimeRange(writeBufferedRangeTimeRange: Boolean) {
        this.writeBufferedRangeTimeRange = writeBufferedRangeTimeRange
    }

    internal fun shouldWriteBufferedRangeTimeRange(): Boolean = writeBufferedRangeTimeRange

    fun setStickyResolutionOverride(stickyResolutionOverride: Int?) {
        this.stickyResolutionOverrideInternal = stickyResolutionOverride
    }

    internal fun getStickyResolutionOverride(): Int? = stickyResolutionOverrideInternal

    fun setOfficialWebClientAbrTimingOverrides(
        timeSinceLastSeek: Long?,
        elapsedWallTime: Long?,
        timeSinceLastAction: Long?,
        field57: Long?
    ) {
        officialTimeSinceLastSeekOverrideInternal = timeSinceLastSeek
        officialElapsedWallTimeOverrideInternal = elapsedWallTime
        officialTimeSinceLastActionOverrideInternal = timeSinceLastAction
        officialField57OverrideInternal = field57
    }

    fun setOfficialField68Override(field68: Long?) {
        officialField68OverrideInternal = field68
    }

    internal fun getOfficialTimeSinceLastSeekOverride(): Long? = officialTimeSinceLastSeekOverrideInternal

    internal fun getOfficialElapsedWallTimeOverride(): Long? = officialElapsedWallTimeOverrideInternal

    internal fun getOfficialTimeSinceLastActionOverride(): Long? = officialTimeSinceLastActionOverrideInternal

    internal fun getOfficialField57Override(): Long? = officialField57OverrideInternal

    internal fun getOfficialField68Override(): Long? = officialField68OverrideInternal

    fun setSabrReportRequestCancellationInfoOverride(sabrReportRequestCancellationInfoOverride: Int?) {
        this.sabrReportRequestCancellationInfoOverrideInternal = sabrReportRequestCancellationInfoOverride
    }

    internal fun getSabrReportRequestCancellationInfoOverride(): Int? =
        sabrReportRequestCancellationInfoOverrideInternal

    fun setWriteOfficialWebClientAbrFields(writeOfficialWebClientAbrFields: Boolean) {
        this.writeOfficialWebClientAbrFields = writeOfficialWebClientAbrFields
    }

    internal fun shouldWriteOfficialWebClientAbrFields(): Boolean = writeOfficialWebClientAbrFields

    fun summarizeBufferedRanges(): String {
        val ranges = getBufferedRanges()
        val builder = StringBuilder()
        for (i in ranges.indices) {
            if (i > 0) builder.append(',')
            builder.append(ranges[i].summarize())
        }
        return builder.toString()
    }

    fun getAverageSegmentDurationMs(format: YoutubeSabrFormat): Long =
        progressForItag(format.itag).averageDurationMs

    fun getSegmentStartMs(format: YoutubeSabrFormat, sequenceNumber: Int): Long =
        progressForItag(format.itag).getSegmentStartMs(sequenceNumber)

    fun getSegmentEndMs(format: YoutubeSabrFormat, sequenceNumber: Int): Long =
        progressForItag(format.itag).getSegmentEndMs(sequenceNumber)

    fun getSegmentNumberAtOrAfterTimeMs(format: YoutubeSabrFormat, timeMs: Long): Int =
        progressForItag(format.itag).getSegmentNumberAtOrAfterTimeMs(timeMs)

    private fun progressForItag(itag: Int): FormatProgress {
        return findProgressForItag(itag) ?: throw IllegalArgumentException("Unknown SABR itag: $itag")
    }

    private fun findProgressForItag(itag: Int): FormatProgress? {
        if (audio.itag == itag) return audio
        if (video.itag == itag) return video
        return null
    }

    private fun ingestContextUpdate(contextUpdate: SabrContextUpdate) {
        if (contextUpdate.type < 0 || contextUpdate.valueLength == 0) return
        if (contextUpdate.writePolicy == SabrContextUpdate.WRITE_POLICY_KEEP_EXISTING
            && sabrContexts.containsKey(contextUpdate.type)
        ) {
            return
        }
        sabrContexts[contextUpdate.type] = contextUpdate
        if (contextUpdate.isSendByDefault) {
            activeSabrContextTypes.add(contextUpdate.type)
        }
    }

    private fun ingestContextSendingPolicy(policy: SabrContextSendingPolicy) {
        activeSabrContextTypes.addAll(policy.startPolicy)
        activeSabrContextTypes.removeAll(policy.stopPolicy.toSet())
        for (type in policy.discardPolicy) {
            sabrContexts.remove(type)
            activeSabrContextTypes.remove(type)
        }
    }

    // ------------------------------------------------------------------
    // FormatProgress inner class
    // ------------------------------------------------------------------

    private class FormatProgress(val format: YoutubeSabrFormat) {
        val itag: Int = format.itag
        val lastModified: Long = format.lastModified
        val xtags: String? = format.xtags

        // pump thread writes it, ExoPlayer loader threads read it. volatile so they actually see it.
        @Volatile var initReceived: Boolean = false
        @Volatile var maxSegment: Int = 0
        // Highest segment with NO gap from the start.
        @Volatile var contiguousMaxSegment: Int = 0
        val aheadOfContiguous: MutableSet<Int> = HashSet()
        var observedMaxSegment: Int = 0
        @Volatile var endSegment: Long = -1
        var averageDurationMs: Long = 5000
        var firstObservedSegment: Int = -1
        var lastObservedSegment: Int = -1
        var observedStartMs: Long = -1
        var observedEndMs: Long = -1
        var lastObservedDurationMs: Long = -1
        var metadata: SabrFormatInitializationMetadata? = null
        var segmentIndex: SabrSegmentIndex? = null

        fun observeMetadata(metadata: SabrFormatInitializationMetadata): Boolean {
            this.metadata = metadata
            val previousEndSegment = endSegment
            endSegment = metadata.endSegmentNumber
            if (metadata.durationUnits > 0 && metadata.durationTimescale > 0 && metadata.endSegmentNumber > 0) {
                val totalMs = metadata.durationUnits * 1000L / metadata.durationTimescale
                averageDurationMs = maxOf(1L, totalMs / metadata.endSegmentNumber)
            } else if (endSegment > 0 && format.approxDurationMs > 0) {
                // The init metadata gives the segment count but no per-segment timing for this
                // format (seen on some YouTube responses). Derive the average from the format's
                // total duration so a cold seek maps the time to the right segment.
                averageDurationMs = maxOf(1L, format.approxDurationMs / endSegment)
            }
            return previousEndSegment != endSegment
        }

        fun observeSegment(segment: SabrMediaSegment): Boolean {
            if (!segment.header.isInitSegment || metadata == null || segmentIndex != null) return false
            return observeInitializationData(segment.data)
        }

        fun observeInitializationData(data: ByteArray): Boolean {
            if (segmentIndex != null) return false
            val mimeType = metadata?.mimeType ?: format.mimeType ?: return false
            try {
                if (mimeType.contains("mp4")) {
                    segmentIndex = if (metadata == null)
                        SabrMp4SegmentIndexParser.parse(data, format)
                    else
                        SabrMp4SegmentIndexParser.parse(data, metadata!!)
                } else if (mimeType.contains("webm")) {
                    segmentIndex = if (metadata == null)
                        SabrWebmSegmentIndexParser.parse(data, format)
                    else
                        SabrWebmSegmentIndexParser.parse(data, metadata!!)
                } else {
                    return false
                }
                observeSegmentIndex()
                return true
            } catch (ignored: SabrProtocolException) {
                if (metadata == null) return false
                try {
                    if (mimeType.contains("mp4")) {
                        segmentIndex = SabrMp4SegmentIndexParser.parse(data, format)
                    } else if (mimeType.contains("webm")) {
                        segmentIndex = SabrWebmSegmentIndexParser.parse(data, format)
                    } else {
                        return false
                    }
                    observeSegmentIndex()
                    return true
                } catch (ignoredFallback: SabrProtocolException) {
                    return false
                }
            } catch (ignored: Exception) {
                return false
            }
        }

        fun observeSegmentIndex() {
            val idx = segmentIndex ?: return
            if (endSegment <= 0) endSegment = idx.size().toLong()
            if (format.approxDurationMs > 0 && endSegment > 0) {
                averageDurationMs = maxOf(1L, format.approxDurationMs / endSegment)
            }
        }

        fun observeHeader(header: SabrMediaHeader): Boolean {
            if (header.isInitSegment) {
                val changed = !initReceived
                initReceived = true
                return changed
            }
            if (header.sequenceNumber > maxSegment) maxSegment = header.sequenceNumber
            val seq = header.sequenceNumber
            if (seq == contiguousMaxSegment + 1) {
                contiguousMaxSegment = seq
                while (aheadOfContiguous.remove(contiguousMaxSegment + 1)) {
                    contiguousMaxSegment++
                }
            } else if (seq > contiguousMaxSegment + 1) {
                aheadOfContiguous.add(seq)
            }
            if (header.sequenceNumber > observedMaxSegment) observedMaxSegment = header.sequenceNumber
            if (firstObservedSegment < 0 || header.sequenceNumber < firstObservedSegment) {
                firstObservedSegment = header.sequenceNumber
            }
            if (header.sequenceNumber >= lastObservedSegment) {
                lastObservedSegment = header.sequenceNumber
                lastObservedDurationMs = header.durationMs
            }
            if (header.startMs >= 0 && header.durationMs > 0) {
                if (observedStartMs < 0 || header.startMs < observedStartMs) observedStartMs = header.startMs
                observedEndMs = maxOf(observedEndMs, header.startMs + header.durationMs)
            }
            return header.sequenceNumber == maxSegment
        }

        fun addBufferedRange(
            ranges: MutableList<SabrBufferedRange>,
            lastOnlyRange: Boolean,
            lastOnlyRangeUseObservedTiming: Boolean,
            startSegmentIndexOffset: Int,
            endSegmentIndexOffset: Int
        ) {
            if (!initReceived || maxSegment <= 0) return
            if (lastOnlyRange && lastObservedSegment > 0) {
                val durationMs = if (lastObservedDurationMs > 0) lastObservedDurationMs else averageDurationMs
                val startTimeMs = if (lastOnlyRangeUseObservedTiming) getSegmentStartMs(lastObservedSegment) else 0
                ranges.add(
                    SabrBufferedRange(
                        itag, lastModified, xtags, startTimeMs, durationMs,
                        applySegmentIndexOffset(lastObservedSegment, startSegmentIndexOffset),
                        applySegmentIndexOffset(lastObservedSegment, endSegmentIndexOffset),
                        1000
                    )
                )
                return
            }
            // Only trust observed timing when there is NO hole (contiguous == max)
            val canUseObservedTiming = observedStartMs >= 0 && observedEndMs > observedStartMs
                && observedMaxSegment >= maxSegment && firstObservedSegment > 0
                && contiguousMaxSegment >= maxSegment
            ranges.add(
                SabrBufferedRange(
                    itag, lastModified, xtags,
                    if (canUseObservedTiming) observedStartMs else 0,
                    if (canUseObservedTiming) observedEndMs - observedStartMs else getBufferedEndMs(),
                    applySegmentIndexOffset(
                        if (canUseObservedTiming) firstObservedSegment else 1,
                        startSegmentIndexOffset
                    ),
                    applySegmentIndexOffset(contiguousMaxSegment, endSegmentIndexOffset),
                    1000
                )
            )
        }

        private fun applySegmentIndexOffset(segmentIndex: Int, segmentIndexOffset: Int): Int {
            return maxOf(0, segmentIndex + segmentIndexOffset)
        }

        fun getBufferedEndMs(): Long {
            // contiguous, not maxSegment: a hole means we are NOT really buffered past it.
            val indexedEndMs = getSegmentEndMs(contiguousMaxSegment)
            if (indexedEndMs >= 0) return indexedEndMs
            return contiguousMaxSegment * averageDurationMs
        }

        fun getSegmentStartMs(sequenceNumber: Int): Long {
            if (sequenceNumber <= 1) return 0
            val idx = segmentIndex
            if (idx != null) {
                val entry = idx.getEntry(sequenceNumber)
                if (entry != null) return entry.startMs
            }
            return maxOf(0, sequenceNumber - 1L) * averageDurationMs
        }

        fun getSegmentEndMs(sequenceNumber: Int): Long {
            val idx = segmentIndex
            if (idx != null) {
                val entry = idx.getEntry(sequenceNumber)
                if (entry != null) return entry.endMs
            }
            if (sequenceNumber <= 0) return -1
            return sequenceNumber * averageDurationMs
        }

        fun getSegmentNumberAtOrAfterTimeMs(timeMs: Long): Int {
            if (timeMs <= 0) return 1
            val idx = segmentIndex
            if (idx != null) {
                for (i in 1..idx.size()) {
                    val entry = idx.getEntry(i)
                    if (entry != null && entry.endMs > timeMs) return entry.sequenceNumber
                }
                return if (idx.size() == Int.MAX_VALUE) Int.MAX_VALUE else maxOf(1, idx.size() + 1)
            }
            val durationMs = maxOf(1, averageDurationMs)
            val sequenceNumber = timeMs / durationMs + 1
            return if (sequenceNumber > Int.MAX_VALUE) Int.MAX_VALUE else maxOf(1, sequenceNumber.toInt())
        }

        fun assumeBufferedUntil(endSegment: Int) {
            maxSegment = maxOf(maxSegment, endSegment)
        }

        fun rewindBufferedTo(fromSegment: Int) {
            val last = maxOf(0, fromSegment - 1)
            if (last >= contiguousMaxSegment) return
            maxSegment = last
            contiguousMaxSegment = last
            observedMaxSegment = minOf(observedMaxSegment, last)
            firstObservedSegment = -1
            lastObservedSegment = -1
            observedStartMs = -1
            observedEndMs = -1
        }

        fun jumpBufferedTo(fromSegment: Int) {
            val last = maxOf(0, fromSegment - 1)
            if (last <= contiguousMaxSegment) return
            contiguousMaxSegment = last
            aheadOfContiguous.removeIf { it <= last }
            while (aheadOfContiguous.remove(contiguousMaxSegment + 1)) {
                contiguousMaxSegment++
            }
            maxSegment = maxOf(maxSegment, contiguousMaxSegment)
            observedMaxSegment = minOf(observedMaxSegment, contiguousMaxSegment)
            firstObservedSegment = -1
            lastObservedSegment = -1
            observedStartMs = -1
            observedEndMs = -1
        }

        fun isComplete(): Boolean = initReceived && endSegment > 0 && maxSegment >= endSegment
    }
}
