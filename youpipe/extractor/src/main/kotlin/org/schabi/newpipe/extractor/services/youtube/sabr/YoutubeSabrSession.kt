package org.schabi.newpipe.extractor.services.youtube.sabr

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.Arrays
import java.util.Base64
import java.util.Collections
import java.util.Deque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class YoutubeSabrSession {

    interface BackoffListener {
        fun onBackoffStarted(durationMs: Int)
        fun onBackoffFinished()
    }

    companion object {
        private const val MAX_REQUESTS_PER_SEGMENT = 16
        private const val MAX_POLICY_ONLY_RESPONSES_PER_SEGMENT = 3
        private const val MAX_REDIRECTS_PER_SESSION = 3
        private const val MAX_RELOADS_PER_SESSION = 2
        private const val INTEGRITY_RELOAD_AFTER_FAILURES = 2
        private const val MAX_INCOMPLETE_MEDIA_RESPONSES = 3
        private const val MAX_PO_TOKEN_REFRESHES = 2
        private const val MAX_BACKOFF_MS = 30_000
        private const val MAX_DEMAND_BACKOFF_MS = 2_000
        private const val MAX_CACHE_BYTES: Long = 32L * 1024 * 1024
        private const val MIN_CACHED_SEGMENTS = 6
        private const val MAX_BOOTSTRAP_RESPONSES = 8
        private const val MAX_INITIALIZATION_BYTES = 4 * 1024 * 1024
        private const val MAX_DIAGNOSTIC_CHARS = 32 * 1024
        private const val MAX_TRACE_EVENTS = 1024
        private const val EVICT_BEHIND_MS: Long = 10_000
        private const val SEEK_KEEP_WINDOW_MS: Long = 8_000

        @JvmStatic
        fun getMaxCacheBytes(): Long = MAX_CACHE_BYTES

        @JvmStatic
        private fun summarizeSegments(segments: List<SabrMediaSegment>): String {
            if (segments.isEmpty()) return "[]"
            val summary = StringBuilder("[")
            for (i in segments.indices) {
                if (i > 0) summary.append(',')
                val segment = segments[i]
                summary.append(segment.header.itag).append(':')
                if (segment.header.isInitSegment) summary.append("init")
                else summary.append(segment.header.sequenceNumber)
            }
            return summary.append(']').toString()
        }

        @JvmStatic
        private fun validateRedirectUrl(redirectUrl: String) {
            try {
                val uri = URI.create(redirectUrl)
                val host = uri.host
                if (!"https".equals(uri.scheme, ignoreCase = true) || host == null ||
                    !(host == "googlevideo.com" || host.endsWith(".googlevideo.com"))
                ) {
                    throw SabrProtocolException("SABR redirect escaped the GoogleVideo Host")
                }
            } catch (error: IllegalArgumentException) {
                throw SabrProtocolException("Malformed SABR redirect URL", error)
            }
        }

        @JvmStatic
        private fun describeRequest(request: SabrSegmentRequest): String {
            return "itag=" + request.format.itag +
                if (request.isInitializationSegment) ":init" else ":seq=" + request.sequenceNumber
        }

        @JvmStatic
        private fun cacheKey(request: SabrSegmentRequest): String {
            return "${request.format.itag}:" + if (request.isInitializationSegment) "init" else request.sequenceNumber.toString()
        }

        @JvmStatic
        private fun cacheKey(segment: SabrMediaSegment): String {
            val header = segment.header
            return "${header.itag}:" + if (header.isInitSegment) "init" else header.sequenceNumber.toString()
        }

        @JvmStatic
        private fun getCompanionFormatStatic(
            targetFormat: YoutubeSabrFormat,
            audioFormat: YoutubeSabrFormat,
            videoFormat: YoutubeSabrFormat
        ): YoutubeSabrFormat {
            if (targetFormat.itag == audioFormat.itag) return videoFormat
            if (targetFormat.itag == videoFormat.itag) return audioFormat
            throw IllegalArgumentException("Unknown SABR itag: " + targetFormat.itag)
        }

        @JvmStatic
        private fun appendQueryParameterIfMissing(url: String, name: String, value: String): String {
            if (url.matches(Regex(".*(?:[?&])$name=[^&]*.*"))) return url
            return url + (if (url.contains("?")) '&' else '?') + "$name=$value"
        }

        @JvmStatic
        private fun readExactly(input: java.io.InputStream, length: Int): ByteArray {
            val data = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(data, offset, length - offset)
                if (read < 0) throw IOException("Truncated SABR initialization range: expected=$length, actual=$offset")
                offset += read
            }
            return data
        }

        @JvmStatic
        private fun isRecoverableIncompleteMediaResponse(integrityIssues: List<String>): Boolean {
            if (integrityIssues.isEmpty()) return false
            for (issue in integrityIssues) {
                if (!issue.startsWith("length-mismatch:") &&
                    !issue.startsWith("missing-media-end:") &&
                    !issue.startsWith("missing-media:") &&
                    !issue.startsWith("media-without-header:") &&
                    !issue.startsWith("media-end-without-header:")
                ) {
                    return false
                }
            }
            return true
        }

        @JvmStatic
        private fun elapsedMs(startNs: Long): Long {
            return maxOf(0, (System.nanoTime() - startNs) / 1_000_000L)
        }

        @JvmStatic
        private fun addBoundedTraceEvent(events: Deque<String>, value: String) {
            if (events.size >= MAX_TRACE_EVENTS) events.removeFirst()
            events.addLast(value)
        }
    }

    // not final: a server-requested reload swaps in a freshly probed info (new URL + ustreamer config)
    private var info: YoutubeSabrInfo
    private val audioFormat: YoutubeSabrFormat
    private val videoFormat: YoutubeSabrFormat
    private val streamState: YoutubeSabrStreamState
    private val poTokenProvider: SabrPoTokenProvider?
    private val segmentSpoolDirectory: File?
    private val sessionPolicyHost: SabrSessionPolicyHost

    private val segmentCache = ConcurrentHashMap<String, SabrMediaSegment>()
    private val inFlightSegments = ConcurrentHashMap<String, SabrMediaSegment>()
    private val segmentAvailable = Object()

    private var serverAbrStreamingUrl: String
    private var requestNumber: Int = 0
    private var redirectCount: Int = 0
    private var poTokenRefreshes: Int = 0
    private var reloads: Int = 0
    private var consecutiveIntegrityFailures: Int = 0

    private val cacheOrder: Deque<String> = ArrayDeque()
    private val diagnosticEvents: Deque<String> = ArrayDeque()
    private var diagnosticChars: Int = 0
    private var cachedBytes: Long = 0
    private var peakCachedBytes: Long = 0

    @Volatile private var totalResponseBytes: Long = 0
    @Volatile private var maxResponseBytes: Long = 0
    @Volatile private var maxUmpPartBytes: Long = 0
    @Volatile private var maxMediaPartPayloadBytes: Long = 0
    @Volatile private var maxSegmentBytes: Long = 0
    @Volatile private var maxSegmentsPerResponse: Int = 0
    @Volatile private var demandBackoffUntilNs: Long = 0
    @Volatile private var backoffListener: BackoffListener? = null
    @Volatile private var mediaProgressVersion: Long = 0
    @Volatile private var cacheClosed: Boolean = false
    @Volatile private var traceEnabled: Boolean = false

    private val traceLock = Object()
    private var traceResponseBytes: Long = 0
    private var traceMediaPayloadBytes: Long = 0
    private var traceControlPayloadBytes: Long = 0
    private var traceUmpOverheadBytes: Long = 0
    private var traceDiscardedBytes: Long = 0
    private var traceCurrentSegmentElapsedMs: Long = -1
    private val traceSegments: Deque<String> = ArrayDeque()
    private val traceDiscards: Deque<String> = ArrayDeque()
    private val traceResponses: Deque<String> = ArrayDeque()

    @Volatile private var playHeadMs: Long = 0

    // Primary constructor (Host injection)
    constructor(
        info: YoutubeSabrInfo,
        audioFormat: YoutubeSabrFormat,
        videoFormat: YoutubeSabrFormat,
        poTokenProvider: SabrPoTokenProvider?,
        segmentSpoolDirectory: File?,
        sessionPolicyHost: SabrSessionPolicyHost
    ) {
        if (!audioFormat.isAudio) {
            throw IllegalArgumentException("SABR audio format must be audio: itag=" + audioFormat.itag)
        }
        if (!videoFormat.isVideo) {
            throw IllegalArgumentException("SABR video format must be video: itag=" + videoFormat.itag)
        }
        if (audioFormat.itag == videoFormat.itag) {
            throw IllegalArgumentException("SABR audio/video formats must be distinct")
        }
        if (info.serverAbrStreamingUrl.isNullOrEmpty()) {
            throw IllegalArgumentException("Missing SABR streaming URL")
        }
        this.info = info
        this.audioFormat = audioFormat
        this.videoFormat = videoFormat
        this.streamState = YoutubeSabrStreamState(audioFormat, videoFormat)
        this.poTokenProvider = poTokenProvider
        this.segmentSpoolDirectory = segmentSpoolDirectory
        this.sessionPolicyHost = sessionPolicyHost
        this.serverAbrStreamingUrl = info.serverAbrStreamingUrl!!
    }

    constructor(
        info: YoutubeSabrInfo,
        audioFormat: YoutubeSabrFormat,
        videoFormat: YoutubeSabrFormat
    ) : this(info, audioFormat, videoFormat, null, null)

    constructor(
        info: YoutubeSabrInfo,
        audioFormat: YoutubeSabrFormat,
        videoFormat: YoutubeSabrFormat,
        poTokenProvider: SabrPoTokenProvider?
    ) : this(info, audioFormat, videoFormat, poTokenProvider, null)

    constructor(
        info: YoutubeSabrInfo,
        audioFormat: YoutubeSabrFormat,
        videoFormat: YoutubeSabrFormat,
        poTokenProvider: SabrPoTokenProvider?,
        segmentSpoolDirectory: File?
    ) : this(
        info,
        audioFormat,
        videoFormat,
        poTokenProvider,
        segmentSpoolDirectory,
        SabrSessionPolicyHost(
            BuiltinSabrSessionPolicy(),
            SabrSessionPolicyTranscript(512)
        )
    )

    constructor(
        info: YoutubeSabrInfo,
        audioFormat: YoutubeSabrFormat,
        videoFormat: YoutubeSabrFormat,
        poTokenProvider: SabrPoTokenProvider?,
        segmentSpoolDirectory: File?,
        policy: SabrSessionPolicy
    ) : this(
        info,
        audioFormat,
        videoFormat,
        poTokenProvider,
        segmentSpoolDirectory,
        SabrSessionPolicyHost(policy, SabrSessionPolicyTranscript(512))
    )

    // ------------------------------------------------------------------
    // Public fetch API
    // ------------------------------------------------------------------

    @Throws(IOException::class, ExtractionException::class)
    fun fetchSegment(request: SabrSegmentRequest, localization: Localization): SabrMediaSegment {
        val cached = segmentCache[cacheKey(request)]
        if (cached != null) return cached
        failIfKnownOutOfBounds(request)

        var targetPrepared = maybePrepareForDistantMediaSegment(request)
        var policyOnlyResponses = 0
        for (attempts in 0 until MAX_REQUESTS_PER_SEGMENT) {
            val result: YoutubeSabrProbeResult
            try {
                result = fetchNextResponse(localization) { seg -> ingestAndCacheSegment(seg) }
            } catch (e: SabrRecoverableException) {
                if (recoverFromStreamingMediaException(localization, e)) continue
                throw e
            }
            val decoded = result.decodedResponse
            val integrityIssues = decoded.getIntegrityIssues()
            if (integrityIssues.isNotEmpty()) {
                if (isRecoverableIncompleteMediaResponse(integrityIssues)) {
                    if (recoverFromIncompleteMediaResponse(localization, decoded)) continue
                    throw SabrProtocolException(
                        "SABR media integrity issue while fetching " + describeRequest(request) + ": " + integrityIssues
                    )
                }
                throw SabrProtocolException(
                    "SABR media integrity issue while fetching " + describeRequest(request) + ": " + integrityIssues
                )
            }
            consecutiveIntegrityFailures = 0
            val segment = segmentCache[cacheKey(request)]
            if (segment != null) return segment
            failIfKnownOutOfBounds(request)
            if (!targetPrepared) targetPrepared = maybePrepareForDistantMediaSegment(request)
            if (applyControlPolicy(
                    localization,
                    result,
                    true,
                    SabrSessionPolicy.ControlMode.FETCH_SEGMENT,
                    request
                ) == PolicyControlOutcome.RETRY
            ) {
                continue
            }
            if (decoded.isPolicyOnlyResponse()) {
                policyOnlyResponses++
                if (policyOnlyResponses >= MAX_POLICY_ONLY_RESPONSES_PER_SEGMENT) {
                    throw SabrProtocolException(
                        "SABR repeated policy-only responses while fetching " +
                            describeRequest(request) + ": " + decoded.summarizeNoMediaResponse()
                    )
                }
            } else if (result.segmentCount > 0) {
                policyOnlyResponses = 0
            }
            if (streamState.isComplete()) break
        }
        throw SabrProtocolException(
            "Requested SABR segment was not returned: itag=" + request.format.itag +
                if (request.isInitializationSegment) ":init" else ":seq=" + request.sequenceNumber
        )
    }

    @Throws(IOException::class, ExtractionException::class)
    fun fetchNextResponse(localization: Localization): YoutubeSabrProbeResult {
        return fetchNextResponse(localization, null)
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun fetchNextResponse(
        localization: Localization,
        segmentConsumer: SabrStreamingResponseReader.SegmentConsumer?
    ): YoutubeSabrProbeResult {
        return fetchNextResponseUntil(localization, if (segmentConsumer == null) null else { segment ->
            segmentConsumer.accept(segment)
            true
        })
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun fetchNextResponseUntil(
        localization: Localization,
        segmentConsumer: SabrStreamingResponseReader.StoppableSegmentConsumer?
    ): YoutubeSabrProbeResult {
        val proposedBody = if (requestNumber == 0)
            YoutubeSabrRequestBuilder.buildFirstMediaRequest(info, audioFormat, videoFormat, streamState)
        else
            YoutubeSabrRequestBuilder.buildFollowUpMediaRequest(info, audioFormat, videoFormat, streamState)

        val playerTimeMs = streamState.getRequestPlayerTimeMs()
        val bufferedEdgeMs = streamState.getMinBufferedEndMs()
        val rawPoToken = streamState.getRawPoToken()
        val poTokenBytes = rawPoToken?.size ?: -1
        val bufferedRangeCount = streamState.getBufferedRanges().size
        val requestPolicyResult = sessionPolicyHost.evaluate(
            sessionPolicyState(),
            SabrSessionPolicy.RequestEvent(playerTimeMs, bufferedEdgeMs, poTokenBytes, bufferedRangeCount, proposedBody)
        )
        val requestBody = requestPolicyResult.requestBody ?: throw IllegalStateException("Missing request body")
        addDiagnosticEvent(
            "request n=$requestNumber playerMs=$playerTimeMs edgeMs=$bufferedEdgeMs poTokenBytes=$poTokenBytes ranges=${streamState.summarizeBufferedRanges()}"
        )
        val requestStartNs = System.nanoTime()
        val timedConsumer: SabrStreamingResponseReader.StoppableSegmentConsumer? = if (segmentConsumer == null) null else { segment ->
            traceCurrentSegmentElapsedMs = elapsedMs(requestStartNs)
            try {
                segmentConsumer.accept(segment)
            } finally {
                traceCurrentSegmentElapsedMs = -1
            }
        }
        val startedConsumer = SabrStreamingResponseReader.SegmentConsumer { segment ->
            traceCurrentSegmentElapsedMs = elapsedMs(requestStartNs)
            try {
                publishInFlightSegment(segment)
            } finally {
                traceCurrentSegmentElapsedMs = -1
            }
        }
        val result: YoutubeSabrProbeResult
        try {
            result = YoutubeSabrProbe.postMediaRequest(
                info,
                requestBody,
                requestNumber,
                serverAbrStreamingUrl,
                timedConsumer,
                startedConsumer,
                segmentSpoolDirectory,
                localization,
                sessionPolicyHost.getMediaProtocol()
            )
        } catch (e: IOException) {
            addDiagnosticEvent("request_failed n=$requestNumber type=${e.javaClass.simpleName} message=${e.message}")
            throw e
        } catch (e: ExtractionException) {
            addDiagnosticEvent("request_failed n=$requestNumber type=${e.javaClass.simpleName} message=${e.message}")
            throw e
        } finally {
            if (inFlightSegments.isNotEmpty()) {
                abortInFlightSegments("SABR response ended before segment completion", null)
            }
        }
        addDiagnosticEvent(
            "response n=$requestNumber http=${result.responseCode} contentType=${result.contentType} segments=" +
                (if (result.segments.isEmpty()) "count=${result.segmentCount}" else summarizeSegments(result.segments)) +
                " decoded={${result.decodedResponse.summarizeForDiagnostics()}}"
        )
        totalResponseBytes += result.responseBytes
        recordMemoryStats(result)
        recordTraceResponse(result)
        updateBandwidthEstimate(result.responseBytes, System.nanoTime() - requestStartNs)
        requestNumber++
        sessionPolicyHost.commitAppliedState(requestPolicyResult, sessionPolicyState())
        return result
    }

    private fun updateBandwidthEstimate(responseBytes: Long, elapsedNs: Long) {
        if (responseBytes <= 0 || elapsedNs <= 0) return
        val sampleBitsPerSecond = responseBytes * 8_000_000_000L / elapsedNs
        val previous = streamState.getBandwidthEstimate()
        val estimate = if (previous <= 0) sampleBitsPerSecond else (previous * 3 + sampleBitsPerSecond) / 4
        streamState.setBandwidthEstimate(estimate)
        addDiagnosticEvent("bandwidth sampleBps=$sampleBitsPerSecond estimateBps=$estimate")
    }

    @Synchronized
    fun addDiagnosticEvent(event: String) {
        val bounded = if (event.length > MAX_DIAGNOSTIC_CHARS) event.substring(0, MAX_DIAGNOSTIC_CHARS) else event
        while (diagnosticEvents.isNotEmpty() && diagnosticChars + bounded.length > MAX_DIAGNOSTIC_CHARS) {
            diagnosticChars -= diagnosticEvents.removeFirst().length
        }
        diagnosticEvents.addLast(bounded)
        diagnosticChars += bounded.length
    }

    @Synchronized
    fun getDiagnosticTrace(): String {
        val trace = StringBuilder()
        for (ev in diagnosticEvents) {
            if (trace.isNotEmpty()) trace.append(" | ")
            trace.append(ev)
        }
        return trace.toString()
    }

    fun setBackoffListener(listener: BackoffListener?) {
        backoffListener = listener
    }

    /**
     * Server asked us to reload the player response.
     */
    @Throws(IOException::class, ExtractionException::class)
    private fun maybeReload(localization: Localization): Boolean {
        if (reloads >= MAX_RELOADS_PER_SESSION) return false
        reloads++
        val contentCountry = if (localization.getCountryCode().isEmpty()) ContentCountry.DEFAULT else ContentCountry(localization.getCountryCode())
        val fresh = YoutubeSabrProbe.fetchSabrInfo(info.videoId, info.profile, localization, contentCountry)
        if (fresh.serverAbrStreamingUrl.isNullOrEmpty()) return false
        info = fresh
        serverAbrStreamingUrl = fresh.serverAbrStreamingUrl!!
        redirectCount = 0
        return true
    }

    @Throws(IOException::class, ExtractionException::class)
    fun pumpOnce(localization: Localization): List<SabrMediaSegment> {
        val result = pumpOnceInternal(localization, false)
        return result?.segments ?: emptyList()
    }

    @Throws(IOException::class, ExtractionException::class)
    fun pumpOnceStreaming(localization: Localization): Int {
        val result = pumpOnceInternal(localization, true)
        return result?.segmentCount ?: 0
    }

    @Throws(IOException::class, ExtractionException::class)
    fun pumpOnceStreamingForStartup(localization: Localization): Int {
        val result = pumpOnceInternal(localization, true, false)
        return result?.segmentCount ?: 0
    }

    @Throws(IOException::class, ExtractionException::class)
    fun pumpOnceStreamingUntilCached(localization: Localization, target: SabrSegmentRequest): Int {
        return pumpOnceStreamingForDemand(localization, target).segmentCount
    }

    @Throws(IOException::class, ExtractionException::class)
    fun pumpOnceStreamingForDemand(localization: Localization, target: SabrSegmentRequest): DemandResponseResult {
        if (getCachedSegment(target) != null) return DemandResponseResult.NO_REQUEST
        val remaining = getDemandBackoffRemainingMs()
        if (remaining > 0) return DemandResponseResult.NO_REQUEST
        val targetTrackSegments = intArrayOf(0)
        val returnedSegments = mutableListOf<SabrSessionPolicy.DemandReturnedSegment>()
        val returnedTruncated = booleanArrayOf(false)
        val result = pumpOnceInternal(localization, { segment ->
            ingestAndCacheSegment(segment)
            val header = segment.header
            if (!header.isInitSegment && header.itag == target.format.itag) targetTrackSegments[0]++
            if (!header.isInitSegment && returnedSegments.size < SabrSessionPolicy.MAX_DEMAND_RETURNED_SEGMENTS) {
                returnedSegments.add(
                    SabrSessionPolicy.DemandReturnedSegment(header.itag, header.sequenceNumber, header.startMs, header.durationMs)
                )
            } else if (!header.isInitSegment) {
                returnedTruncated[0] = true
            }
            true
        }, false)
        return DemandResponseResult(
            result?.segmentCount ?: returnedSegments.size,
            targetTrackSegments[0],
            returnedSegments,
            returnedTruncated[0],
            true
        )
    }

    class DemandResponseResult(
        val segmentCount: Int,
        val targetTrackSegmentCount: Int,
        returnedSegments: List<SabrSessionPolicy.DemandReturnedSegment>,
        val areReturnedSegmentsTruncated: Boolean,
        val wasRequestPerformed: Boolean
    ) {
        val returnedSegments: List<SabrSessionPolicy.DemandReturnedSegment> =
            Collections.unmodifiableList(ArrayList(returnedSegments))

        fun getSegmentCount(): Int = segmentCount
        fun getTargetTrackSegmentCount(): Int = targetTrackSegmentCount
        fun getReturnedSegments(): List<SabrSessionPolicy.DemandReturnedSegment> = returnedSegments
        fun areReturnedSegmentsTruncated(): Boolean = areReturnedSegmentsTruncated
        fun wasRequestPerformed(): Boolean = wasRequestPerformed

        companion object {
            @JvmField
            val NO_REQUEST = DemandResponseResult(0, 0, emptyList(), false, false)
        }
    }

    @Throws(SabrProtocolException::class)
    fun evaluateDemandRoute(event: SabrSessionPolicy.DemandRouteEvent): SabrSessionPolicy.DemandRoute {
        return sessionPolicyHost.evaluateDemandRoute(event)
    }

    @Throws(SabrProtocolException::class)
    fun evaluateDemandResponse(event: SabrSessionPolicy.DemandResponseEvent): SabrSessionPolicy.DemandResponseDecision {
        return sessionPolicyHost.evaluateDemandResponse(event)
    }

    fun getDemandBackoffRemainingMs(): Long {
        val remainingNs = demandBackoffUntilNs - System.nanoTime()
        if (remainingNs <= 0) return 0
        return maxOf(1, TimeUnit.NANOSECONDS.toMillis(remainingNs))
    }

    fun getMediaProgressVersion(): Long = mediaProgressVersion

    @Throws(IOException::class, ExtractionException::class)
    private fun pumpOnceInternal(localization: Localization, streaming: Boolean): YoutubeSabrProbeResult? {
        return pumpOnceInternal(localization, streaming, true)
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun pumpOnceInternal(localization: Localization, streaming: Boolean, honorBackoff: Boolean): YoutubeSabrProbeResult? {
        return pumpOnceInternal(
            localization,
            if (streaming) { segment ->
                ingestAndCacheSegment(segment)
                true
            } else null,
            honorBackoff,
            false
        )
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun pumpOnceInternal(
        localization: Localization,
        segmentConsumer: SabrStreamingResponseReader.StoppableSegmentConsumer?,
        honorBackoff: Boolean
    ): YoutubeSabrProbeResult? {
        return pumpOnceInternal(localization, segmentConsumer, honorBackoff, false)
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun pumpOnceInternal(
        localization: Localization,
        segmentConsumer: SabrStreamingResponseReader.StoppableSegmentConsumer?,
        honorBackoff: Boolean,
        skipBackoffWhenBootstrapReady: Boolean
    ): YoutubeSabrProbeResult? {
        val result: YoutubeSabrProbeResult
        try {
            result = if (segmentConsumer == null) fetchNextResponse(localization)
            else fetchNextResponseUntil(localization, segmentConsumer)
        } catch (e: SabrRecoverableException) {
            if (recoverFromStreamingMediaException(localization, e)) return null
            throw e
        }
        val decoded = result.decodedResponse
        val integrityIssues = decoded.getIntegrityIssues()
        if (integrityIssues.isNotEmpty()) {
            if (isRecoverableIncompleteMediaResponse(integrityIssues)) {
                if (recoverFromIncompleteMediaResponse(localization, decoded)) return null
                throw SabrProtocolException("SABR media integrity issue: $integrityIssues")
            }
            throw SabrProtocolException("SABR media integrity issue: $integrityIssues")
        }
        consecutiveIntegrityFailures = 0
        val segments = result.segments
        if (segmentConsumer == null) {
            for (segment in segments) ingestAndCacheSegment(segment)
        }
        evictCacheIfNeeded()
        if (applyControlPolicy(
                localization,
                result,
                honorBackoff,
                SabrSessionPolicy.ControlMode.PUMP,
                null,
                skipBackoffWhenBootstrapReady
            ) == PolicyControlOutcome.RETRY
        ) {
            return null
        }
        return result
    }

    private enum class PolicyControlOutcome {
        CONTINUE, RETRY
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun applyControlPolicy(
        localization: Localization,
        result: YoutubeSabrProbeResult,
        honorBackoff: Boolean,
        mode: SabrSessionPolicy.ControlMode,
        request: SabrSegmentRequest?
    ): PolicyControlOutcome {
        return applyControlPolicy(localization, result, honorBackoff, mode, request, false)
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun applyControlPolicy(
        localization: Localization,
        result: YoutubeSabrProbeResult,
        honorBackoff: Boolean,
        mode: SabrSessionPolicy.ControlMode,
        request: SabrSegmentRequest?,
        skipBackoffWhenBootstrapReady: Boolean
    ): PolicyControlOutcome {
        val decoded = result.decodedResponse
        val policyResult = sessionPolicyHost.evaluate(
            sessionPolicyState(),
            SabrSessionPolicy.ControlResponseEvent(result.segmentCount, honorBackoff, mode, decoded)
        )
        val decision = policyResult.controlDecision ?: throw IllegalStateException("Missing control decision")
        val redirectCountBeforePolicy = redirectCount
        redirectCount = policyResult.nextState.redirectCount
        poTokenRefreshes = policyResult.nextState.poTokenRefreshes
        val executedActions = mutableListOf<SabrSessionPolicy.ActionType>()
        var completed = false
        try {
            for (action in policyResult.actions) {
                executedActions.add(action.type)
                when (action.type) {
                    SabrSessionPolicy.ActionType.APPLY_BUILTIN_RESPONSE_STATE -> streamState.ingest(decoded)
                    SabrSessionPolicy.ActionType.APPLY_RESPONSE_STATE -> streamState.ingest(policyResult.statePatch!!)
                    SabrSessionPolicy.ActionType.APPLY_REDIRECT -> {
                        if (redirectCountBeforePolicy + 1 > MAX_REDIRECTS_PER_SESSION) {
                            throw SabrProtocolException("SABR redirect limit exceeded: redirects=${redirectCountBeforePolicy + 1}")
                        }
                        val redirectUrl = decision.redirectUrl
                        if (redirectUrl.isNullOrEmpty()) {
                            throw SabrProtocolException("SABR policy requested redirect without Host URL capability")
                        }
                        validateRedirectUrl(redirectUrl)
                        serverAbrStreamingUrl = redirectUrl
                    }
                    SabrSessionPolicy.ActionType.FAIL_SABR_ERROR -> {
                        val errorDetails = decision.errorDetails ?: decoded.summarizeNoMediaResponse()
                        completed = true
                        throw SabrProtocolException(
                            if (request == null) "SABR error: $errorDetails"
                            else "SABR error while fetching ${describeRequest(request)}: $errorDetails"
                        )
                    }
                    SabrSessionPolicy.ActionType.TRY_RELOAD -> {
                        if (maybeReload(localization)) {
                            completed = true
                            return PolicyControlOutcome.RETRY
                        }
                        throw SabrProtocolException(
                            if (request == null)
                                "SABR requested player reload (reload budget spent): ${decoded.summarizeNoMediaResponse()}"
                            else
                                "SABR requested player reload while fetching ${describeRequest(request)} (reload budget spent): ${decoded.summarizeNoMediaResponse()}"
                        )
                    }
                    SabrSessionPolicy.ActionType.REFRESH_PO_TOKEN -> applyPoTokenForProtectedResponse()
                    SabrSessionPolicy.ActionType.REQUIRE_PO_TOKEN -> {
                        if (!applyPoTokenForProtectedResponse()) {
                            throw SabrProtocolException(
                                "SABR protected no-media response" +
                                    (if (request == null) "" else " while fetching ${describeRequest(request)}") +
                                    ": ${decoded.summarizeNoMediaResponse()}"
                            )
                        }
                    }
                    SabrSessionPolicy.ActionType.RESET_RECOVERY_BUDGETS -> {}
                    SabrSessionPolicy.ActionType.SLEEP_BACKOFF -> {
                        val bootstrapReady = skipBackoffWhenBootstrapReady && isBootstrapReadyForBackoff()
                        if (!skipBackoffWhenBootstrapReady || !bootstrapReady) {
                            sleepBackoff(decision.backoffTimeMs, true)
                        }
                    }
                    SabrSessionPolicy.ActionType.DEFER_BACKOFF -> {
                        val applied = minOf(decision.backoffTimeMs, MAX_DEMAND_BACKOFF_MS)
                        demandBackoffUntilNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(applied.toLong())
                        addDiagnosticEvent("defer_backoff waitTarget requestedMs=${decision.backoffTimeMs} appliedMs=$applied")
                    }
                    SabrSessionPolicy.ActionType.CLEAR_DEMAND_BACKOFF -> demandBackoffUntilNs = 0
                    SabrSessionPolicy.ActionType.RETRY -> {
                        completed = true
                        return PolicyControlOutcome.RETRY
                    }
                    SabrSessionPolicy.ActionType.CONTINUE -> {
                        completed = true
                        return PolicyControlOutcome.CONTINUE
                    }
                    else -> throw IllegalStateException("Unexpected SABR session control action: ${action.type}")
                }
            }
            throw IllegalStateException("SABR session policy returned no terminal outcome")
        } finally {
            sessionPolicyHost.commitAppliedState(policyResult, sessionPolicyState(), executedActions, completed)
        }
    }

    private fun sessionPolicyState(): SabrSessionPolicy.State {
        return SabrSessionPolicy.State(requestNumber, redirectCount, poTokenRefreshes, reloads)
    }

    private fun ingestAndCacheSegment(segment: SabrMediaSegment) {
        val key = cacheKey(segment)
        if (cacheClosed || !segment.isComplete || segment.hasFailed()) {
            inFlightSegments.remove(key, segment)
            segment.delete()
            return
        }
        streamState.ingest(segment)
        inFlightSegments.remove(key, segment)
        val previous = segmentCache.putIfAbsent(key, segment)
        if (previous != null && previous !== segment) {
            segment.delete()
            synchronized(segmentAvailable) { (segmentAvailable as Object).notifyAll() }
            return
        }
        synchronized(segmentAvailable) { (segmentAvailable as Object).notifyAll() }
        if (previous == null && !segment.header.isInitSegment) {
            cacheOrder.addLast(key)
            cachedBytes += segment.length
            peakCachedBytes = maxOf(peakCachedBytes, cachedBytes)
        }
        if (previous == null) {
            mediaProgressVersion++
            recordTraceSegment(segment)
        }
        evictCacheIfNeeded()
    }

    private fun publishInFlightSegment(segment: SabrMediaSegment) {
        if (segment.isComplete || segment.header.isInitSegment) return
        if (cacheClosed) {
            segment.failProgressive(IOException("SABR session cache is closed"))
            segment.delete()
            return
        }
        val key = cacheKey(segment)
        val previous = inFlightSegments.put(key, segment)
        if (previous != null && previous !== segment) previous.delete()
        synchronized(segmentAvailable) { (segmentAvailable as Object).notifyAll() }
        addDiagnosticEvent("segment_started itag=${segment.header.itag} seq=${segment.header.sequenceNumber} bytes=${segment.length}")
    }

    private fun abortInFlightSegments(message: String, cause: Throwable?) {
        for (segment in inFlightSegments.values) {
            val failure = IOException(message, cause)
            segment.failProgressive(failure)
            segment.delete()
        }
        inFlightSegments.clear()
        synchronized(segmentAvailable) { (segmentAvailable as Object).notifyAll() }
    }

    fun setPlayHeadMs(ms: Long) {
        this.playHeadMs = ms
    }

    fun getCachedBytes(): Long = cachedBytes

    fun getPeakCachedBytes(): Long = peakCachedBytes

    fun getTotalResponseBytes(): Long = totalResponseBytes
    fun getMaxResponseBytes(): Long = maxResponseBytes
    fun getMaxUmpPartBytes(): Long = maxUmpPartBytes
    fun getMaxMediaPartPayloadBytes(): Long = maxMediaPartPayloadBytes
    fun getMaxSegmentBytes(): Long = maxSegmentBytes
    fun getMaxSegmentsPerResponse(): Int = maxSegmentsPerResponse

    fun getMemoryDiagnosticSummary(): String {
        return "requestNumber=$requestNumber, cachedBytes=$cachedBytes, peakCachedBytes=$peakCachedBytes, totalResponseBytes=$totalResponseBytes, maxResponseBytes=$maxResponseBytes, maxUmpPartBytes=$maxUmpPartBytes, maxMediaPartPayloadBytes=$maxMediaPartPayloadBytes, maxSegmentBytes=$maxSegmentBytes, maxSegmentsPerResponse=$maxSegmentsPerResponse"
    }

    fun clearCache() {
        demandBackoffUntilNs = 0
        cacheClosed = true
        sessionPolicyHost.close()
        abortInFlightSegments("SABR session cache was cleared", null)
        for (segment in segmentCache.values) segment.delete()
        segmentCache.clear()
        cacheOrder.clear()
        cachedBytes = 0
    }

    fun evictPlayed() {
        evictCacheIfNeeded()
    }

    private fun recordMemoryStats(result: YoutubeSabrProbeResult) {
        maxResponseBytes = maxOf(maxResponseBytes, result.responseBytes)
        maxUmpPartBytes = maxOf(maxUmpPartBytes, result.maxPartBytes)
        maxMediaPartPayloadBytes = maxOf(maxMediaPartPayloadBytes, result.maxMediaPartPayloadBytes)
        maxSegmentBytes = maxOf(maxSegmentBytes, result.maxSegmentBytes)
        maxSegmentsPerResponse = maxOf(maxSegmentsPerResponse, result.segmentCount)
    }

    private fun evictCacheIfNeeded() {
        while (cachedBytes > MAX_CACHE_BYTES && cacheOrder.size > MIN_CACHED_SEGMENTS) {
            val oldKey = cacheOrder.peekFirst() ?: break
            val old = segmentCache[oldKey]
            if (old != null) {
                val endMs = old.header.startMs + old.header.durationMs
                if (endMs > playHeadMs - EVICT_BEHIND_MS) break
            }
            cacheOrder.pollFirst()
            val removed = segmentCache.remove(oldKey)
            if (removed != null) {
                cachedBytes -= removed.length
                recordTraceDiscard(removed, "cache_limit")
                removed.delete()
            }
        }
    }

    private fun evictOutsideSeekWindow(fromMs: Long) {
        val lowMs = fromMs - SEEK_KEEP_WINDOW_MS
        val highMs = fromMs + SEEK_KEEP_WINDOW_MS
        val it = cacheOrder.iterator()
        while (it.hasNext()) {
            val key = it.next()
            val seg = segmentCache[key] ?: continue
            val startMs = seg.header.startMs
            val endMs = startMs + seg.header.durationMs
            if (endMs < lowMs || startMs > highMs) {
                it.remove()
                segmentCache.remove(key)
                cachedBytes -= seg.length
                recordTraceDiscard(seg, "seek_window")
                seg.delete()
            }
        }
    }

    fun getCachedSegment(request: SabrSegmentRequest): SabrMediaSegment? = segmentCache[cacheKey(request)]

    fun getReadableSegment(request: SabrSegmentRequest): SabrMediaSegment? {
        val key = cacheKey(request)
        val complete = segmentCache[key]
        if (complete != null) return complete
        val inFlight = inFlightSegments[key]
        if (inFlight != null && inFlight.hasFailed()) {
            inFlightSegments.remove(key, inFlight)
            return null
        }
        return inFlight
    }

    @Throws(InterruptedException::class)
    fun awaitCachedSegment(request: SabrSegmentRequest, timeoutMs: Long): SabrMediaSegment? {
        var segment = getCachedSegment(request)
        if (segment != null || timeoutMs <= 0) return segment
        synchronized(segmentAvailable) {
            segment = getCachedSegment(request)
            if (segment == null) {
                (segmentAvailable as Object).wait(timeoutMs)
                segment = getCachedSegment(request)
            }
        }
        return segment
    }

    @Throws(InterruptedException::class)
    fun awaitReadableSegment(request: SabrSegmentRequest, timeoutMs: Long): SabrMediaSegment? {
        var segment = getReadableSegment(request)
        if (segment != null || timeoutMs <= 0) return segment
        synchronized(segmentAvailable) {
            segment = getReadableSegment(request)
            if (segment == null) {
                (segmentAvailable as Object).wait(timeoutMs)
                segment = getReadableSegment(request)
            }
        }
        return segment
    }

    fun discardCachedSegment(request: SabrSegmentRequest) {
        val key = cacheKey(request)
        val inFlight = inFlightSegments.remove(key)
        inFlight?.delete()
        val removed = segmentCache.remove(key)
        if (removed != null && !removed.header.isInitSegment) {
            cacheOrder.remove(key)
            cachedBytes = maxOf(0, cachedBytes - removed.length)
            recordTraceDiscard(removed, "explicit")
            removed.delete()
        }
    }

    fun setTraceEnabled(traceEnabled: Boolean) {
        this.traceEnabled = traceEnabled
    }

    fun getTraceSnapshot(): TraceSnapshot {
        synchronized(traceLock) {
            return TraceSnapshot(
                traceResponseBytes,
                traceMediaPayloadBytes,
                traceControlPayloadBytes,
                traceUmpOverheadBytes,
                traceDiscardedBytes,
                requestNumber,
                cachedBytes,
                peakCachedBytes,
                ArrayList(traceSegments),
                ArrayList(traceDiscards),
                ArrayList(traceResponses)
            )
        }
    }

    private fun recordTraceResponse(result: YoutubeSabrProbeResult) {
        if (!traceEnabled) return
        val umpOverhead = maxOf(0, result.responseBytes - result.totalPayloadBytes)
        synchronized(traceLock) {
            traceResponseBytes += result.responseBytes
            traceMediaPayloadBytes += result.mediaPayloadBytes
            traceControlPayloadBytes += result.controlPayloadBytes
            traceUmpOverheadBytes += umpOverhead
            addBoundedTraceEvent(
                traceResponses,
                "request=$requestNumber,elapsedMs=${result.requestElapsedMs},firstSegmentMs=${result.firstSegmentElapsedMs},bytes=${result.responseBytes},mediaBytes=${result.mediaPayloadBytes},segments=${result.segmentCount}"
            )
        }
    }

    private fun recordTraceSegment(segment: SabrMediaSegment) {
        if (!traceEnabled) return
        val header = segment.header
        val value = "request=$requestNumber,itag=${header.itag}" +
            (if (header.isInitSegment) ",init=true" else ",seq=${header.sequenceNumber}") +
            ",startMs=${header.startMs},durationMs=${header.durationMs},bytes=${segment.length},elapsedMs=$traceCurrentSegmentElapsedMs"
        synchronized(traceLock) {
            addBoundedTraceEvent(traceSegments, value)
        }
    }

    private fun recordTraceDiscard(segment: SabrMediaSegment, reason: String) {
        if (!traceEnabled) return
        val header = segment.header
        val bytes = segment.length
        val value = "request=$requestNumber,reason=$reason,itag=${header.itag}" +
            (if (header.isInitSegment) ",init=true" else ",seq=${header.sequenceNumber}") +
            ",startMs=${header.startMs},durationMs=${header.durationMs},bytes=$bytes"
        synchronized(traceLock) {
            traceDiscardedBytes += bytes
            addBoundedTraceEvent(traceDiscards, value)
        }
    }

    class TraceSnapshot(
        val responseBytes: Long,
        val mediaPayloadBytes: Long,
        val controlPayloadBytes: Long,
        val umpOverheadBytes: Long,
        val discardedBytes: Long,
        val requestNumber: Int,
        val cachedBytes: Long,
        val peakCachedBytes: Long,
        segments: List<String>,
        discards: List<String>,
        responses: List<String>
    ) {
        val segments: List<String> = Collections.unmodifiableList(segments)
        val discards: List<String> = Collections.unmodifiableList(discards)
        val responses: List<String> = Collections.unmodifiableList(responses)

        fun getResponseBytes(): Long = responseBytes
        fun getMediaPayloadBytes(): Long = mediaPayloadBytes
        fun getControlPayloadBytes(): Long = controlPayloadBytes
        fun getUmpOverheadBytes(): Long = umpOverheadBytes
        fun getDiscardedBytes(): Long = discardedBytes
        fun getRequestNumber(): Int = requestNumber
        fun getCachedBytes(): Long = cachedBytes
        fun getPeakCachedBytes(): Long = peakCachedBytes
        fun getSegments(): List<String> = segments
        fun getDiscards(): List<String> = discards
        fun getResponses(): List<String> = responses
    }

    fun isBeyondEnd(request: SabrSegmentRequest): Boolean {
        if (request.isInitializationSegment) return false
        val endSegment = streamState.getEndSegment(request.format)
        return endSegment > 0 && request.sequenceNumber > endSegment
    }

    fun isComplete(): Boolean = streamState.isComplete()
    fun isLive(): Boolean = streamState.isLive()
    fun getLiveHeadSequenceNumber(): Long = streamState.getLiveHeadSequenceNumber()
    fun isAtLiveEdge(): Boolean = streamState.isAtLiveEdge(audioFormat, videoFormat)

    fun getStreamState(): YoutubeSabrStreamState = streamState

    fun getRequestNumber(): Int = requestNumber

    fun getSessionPolicyTranscript(): List<String> = sessionPolicyHost.snapshotTranscript()

    fun prepareForMediaSegment(request: SabrSegmentRequest) {
        if (request.isInitializationSegment) return
        demandBackoffUntilNs = 0
        val targetFormat = request.format
        val companionFormat = getCompanionFormat(targetFormat)
        val targetStartMs = streamState.getSegmentStartMs(targetFormat, request.sequenceNumber)
        streamState.assumeBufferedUntil(targetFormat, request.sequenceNumber - 1)
        streamState.assumeBufferedUntil(companionFormat, streamState.getSegmentNumberAtOrAfterTimeMs(companionFormat, targetStartMs))
        streamState.setPlayerTimeMs(targetStartMs)
        streamState.clearPlaybackCookie()
    }

    fun prepareForInitialization(format: YoutubeSabrFormat) {
        demandBackoffUntilNs = 0
        discardCachedSegment(SabrSegmentRequest.initialization(format))
        streamState.resetInitialization(format)
        streamState.clearPlaybackCookie()
    }

    @Throws(IOException::class, ExtractionException::class)
    fun bootstrapInitialization(localization: Localization) {
        for (response in 0 until MAX_BOOTSTRAP_RESPONSES) {
            reindexCachedInitialization()
            if (hasExactBootstrapTimeline()) {
                addDiagnosticEvent("bootstrap_ready responses=$response")
                return
            }
            pumpOnceInternal(localization, { segment ->
                ingestAndCacheSegment(segment)
                !hasCachedBootstrapInitialization()
            }, true, true)
        }
        reindexCachedInitialization()
        if (hasExactBootstrapTimeline()) {
            addDiagnosticEvent("bootstrap_ready responses=$MAX_BOOTSTRAP_RESPONSES")
            return
        }
        throw SabrProtocolException(
            "SABR bootstrap did not provide exact audio/video indexes: audioInit=${getCachedSegment(SabrSegmentRequest.initialization(audioFormat)) != null}, videoInit=${getCachedSegment(SabrSegmentRequest.initialization(videoFormat)) != null}, audioEnd=${streamState.getEndSegment(audioFormat)}, videoEnd=${streamState.getEndSegment(videoFormat)}"
        )
    }

    @Throws(IOException::class)
    fun fetchInitializationData(
        format: YoutubeSabrFormat,
        localization: Localization,
        timeoutMs: Long,
        poToken: ByteArray
    ): ByteArray {
        val initializationUrl = format.initializationUrl
        val start = format.initRangeStart
        val end = format.initRangeEnd
        if (initializationUrl.isNullOrEmpty() || start < 0 || end < start || end - start >= MAX_INITIALIZATION_BYTES) {
            throw IOException("Invalid SABR initialization range: itag=${format.itag}, start=$start, end=$end")
        }
        if (poToken.isEmpty()) throw IOException("Missing PO token for SABR initialization range: itag=${format.itag}")
        val url = appendQueryParameterIfMissing(initializationUrl, "pot", Base64.getUrlEncoder().withoutPadding().encodeToString(poToken))
        val length = (end - start + 1).toInt()
        val headers = Collections.singletonMap("Range", Collections.singletonList("bytes=$start-$end"))
        try {
            NewPipe.getDownloader().getStreaming(url, headers, localization, timeoutMs).use { response ->
                if (response.responseCode() != 206 && !(response.responseCode() == 200 && start == 0L)) {
                    throw IOException("SABR initialization range failed: itag=${format.itag}, status=${response.responseCode()}")
                }
                val data = readExactly(response.body(), length)
                if (!streamState.ingestInitializationData(format, data) || !streamState.hasSegmentIndex(format)) {
                    throw IOException("SABR initialization range has no exact index: itag=${format.itag}")
                }
                addDiagnosticEvent("initialization_range itag=${format.itag} status=${response.responseCode()} bytes=${data.size}")
                return data
            }
        } catch (e: ExtractionException) {
            throw IOException("SABR initialization range failed: itag=${format.itag}", e)
        }
    }

    // overload without timeout for Java compatibility
    @Throws(IOException::class)
    fun fetchInitializationData(
        format: YoutubeSabrFormat,
        localization: Localization,
        poToken: ByteArray
    ): ByteArray {
        return fetchInitializationData(format, localization, -1, poToken)
    }

    private fun reindexCachedInitialization() {
        reindexCachedInitialization(audioFormat)
        reindexCachedInitialization(videoFormat)
    }

    private fun reindexCachedInitialization(format: YoutubeSabrFormat) {
        val segment = getCachedSegment(SabrSegmentRequest.initialization(format))
        if (segment != null) streamState.ingestInitializationData(format, segment.data)
    }

    private fun hasExactBootstrapTimeline(): Boolean =
        streamState.hasSegmentIndex(audioFormat) && streamState.hasSegmentIndex(videoFormat)

    private fun isBootstrapReadyForBackoff(): Boolean {
        reindexCachedInitialization()
        return hasExactBootstrapTimeline()
    }

    private fun hasCachedBootstrapInitialization(): Boolean =
        getCachedSegment(SabrSegmentRequest.initialization(audioFormat)) != null &&
            getCachedSegment(SabrSegmentRequest.initialization(videoFormat)) != null

    fun prepareForRewind(request: SabrSegmentRequest) {
        prepareForRewind(request, -1)
    }

    fun prepareForRewind(request: SabrSegmentRequest, seekPositionMs: Long) {
        if (request.isInitializationSegment) return
        demandBackoffUntilNs = 0
        val targetFormat = request.format
        val companionFormat = getCompanionFormat(targetFormat)
        val targetStartMs = streamState.getSegmentStartMs(targetFormat, request.sequenceNumber)
        val playbackPositionMs = if (seekPositionMs >= 0) seekPositionMs else targetStartMs
        streamState.rewindBufferedTo(targetFormat, request.sequenceNumber)
        streamState.rewindBufferedTo(companionFormat, streamState.getSegmentNumberAtOrAfterTimeMs(companionFormat, playbackPositionMs))
        streamState.setPlayerTimeMs(playbackPositionMs)
        streamState.clearPlaybackCookie()
        evictOutsideSeekWindow(playbackPositionMs)
    }

    fun prepareForForwardJump(request: SabrSegmentRequest) {
        prepareForForwardJump(request, -1)
    }

    fun prepareForForwardJump(request: SabrSegmentRequest, seekPositionMs: Long) {
        if (request.isInitializationSegment) return
        demandBackoffUntilNs = 0
        val targetFormat = request.format
        val companionFormat = getCompanionFormat(targetFormat)
        val targetStartMs = streamState.getSegmentStartMs(targetFormat, request.sequenceNumber)
        val playbackPositionMs = if (seekPositionMs >= 0) seekPositionMs else targetStartMs
        streamState.jumpBufferedTo(targetFormat, request.sequenceNumber)
        streamState.jumpBufferedTo(companionFormat, streamState.getSegmentNumberAtOrAfterTimeMs(companionFormat, playbackPositionMs))
        streamState.setPlayerTimeMs(playbackPositionMs)
        streamState.clearPlaybackCookie()
        evictOutsideSeekWindow(playbackPositionMs)
    }

    fun prepareForMissingSegment(request: SabrSegmentRequest) {
        if (request.isInitializationSegment) return
        demandBackoffUntilNs = 0
        val targetStartMs = streamState.getSegmentStartMs(request.format, request.sequenceNumber)
        streamState.jumpBufferedTo(request.format, request.sequenceNumber)
        streamState.setPlayerTimeMs(targetStartMs)
        streamState.clearPlaybackCookie()
    }

    @Throws(SabrProtocolException::class)
    private fun failIfKnownOutOfBounds(request: SabrSegmentRequest) {
        if (request.isInitializationSegment) return
        val endSegment = streamState.getEndSegment(request.format)
        if (endSegment > 0 && request.sequenceNumber > endSegment) {
            throw SabrProtocolException("Requested SABR segment is beyond end: ${describeRequest(request)}, endSegment=$endSegment")
        }
    }

    private fun maybePrepareForDistantMediaSegment(request: SabrSegmentRequest): Boolean {
        if (request.isInitializationSegment || requestNumber == 0) return false
        val format = request.format
        if (streamState.getEndSegment(format) <= 0) return false
        if (request.sequenceNumber <= streamState.getMaxSegment(format) + 1) return false
        prepareForMediaSegment(request)
        return true
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun recoverFromIncompleteMediaResponse(localization: Localization, decoded: SabrDecodedResponse): Boolean {
        return recoverFromIncompleteMediaResponse(localization, decoded.getBackoffTimeMs())
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun recoverFromStreamingMediaException(localization: Localization, error: SabrRecoverableException): Boolean {
        addDiagnosticEvent("streaming_integrity_recoverable type=${error.javaClass.simpleName} message=${error.message}")
        return recoverFromIncompleteMediaResponse(localization, -1)
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun recoverFromIncompleteMediaResponse(localization: Localization, responseBackoffMs: Int): Boolean {
        consecutiveIntegrityFailures++
        if (consecutiveIntegrityFailures >= MAX_INCOMPLETE_MEDIA_RESPONSES) return false
        if (consecutiveIntegrityFailures >= INTEGRITY_RELOAD_AFTER_FAILURES && maybeReload(localization)) return true
        val backoffMs = if (responseBackoffMs > 0) responseBackoffMs else 500 * consecutiveIntegrityFailures
        sleepBackoff(backoffMs, responseBackoffMs > 0)
        return true
    }

    @Throws(InterruptedIOException::class)
    private fun sleepBackoff(backoffTimeMs: Int, notifyListener: Boolean) {
        val ms = minOf(maxOf(0, backoffTimeMs), MAX_BACKOFF_MS)
        if (ms == 0) return
        val listener = if (notifyListener) backoffListener else null
        notifyBackoffStarted(listener, ms)
        try {
            Thread.sleep(ms.toLong())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Interrupted during SABR backoff")
        } finally {
            notifyBackoffFinished(listener)
        }
    }

    private fun notifyBackoffStarted(listener: BackoffListener?, durationMs: Int) {
        if (listener == null) return
        try {
            listener.onBackoffStarted(durationMs)
        } catch (e: RuntimeException) {
            addDiagnosticEvent("backoff_listener_start_failed type=${e.javaClass.simpleName} message=${e.message}")
        }
    }

    private fun notifyBackoffFinished(listener: BackoffListener?) {
        if (listener == null) return
        try {
            listener.onBackoffFinished()
        } catch (e: RuntimeException) {
            addDiagnosticEvent("backoff_listener_finish_failed type=${e.javaClass.simpleName} message=${e.message}")
        }
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun maybeApplyPoToken(forceRefresh: Boolean): Boolean {
        if (poTokenProvider == null) return false
        val current = streamState.getRawPoToken()
        if (current != null && current.isNotEmpty() && !forceRefresh) return false
        val poToken = poTokenProvider.getPoToken(info, streamState, forceRefresh)
        if (poToken != null && poToken.isNotEmpty() && !Arrays.equals(poToken, current)) {
            streamState.setPoToken(poToken)
            return true
        }
        return false
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun applyPoTokenForProtectedResponse(): Boolean {
        if (maybeApplyPoToken(false)) return true
        if (poTokenRefreshes < MAX_PO_TOKEN_REFRESHES) {
            poTokenRefreshes++
            return maybeApplyPoToken(true)
        }
        return false
    }

    private fun getCompanionFormat(targetFormat: YoutubeSabrFormat): YoutubeSabrFormat {
        return getCompanionFormatStatic(targetFormat, audioFormat, videoFormat)
    }
}
