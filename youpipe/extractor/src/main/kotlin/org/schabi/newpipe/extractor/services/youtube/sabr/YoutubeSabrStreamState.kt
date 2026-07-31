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
        private const val LIVE_EDGE_MARGIN_SEGMENTS: Long = 2
    }

    private val audio: FormatProgress = FormatProgress(audioFormat)
    private val video: FormatProgress = FormatProgress(videoFormat)

    private val sabrContexts: MutableMap<Int, SabrContextUpdate> = LinkedHashMap()
    private val activeSabrContextTypes: MutableSet<Int> = LinkedHashSet()

    @Volatile private var playbackCookie: ByteArray? = null
    @Volatile private var poToken: ByteArray? = null
    @Volatile private var nextRequestPolicy: SabrNextRequestPolicy? = null

    private var playerTimeMsOverride: Long = -1
    private var audioFullyBuffered: Boolean = false
    private var videoFullyBuffered: Boolean = false
    private var audioLastOnlyRange: Boolean = false
    private var videoLastOnlyRange: Boolean = false
    private var lastOnlyRangesUseObservedTiming: Boolean = false

    @Volatile private var enabledTrackTypesBitfieldInternal: Int =
        YoutubeSabrRequestBuilder.ENABLED_TRACK_TYPES_VIDEO_AND_AUDIO
    @Volatile private var selectAudioFormatInternal: Boolean = true
    @Volatile private var selectVideoFormatInternal: Boolean = true

    private var writeTopLevelPlayerTimeMsInternal: Boolean = true
    private var clientViewportWidthInternal: Int = -1
    private var clientViewportHeightInternal: Int = -1
    private var bandwidthEstimateInternal: Long = -1
    private var playbackRateInternal: Float = 1.0f
    private var bufferedRangeStartSegmentIndexOffset: Int = 0
    private var bufferedRangeEndSegmentIndexOffset: Int = 0
    private var clientAbrVisibilityInternal: Int? = 1
    private var writeLastManualSelectedResolutionInternal: Boolean = false
    private var writeAllPreferredFormatsInternal: Boolean = false
    private var writeOfficialWebPreferredFormatsInternal: Boolean = false
    private var selectVideoFormatBeforeAudioInternal: Boolean = false
    private var writeBufferedRangeTimeRangeInternal: Boolean = true
    private var stickyResolutionOverrideInternal: Int? = null
    private var officialTimeSinceLastSeekOverrideInternal: Long? = null
    private var officialElapsedWallTimeOverrideInternal: Long? = null
    private var officialTimeSinceLastActionOverrideInternal: Long? = null
    private var officialField57OverrideInternal: Long? = null
    private var officialField68OverrideInternal: Long? = null
    private var sabrReportRequestCancellationInfoOverrideInternal: Int? = null
    private var writeOfficialWebClientAbrFieldsInternal: Boolean = false
    private var bufferedRangesOverride: List<SabrBufferedRange>? = null

    private var live: Boolean = false
    private var postLiveDvr: Boolean = false
    private var liveHeadSequenceNumber: Long = -1
    private var liveHeadTimeMs: Long = -1

    // ------------------------------------------------------------------
    // ingest
    // ------------------------------------------------------------------

    fun ingest(response: SabrDecodedResponse): Boolean {
        return ingest(SabrResponseStatePatch.builtin(response))
    }

    fun ingest(patch: SabrResponseStatePatch): Boolean {
        var progressed = false
        val nextPolicy = patch.getNextRequestPolicy()
        if (nextPolicy != null) {
            this.nextRequestPolicy = nextPolicy
        }
        if (nextPolicy?.getRawPlaybackCookie() != null) {
            playbackCookie = nextPolicy.getRawPlaybackCookie()!!.clone()
        }
        for (meta in patch.getLiveMetadata()) {
            live = true
            postLiveDvr = meta.isPostLiveDvr
            if (meta.headSequenceNumber >= 0) liveHeadSequenceNumber = meta.headSequenceNumber
            if (meta.headTimeMs >= 0) liveHeadTimeMs = meta.headTimeMs
        }
        for (metadata in patch.getFormatMetadata()) {
            val progress = findProgressForItag(metadata.itag)
            if (progress != null) progressed = progress.observeMetadata(metadata) || progressed
        }
        for (header in patch.getMediaHeaders()) {
            val progress = findProgressForItag(header.itag)
            if (progress != null) progressed = progress.observeHeader(header) || progressed
        }
        for (contextUpdate in patch.getContextUpdates()) ingestContextUpdate(contextUpdate)
        val ctxPolicy = patch.getContextSendingPolicy()
        if (ctxPolicy != null) ingestContextSendingPolicy(ctxPolicy)
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
            if (audioFullyBuffered) ranges.add(SabrBufferedRange.full(audio.format))
            else audio.addBufferedRange(ranges, audioLastOnlyRange, lastOnlyRangesUseObservedTiming, bufferedRangeStartSegmentIndexOffset, bufferedRangeEndSegmentIndexOffset)
        }
        if (isVideoEnabled()) {
            if (videoFullyBuffered) ranges.add(SabrBufferedRange.full(video.format))
            else video.addBufferedRange(ranges, videoLastOnlyRange, lastOnlyRangesUseObservedTiming, bufferedRangeStartSegmentIndexOffset, bufferedRangeEndSegmentIndexOffset)
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

    internal fun getRequestPlayerTimeMs(): Long {
        if (playerTimeMsOverride >= 0) return playerTimeMsOverride
        if ((isAudioEnabled() && !audio.initReceived) || (isVideoEnabled() && !video.initReceived)) return 0
        return getPlayerTimeMs()
    }

    fun getMinBufferedEndMs(): Long {
        if (!isVideoEnabled()) return audio.getBufferedEndMs()
        if (!isAudioEnabled()) return video.getBufferedEndMs()
        return minOf(audio.getBufferedEndMs(), video.getBufferedEndMs())
    }

    fun getBufferedEndMs(format: YoutubeSabrFormat): Long = progressForItag(format.itag).getBufferedEndMs()

    fun setPlayerTimeMs(playerTimeMs: Long) {
        playerTimeMsOverride = maxOf(0, playerTimeMs)
    }

    fun clearPlayerTimeMsOverride() {
        playerTimeMsOverride = -1
    }

    internal fun clearPlaybackCookie() {
        playbackCookie = null
    }

    internal fun isInitialized(format: YoutubeSabrFormat): Boolean = progressForItag(format.itag).initReceived

    internal fun resetInitialization(format: YoutubeSabrFormat) {
        progressForItag(format.itag).initReceived = false
    }

    fun getPlaybackCookie(): ByteArray? = playbackCookie?.clone()
    fun setPoToken(poToken: ByteArray?) { this.poToken = poToken?.clone() }
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
        for (type in sabrContexts.keys) if (!activeSabrContextTypes.contains(type)) unsent.add(type)
        return unsent
    }

    fun isComplete(): Boolean = (!isAudioEnabled() || audio.isComplete()) && (!isVideoEnabled() || video.isComplete())
    fun isLive(): Boolean = live
    fun isPostLiveDvr(): Boolean = postLiveDvr
    fun getLiveHeadSequenceNumber(): Long = liveHeadSequenceNumber
    fun getLiveHeadTimeMs(): Long = liveHeadTimeMs

    fun isAtLiveEdge(audioFormat: YoutubeSabrFormat, videoFormat: YoutubeSabrFormat): Boolean {
        if (!live || liveHeadSequenceNumber < 0) return false
        val slowerTrack = minOf(getMaxSegment(audioFormat), getMaxSegment(videoFormat)).toLong()
        return slowerTrack >= liveHeadSequenceNumber - LIVE_EDGE_MARGIN_SEGMENTS
    }

    fun getMaxSegment(format: YoutubeSabrFormat): Int = progressForItag(format.itag).maxSegment
    fun getEndSegment(format: YoutubeSabrFormat): Long = progressForItag(format.itag).endSegment
    fun hasSegmentIndex(format: YoutubeSabrFormat): Boolean = progressForItag(format.itag).segmentIndex != null
    fun isComplete(format: YoutubeSabrFormat): Boolean = progressForItag(format.itag).isComplete()

    fun assumeBufferedUntil(format: YoutubeSabrFormat, endSegment: Int) {
        if (endSegment > 0) progressForItag(format.itag).assumeBufferedUntil(endSegment)
    }
    fun rewindBufferedTo(format: YoutubeSabrFormat, fromSegment: Int) {
        if (fromSegment > 0) progressForItag(format.itag).rewindBufferedTo(fromSegment)
    }
    fun jumpBufferedTo(format: YoutubeSabrFormat, fromSegment: Int) {
        if (fromSegment > 0) progressForItag(format.itag).jumpBufferedTo(fromSegment)
    }

    fun setFullyBuffered(format: YoutubeSabrFormat, fullyBuffered: Boolean) {
        when (format.itag) {
            audio.itag -> audioFullyBuffered = fullyBuffered
            video.itag -> videoFullyBuffered = fullyBuffered
            else -> throw IllegalArgumentException("Unknown SABR itag: ${format.itag}")
        }
    }
    fun setLastOnlyRange(format: YoutubeSabrFormat, lastOnlyRange: Boolean) {
        when (format.itag) {
            audio.itag -> audioLastOnlyRange = lastOnlyRange
            video.itag -> videoLastOnlyRange = lastOnlyRange
            else -> throw IllegalArgumentException("Unknown SABR itag: ${format.itag}")
        }
    }
    fun setLastOnlyRangesUseObservedTiming(useObservedTiming: Boolean) {
        lastOnlyRangesUseObservedTiming = useObservedTiming
    }
    fun setBufferedRangeSegmentIndexOffset(offset: Int) {
        bufferedRangeStartSegmentIndexOffset = offset
        bufferedRangeEndSegmentIndexOffset = offset
    }
    fun setBufferedRangeSegmentIndexOffsets(start: Int, end: Int) {
        bufferedRangeStartSegmentIndexOffset = start
        bufferedRangeEndSegmentIndexOffset = end
    }

    @Synchronized
    fun setRequestTrackMode(enabledTrackTypesBitfield: Int, selectAudioFormat: Boolean, selectVideoFormat: Boolean) {
        this.enabledTrackTypesBitfieldInternal = enabledTrackTypesBitfield
        this.selectAudioFormatInternal = selectAudioFormat
        this.selectVideoFormatInternal = selectVideoFormat
    }

    fun setActiveTrackTypes(videoActive: Boolean, audioActive: Boolean) {
        if (audioActive && !videoActive) setRequestTrackMode(YoutubeSabrRequestBuilder.ENABLED_TRACK_TYPES_AUDIO_ONLY, true, false)
        else if (videoActive && !audioActive) setRequestTrackMode(YoutubeSabrRequestBuilder.ENABLED_TRACK_TYPES_VIDEO_ONLY, false, true)
        else if (videoActive) setRequestTrackMode(YoutubeSabrRequestBuilder.ENABLED_TRACK_TYPES_VIDEO_AND_AUDIO, true, true)
    }
    fun setAudioOnlyRequestMode() { setRequestTrackMode(TRACK_MODE_AUDIO_ONLY, true, false) }
    fun setVideoOnlyRequestMode() { setRequestTrackMode(TRACK_MODE_VIDEO_ONLY, false, true) }
    fun setVideoAndAudioRequestMode() { setRequestTrackMode(TRACK_MODE_VIDEO_AND_AUDIO, true, true) }

    private fun isAudioEnabled(): Boolean = enabledTrackTypesBitfieldInternal != YoutubeSabrRequestBuilder.ENABLED_TRACK_TYPES_VIDEO_ONLY
    private fun isVideoEnabled(): Boolean = enabledTrackTypesBitfieldInternal != YoutubeSabrRequestBuilder.ENABLED_TRACK_TYPES_AUDIO_ONLY

    fun setClientViewport(clientViewportWidth: Int, clientViewportHeight: Int) {
        this.clientViewportWidthInternal = clientViewportWidth
        this.clientViewportHeightInternal = clientViewportHeight
    }
    internal fun getClientViewportWidth(): Int = clientViewportWidthInternal
    internal fun getClientViewportHeight(): Int = clientViewportHeightInternal

    fun setBandwidthEstimate(bandwidthEstimate: Long) { this.bandwidthEstimateInternal = bandwidthEstimate }
    fun getBandwidthEstimate(): Long = bandwidthEstimateInternal
    internal fun getBandwidthEstimateInternalMethod(): Long = bandwidthEstimateInternal

    fun getNextRequestPolicy(): SabrNextRequestPolicy? = nextRequestPolicy

    fun setPlaybackRate(playbackRate: Float) { if (playbackRate > 0.0f) this.playbackRateInternal = playbackRate }
    fun getPlaybackRate(): Float = playbackRateInternal
    internal fun getPlaybackRateInternalMethod(): Float = playbackRateInternal

    internal fun getEnabledTrackTypesBitfield(): Int = enabledTrackTypesBitfieldInternal
    internal fun shouldSelectAudioFormat(): Boolean = selectAudioFormatInternal
    internal fun shouldSelectVideoFormat(): Boolean = selectVideoFormatInternal

    fun setWriteTopLevelPlayerTimeMs(writeTopLevelPlayerTimeMs: Boolean) { this.writeTopLevelPlayerTimeMsInternal = writeTopLevelPlayerTimeMs }
    internal fun shouldWriteTopLevelPlayerTimeMs(): Boolean = writeTopLevelPlayerTimeMsInternal

    fun setClientAbrVisibility(clientAbrVisibility: Int?) { this.clientAbrVisibilityInternal = clientAbrVisibility }
    internal fun getClientAbrVisibility(): Int? = clientAbrVisibilityInternal

    fun setWriteLastManualSelectedResolution(write: Boolean) { this.writeLastManualSelectedResolutionInternal = write }
    internal fun shouldWriteLastManualSelectedResolution(): Boolean = writeLastManualSelectedResolutionInternal

    fun setWriteAllPreferredFormats(write: Boolean) { this.writeAllPreferredFormatsInternal = write }
    internal fun shouldWriteAllPreferredFormats(): Boolean = writeAllPreferredFormatsInternal

    fun setWriteOfficialWebPreferredFormats(write: Boolean) { this.writeOfficialWebPreferredFormatsInternal = write }
    internal fun shouldWriteOfficialWebPreferredFormats(): Boolean = writeOfficialWebPreferredFormatsInternal

    fun setSelectVideoFormatBeforeAudio(select: Boolean) { this.selectVideoFormatBeforeAudioInternal = select }
    internal fun shouldSelectVideoFormatBeforeAudio(): Boolean = selectVideoFormatBeforeAudioInternal

    fun setWriteBufferedRangeTimeRange(write: Boolean) { this.writeBufferedRangeTimeRangeInternal = write }
    internal fun shouldWriteBufferedRangeTimeRange(): Boolean = writeBufferedRangeTimeRangeInternal

    fun setStickyResolutionOverride(sticky: Int?) { this.stickyResolutionOverrideInternal = sticky }
    internal fun getStickyResolutionOverride(): Int? = stickyResolutionOverrideInternal

    fun setOfficialWebClientAbrTimingOverrides(timeSinceLastSeek: Long?, elapsedWallTime: Long?, timeSinceLastAction: Long?, field57: Long?) {
        officialTimeSinceLastSeekOverrideInternal = timeSinceLastSeek
        officialElapsedWallTimeOverrideInternal = elapsedWallTime
        officialTimeSinceLastActionOverrideInternal = timeSinceLastAction
        officialField57OverrideInternal = field57
    }
    fun setOfficialField68Override(field68: Long?) { officialField68OverrideInternal = field68 }
    internal fun getOfficialTimeSinceLastSeekOverride(): Long? = officialTimeSinceLastSeekOverrideInternal
    internal fun getOfficialElapsedWallTimeOverride(): Long? = officialElapsedWallTimeOverrideInternal
    internal fun getOfficialTimeSinceLastActionOverride(): Long? = officialTimeSinceLastActionOverrideInternal
    internal fun getOfficialField57Override(): Long? = officialField57OverrideInternal
    internal fun getOfficialField68Override(): Long? = officialField68OverrideInternal

    fun setSabrReportRequestCancellationInfoOverride(v: Int?) { this.sabrReportRequestCancellationInfoOverrideInternal = v }
    internal fun getSabrReportRequestCancellationInfoOverride(): Int? = sabrReportRequestCancellationInfoOverrideInternal

    fun setWriteOfficialWebClientAbrFields(write: Boolean) { this.writeOfficialWebClientAbrFieldsInternal = write }
    internal fun shouldWriteOfficialWebClientAbrFields(): Boolean = writeOfficialWebClientAbrFieldsInternal

    fun summarizeBufferedRanges(): String {
        val ranges = getBufferedRanges()
        val builder = StringBuilder()
        for (i in ranges.indices) {
            if (i > 0) builder.append(',')
            builder.append(ranges[i].summarize())
        }
        return builder.toString()
    }

    fun getAverageSegmentDurationMs(format: YoutubeSabrFormat): Long = progressForItag(format.itag).averageDurationMs
    fun getSegmentStartMs(format: YoutubeSabrFormat, sequenceNumber: Int): Long = progressForItag(format.itag).getSegmentStartMs(sequenceNumber)
    fun getSegmentEndMs(format: YoutubeSabrFormat, sequenceNumber: Int): Long = progressForItag(format.itag).getSegmentEndMs(sequenceNumber)
    fun getSegmentNumberAtOrAfterTimeMs(format: YoutubeSabrFormat, timeMs: Long): Int = progressForItag(format.itag).getSegmentNumberAtOrAfterTimeMs(timeMs)

    private fun progressForItag(itag: Int): FormatProgress = findProgressForItag(itag) ?: throw IllegalArgumentException("Unknown SABR itag: $itag")
    private fun findProgressForItag(itag: Int): FormatProgress? {
        if (audio.itag == itag) return audio
        if (video.itag == itag) return video
        return null
    }

    private fun ingestContextUpdate(contextUpdate: SabrContextUpdate) {
        if (contextUpdate.type < 0 || contextUpdate.getValueLength() == 0) return
        if (contextUpdate.writePolicy == SabrContextUpdate.WRITE_POLICY_KEEP_EXISTING && sabrContexts.containsKey(contextUpdate.type)) return
        sabrContexts[contextUpdate.type] = contextUpdate
        if (contextUpdate.isSendByDefault) activeSabrContextTypes.add(contextUpdate.type)
    }

    private fun ingestContextSendingPolicy(policy: SabrContextSendingPolicy) {
        activeSabrContextTypes.addAll(policy.startPolicy)
        activeSabrContextTypes.removeAll(policy.stopPolicy.toSet())
        for (type in policy.discardPolicy) {
            sabrContexts.remove(type)
            activeSabrContextTypes.remove(type)
        }
    }

    private class FormatProgress(val format: YoutubeSabrFormat) {
        val itag: Int = format.itag
        val lastModified: Long = format.lastModified
        val xtags: String? = format.xtags
        @Volatile var initReceived: Boolean = false
        @Volatile var maxSegment: Int = 0
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
                    segmentIndex = if (metadata == null) SabrMp4SegmentIndexParser.parse(data, format) else SabrMp4SegmentIndexParser.parse(data, metadata!!)
                } else if (mimeType.contains("webm")) {
                    segmentIndex = if (metadata == null) SabrWebmSegmentIndexParser.parse(data, format) else SabrWebmSegmentIndexParser.parse(data, metadata!!)
                } else return false
                observeSegmentIndex()
                return true
            } catch (ignored: SabrProtocolException) {
                if (metadata == null) return false
                try {
                    if (mimeType.contains("mp4")) segmentIndex = SabrMp4SegmentIndexParser.parse(data, format)
                    else if (mimeType.contains("webm")) segmentIndex = SabrWebmSegmentIndexParser.parse(data, format)
                    else return false
                    observeSegmentIndex()
                    return true
                } catch (ignoredFallback: SabrProtocolException) { return false }
            } catch (ignored: Exception) { return false }
        }

        fun observeSegmentIndex() {
            val idx = segmentIndex ?: return
            if (endSegment <= 0) endSegment = idx.size().toLong()
            if (format.approxDurationMs > 0 && endSegment > 0) averageDurationMs = maxOf(1L, format.approxDurationMs / endSegment)
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
                while (aheadOfContiguous.remove(contiguousMaxSegment + 1)) contiguousMaxSegment++
            } else if (seq > contiguousMaxSegment + 1) aheadOfContiguous.add(seq)
            if (header.sequenceNumber > observedMaxSegment) observedMaxSegment = header.sequenceNumber
            if (firstObservedSegment < 0 || header.sequenceNumber < firstObservedSegment) firstObservedSegment = header.sequenceNumber
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

        fun addBufferedRange(ranges: MutableList<SabrBufferedRange>, lastOnlyRange: Boolean, lastOnlyRangeUseObservedTiming: Boolean, startOffset: Int, endOffset: Int) {
            if (!initReceived || maxSegment <= 0) return
            if (lastOnlyRange && lastObservedSegment > 0) {
                val durationMs = if (lastObservedDurationMs > 0) lastObservedDurationMs else averageDurationMs
                val startTimeMs = if (lastOnlyRangeUseObservedTiming) getSegmentStartMs(lastObservedSegment) else 0
                ranges.add(SabrBufferedRange(itag, lastModified, xtags, startTimeMs, durationMs, applyOffset(lastObservedSegment, startOffset), applyOffset(lastObservedSegment, endOffset), 1000))
                return
            }
            val canUseObservedTiming = observedStartMs >= 0 && observedEndMs > observedStartMs && observedMaxSegment >= maxSegment && firstObservedSegment > 0 && contiguousMaxSegment >= maxSegment
            ranges.add(SabrBufferedRange(itag, lastModified, xtags, if (canUseObservedTiming) observedStartMs else 0, if (canUseObservedTiming) observedEndMs - observedStartMs else getBufferedEndMs(), applyOffset(if (canUseObservedTiming) firstObservedSegment else 1, startOffset), applyOffset(contiguousMaxSegment, endOffset), 1000))
        }

        private fun applyOffset(segmentIndex: Int, offset: Int): Int = maxOf(0, segmentIndex + offset)

        fun getBufferedEndMs(): Long {
            val indexedEndMs = getSegmentEndMs(contiguousMaxSegment)
            if (indexedEndMs >= 0) return indexedEndMs
            return contiguousMaxSegment * averageDurationMs
        }
        fun getSegmentStartMs(sequenceNumber: Int): Long {
            if (sequenceNumber <= 1) return 0
            val idx = segmentIndex
            if (idx != null) { val entry = idx.getEntry(sequenceNumber); if (entry != null) return entry.startMs }
            return maxOf(0, sequenceNumber - 1L) * averageDurationMs
        }
        fun getSegmentEndMs(sequenceNumber: Int): Long {
            val idx = segmentIndex
            if (idx != null) { val entry = idx.getEntry(sequenceNumber); if (entry != null) return entry.getEndMs() }
            if (sequenceNumber <= 0) return -1
            return sequenceNumber * averageDurationMs
        }
        fun getSegmentNumberAtOrAfterTimeMs(timeMs: Long): Int {
            if (timeMs <= 0) return 1
            val idx = segmentIndex
            if (idx != null) {
                for (i in 1..idx.size()) { val entry = idx.getEntry(i); if (entry != null && entry.getEndMs() > timeMs) return entry.sequenceNumber }
                return if (idx.size() == Int.MAX_VALUE) Int.MAX_VALUE else maxOf(1, idx.size() + 1)
            }
            val durationMs = maxOf(1, averageDurationMs)
            val sequenceNumber = timeMs / durationMs + 1
            return if (sequenceNumber > Int.MAX_VALUE) Int.MAX_VALUE else maxOf(1, sequenceNumber.toInt())
        }
        fun assumeBufferedUntil(endSegment: Int) { maxSegment = maxOf(maxSegment, endSegment) }
        fun rewindBufferedTo(fromSegment: Int) {
            val last = maxOf(0, fromSegment - 1)
            if (last >= contiguousMaxSegment) return
            maxSegment = last; contiguousMaxSegment = last; observedMaxSegment = minOf(observedMaxSegment, last)
            firstObservedSegment = -1; lastObservedSegment = -1; observedStartMs = -1; observedEndMs = -1
        }
        fun jumpBufferedTo(fromSegment: Int) {
            val last = maxOf(0, fromSegment - 1)
            if (last <= contiguousMaxSegment) return
            contiguousMaxSegment = last
            aheadOfContiguous.removeIf { it <= last }
            while (aheadOfContiguous.remove(contiguousMaxSegment + 1)) contiguousMaxSegment++
            maxSegment = maxOf(maxSegment, contiguousMaxSegment)
            observedMaxSegment = minOf(observedMaxSegment, contiguousMaxSegment)
            firstObservedSegment = -1; lastObservedSegment = -1; observedStartMs = -1; observedEndMs = -1
        }
        fun isComplete(): Boolean = initReceived && endSegment > 0 && maxSegment >= endSegment
    }
}
