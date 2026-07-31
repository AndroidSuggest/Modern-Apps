package com.vayunmathur.youpipe.util.sabr

import android.os.SystemClock
import android.util.Log
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrMediaSegment
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrRecoverableException
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSessionPolicy
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.LockSupport

internal class SabrStreamPump(
    private val session: YoutubeSabrSession,
    private val holder: SabrSessionStore.Holder,
    private val localization: Localization
) {
    enum class State {
        IDLE,
        REQUESTING,
        REPOSITIONING,
        THROTTLED,
        NETWORK_FAILED,
        TERMINAL,
        STOPPED
    }

    @Volatile private var started = false
    @Volatile private var stopped = false
    @Volatile private var clearCacheOnStop = false
    @Volatile private var state = State.IDLE
    @Volatile private var networkFailure: IOException? = null
    @Volatile private var lastReadMs: Long = 0
    @Volatile private var lastRequestMs: Long = 0
    @Volatile private var pendingRefetch: SabrSegmentRequest? = null
    @Volatile private var pendingRefetchPositionMs: Long = -1
    @Volatile private var pendingForwardSeek: SabrSegmentRequest? = null
    @Volatile private var pendingForwardSeekPositionMs: Long = -1
    private val activeDemands: MutableMap<DemandKey, SegmentDemand> = ConcurrentHashMap()
    private val demandFailures: MutableMap<DemandKey, IOException> = ConcurrentHashMap()
    @Volatile private var pendingInitialization: YoutubeSabrFormat? = null
    @Volatile private var seekModeUntilMs: Long = 0
    @Volatile private var startedAtMs: Long = 0
    private var thread: Thread? = null

    fun ensureStarted() {
        lastReadMs = System.currentTimeMillis()
        if (state == State.TERMINAL || (started && !stopped)) {
            return
        }
        synchronized(this) {
            if (state == State.TERMINAL || (started && !stopped)) {
                return
            }
            stopped = false
            started = true
            startedAtMs = System.currentTimeMillis()
            state = State.IDLE
            thread = Thread({ loop() }, "SabrStreamPump").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun stop() {
        synchronized(this) {
            stopped = true
            clearCacheOnStop = true
            val pumpThread = thread
            if (pumpThread != null && pumpThread !== Thread.currentThread()) {
                pumpThread.interrupt()
            }
        }
    }

    fun getCached(request: SabrSegmentRequest): SabrMediaSegment? {
        ensureStarted()
        return session.getCachedSegment(request)
    }

    @Synchronized
    fun takeNetworkFailure(): IOException? {
        val failure = networkFailure
        networkFailure = null
        return failure
    }

    fun takeDemandFailure(
        request: SabrSegmentRequest,
        readerOwner: Any,
        readerGeneration: Long
    ): IOException? =
        demandFailures.remove(DemandKey.from(request, readerOwner, readerGeneration))

    fun canRecover(): Boolean = state == State.IDLE || state == State.THROTTLED

    fun getStateName(): String = state.name

    fun requestRefetchFrom(request: SabrSegmentRequest) {
        activateSeekMode()
        pendingRefetch = request
        pendingRefetchPositionMs = -1
        ensureStarted()
        wake()
    }

    fun requestForwardSeekTo(request: SabrSegmentRequest) {
        activateSeekMode()
        pendingForwardSeek = request
        pendingForwardSeekPositionMs = -1
        ensureStarted()
        wake()
    }

    fun requestSegmentDemand(
        request: SabrSegmentRequest,
        readerOwner: Any,
        readerGeneration: Long
    ) {
        if (request.isInitializationSegment()) {
            requestInitialization(request.format)
            return
        }
        if (session.getCachedSegment(request) != null) {
            clearSegmentDemand(request, readerOwner, readerGeneration)
            return
        }
        val key = DemandKey.from(request, readerOwner, readerGeneration)
        if (demandFailures.containsKey(key)) {
            wake()
            return
        }
        val nowMs = System.currentTimeMillis()
        val created = SegmentDemand(request, readerOwner, readerGeneration, nowMs)
        val remainingBackoffMs = session.getDemandBackoffRemainingMs()
        if (remainingBackoffMs > 0) {
            created.pausePolicyClockForBackoff(nowMs, remainingBackoffMs)
        }
        val added = activeDemands.putIfAbsent(key, created) == null
        ensureStarted()
        if (added) {
            wake()
        }
    }

    fun clearSegmentDemand(
        request: SabrSegmentRequest,
        readerOwner: Any,
        readerGeneration: Long
    ) {
        val removed = activeDemands.remove(
            DemandKey.from(request, readerOwner, readerGeneration)
        )
        demandFailures.remove(DemandKey.from(request, readerOwner, readerGeneration))
        if (removed != null) {
            // A server backoff can park the pump for many seconds. Wake it so cancellation or a
            // superseded reader is observed immediately without permitting an early request.
            wake()
        }
    }

    @JvmOverloads
    fun requestSeekTo(
        request: SabrSegmentRequest,
        backward: Boolean,
        positionMs: Long = -1
    ) {
        activateSeekMode()
        if (backward) {
            pendingForwardSeek = null
            pendingForwardSeekPositionMs = -1
            pendingRefetch = request
            pendingRefetchPositionMs = positionMs
        } else {
            pendingRefetch = null
            pendingRefetchPositionMs = -1
            pendingForwardSeek = request
            pendingForwardSeekPositionMs = positionMs
        }
        ensureStarted()
        wake()
    }

    fun noteSeekWithinCache() {
        activateSeekMode()
        ensureStarted()
        wake()
    }

    fun requestInitialization(format: YoutubeSabrFormat) {
        pendingInitialization = format
        ensureStarted()
        wake()
    }

    private fun loop() {
        var consecutiveIoErrors = 0
        state = State.IDLE
        try {
            while (!stopped) {
                if (pendingRefetch == null && pendingForwardSeek == null &&
                    activeDemands.isEmpty() && pendingInitialization == null &&
                    (System.currentTimeMillis() - lastReadMs > IDLE_STOP_MS ||
                        session.isComplete())
                ) {
                    break
                }
                try {
                    val readerHeadMs = holder.getReaderHeadMs()
                    val backBufferMs = if (session.getCachedBytes() > MAX_AHEAD_BYTES) {
                        MIN_BACK_BUFFER_MS
                    } else {
                        targetBackBufferMs()
                    }
                    session.setPlayHeadMs(maxOf(0, holder.getReaderTailMs() - backBufferMs))
                    session.evictPlayed()
                    val edgeMs = session.getStreamState().getMinBufferedEndMs()
                    val remainingBackoffMs = session.getDemandBackoffRemainingMs()
                    if (remainingBackoffMs > 0) {
                        state = State.IDLE
                        awaitWake(remainingBackoffMs)
                        continue
                    }
                    val initialization = pendingInitialization
                    if (initialization != null) {
                        pendingInitialization = null
                        state = State.REPOSITIONING
                        session.addDiagnosticEvent(
                            "pump_initialization itag=${initialization.itag}"
                        )
                        prepareInitialRequestPosition()
                        session.prepareForInitialization(initialization)
                        pumpOnceStreaming()
                        state = State.IDLE
                        consecutiveIoErrors = 0
                        continue
                    }
                    val refetch = pendingRefetch
                    if (refetch != null) {
                        val refetchPositionMs = pendingRefetchPositionMs
                        pendingRefetch = null
                        pendingRefetchPositionMs = -1
                        state = State.REPOSITIONING
                        session.addDiagnosticEvent(
                            "pump_rewind itag=${refetch.format.itag}" +
                                " seq=${refetch.getSequenceNumber()}"
                        )
                        if (refetchPositionMs >= 0) {
                            session.prepareForRewind(refetch, refetchPositionMs)
                        } else {
                            session.prepareForRewind(refetch)
                        }
                        pumpOnceStreaming()
                        state = State.IDLE
                        consecutiveIoErrors = 0
                        continue
                    }
                    val forwardSeek = pendingForwardSeek
                    if (forwardSeek != null) {
                        val forwardSeekPositionMs = pendingForwardSeekPositionMs
                        pendingForwardSeek = null
                        pendingForwardSeekPositionMs = -1
                        if (isSeekTargetCached(forwardSeek, forwardSeekPositionMs)) {
                            session.addDiagnosticEvent(
                                "pump_forward_cached itag=${forwardSeek.format.itag}" +
                                    " seq=${forwardSeek.getSequenceNumber()}" +
                                    " positionMs=$forwardSeekPositionMs"
                            )
                            state = State.IDLE
                            continue
                        }
                        state = State.REPOSITIONING
                        session.addDiagnosticEvent(
                            "pump_forward itag=${forwardSeek.format.itag}" +
                                " init=${forwardSeek.isInitializationSegment()}" +
                                " seq=${forwardSeek.getSequenceNumber()}"
                        )
                        if (forwardSeekPositionMs >= 0) {
                            session.prepareForForwardJump(forwardSeek, forwardSeekPositionMs)
                        } else {
                            session.prepareForForwardJump(forwardSeek)
                        }
                        pumpOnceStreaming()
                        state = State.IDLE
                        consecutiveIoErrors = 0
                        continue
                    }
                    val demand = selectDemand(edgeMs)
                    if (demand != null) {
                        if (session.getCachedSegment(demand.request) != null) {
                            clearDemand(demand)
                        } else {
                            val demandStartMs = session.getStreamState().getSegmentStartMs(
                                demand.request.format, demand.request.getSequenceNumber()
                            )
                            val route = session.evaluateDemandRoute(
                                demand.routeEvent(
                                    demandStartMs, edgeMs, System.currentTimeMillis()
                                )
                            )
                            if (route == SabrSessionPolicy.DemandRoute.RECOVER_REWIND ||
                                route == SabrSessionPolicy.DemandRoute.RECOVER_FORWARD ||
                                route == SabrSessionPolicy.DemandRoute.RECOVER_MISSING
                            ) {
                                state = State.REPOSITIONING
                                demand.recoveryCount++
                                session.addDiagnosticEvent(
                                    "pump_demand_reposition itag=${demand.request.format.itag}" +
                                        " seq=${demand.request.getSequenceNumber()}" +
                                        " startMs=$demandStartMs" +
                                        " edgeMs=$edgeMs" +
                                        " omissions=${demand.responsesWithoutDemandedSegment}" +
                                        " recovery=${demand.recoveryCount}" +
                                        " route=$route"
                                )
                                when (route) {
                                    SabrSessionPolicy.DemandRoute.RECOVER_REWIND ->
                                        session.prepareForRewind(demand.request)
                                    SabrSessionPolicy.DemandRoute.RECOVER_FORWARD ->
                                        session.prepareForForwardJump(demand.request)
                                    else -> session.prepareForMissingSegment(demand.request)
                                }
                                val result = pumpOnceStreamingUntilCached(demand.request)
                                val demandCompleted = finishDemandAttempt(demand, result)
                                state = State.IDLE
                                consecutiveIoErrors = 0
                                if (!demandCompleted) {
                                    awaitDemandRetry(demand)
                                }
                                continue
                            } else if (route == SabrSessionPolicy.DemandRoute.REWIND) {
                                state = State.REPOSITIONING
                                session.addDiagnosticEvent(
                                    "pump_demand_rewind itag=${demand.request.format.itag}" +
                                        " seq=${demand.request.getSequenceNumber()}" +
                                        " startMs=$demandStartMs edgeMs=$edgeMs"
                                )
                                session.prepareForRewind(demand.request)
                                val result = pumpOnceStreamingUntilCached(demand.request)
                                val demandCompleted = finishDemandAttempt(demand, result)
                                state = State.IDLE
                                consecutiveIoErrors = 0
                                if (!demandCompleted) {
                                    awaitDemandRetry(demand)
                                }
                                continue
                            } else if (route == SabrSessionPolicy.DemandRoute.FORWARD) {
                                state = State.REPOSITIONING
                                session.addDiagnosticEvent(
                                    "pump_demand_forward itag=${demand.request.format.itag}" +
                                        " seq=${demand.request.getSequenceNumber()}" +
                                        " startMs=$demandStartMs edgeMs=$edgeMs"
                                )
                                session.prepareForForwardJump(demand.request)
                                val result = pumpOnceStreamingUntilCached(demand.request)
                                val demandCompleted = finishDemandAttempt(demand, result)
                                state = State.IDLE
                                consecutiveIoErrors = 0
                                if (!demandCompleted) {
                                    awaitDemandRetry(demand)
                                }
                                continue
                            } else if (route == SabrSessionPolicy.DemandRoute.STREAM) {
                                state = State.REQUESTING
                                session.addDiagnosticEvent(
                                    "pump_demand itag=${demand.request.format.itag}" +
                                        " seq=${demand.request.getSequenceNumber()}" +
                                        " startMs=$demandStartMs" +
                                        " edgeMs=$edgeMs" +
                                        " sinceMs=${
                                            maxOf(
                                                0,
                                                System.currentTimeMillis() - demand.createdAtMs
                                            )
                                        }"
                                )
                                val playerTime = holder.getPlayerTimeMs()
                                val requestPlayerTimeMs =
                                    cappedServerAheadPlayerTimeMs(playerTime, edgeMs)
                                session.getStreamState().setPlayerTimeMs(requestPlayerTimeMs)
                                val result = pumpOnceStreamingUntilCached(demand.request)
                                val demandCompleted = finishDemandAttempt(demand, result)
                                state = State.IDLE
                                consecutiveIoErrors = 0
                                if (!demandCompleted) {
                                    awaitDemandRetry(demand)
                                }
                                continue
                            }
                            throw IllegalStateException("Unhandled SABR demand route $route")
                        }
                    }
                    val readaheadCushionMs = targetReadaheadCushionMs()
                    val playerTimeMs = holder.getPlayerTimeMs()
                    val aheadMs = maxOf(0, edgeMs - playerTimeMs)
                    val heartbeatDue = isHeartbeatDue()
                    val throttled = (aheadMs >= readaheadCushionMs && !heartbeatDue) ||
                        session.getCachedBytes() > MAX_AHEAD_BYTES
                    if (throttled) {
                        if (state != State.THROTTLED) {
                            session.addDiagnosticEvent(
                                "pump_throttled cushionMs=$readaheadCushionMs" +
                                    " unstartedReader=${holder.hasUnstartedActiveReader()}" +
                                    " edgeMs=$edgeMs" +
                                    " playerTimeMs=$playerTimeMs" +
                                    " aheadMs=$aheadMs" +
                                    " readerHeadMs=$readerHeadMs" +
                                    " readerTailMs=${holder.getReaderTailMs()}" +
                                    " cachedBytes=${session.getCachedBytes()}" +
                                    " requestNumber=${session.getRequestNumber()}"
                            )
                        }
                        state = State.THROTTLED
                        awaitWake(IDLE_POLL_MS)
                        continue
                    }
                    val startupWait = holder.hasUnstartedActiveReader()
                    val startupBackoffMs =
                        if (startupWait) session.getDemandBackoffRemainingMs() else 0
                    if (startupBackoffMs > 0) {
                        SabrBackoffCoordinator.getInstance().begin(
                            holder.getApplicationContext(), holder,
                            SystemClock.elapsedRealtime() + startupBackoffMs
                        )
                        awaitWake(maxOf(startupBackoffMs, IDLE_POLL_MS))
                        continue
                    }
                    state = State.REQUESTING
                    val requestPlayerTimeMs = startupRequestPlayerTimeMs(playerTimeMs, edgeMs)
                    session.getStreamState().setPlayerTimeMs(requestPlayerTimeMs)
                    val segmentCount = if (holder.hasUnstartedActiveReader()) {
                        pumpOnceStreamingForStartup()
                    } else {
                        pumpOnceStreaming()
                    }
                    state = State.IDLE
                    consecutiveIoErrors = 0
                    if (segmentCount == 0) {
                        awaitWake(IDLE_POLL_MS)
                    }
                } catch (e: IOException) {
                    if (stopped || holder.isInvalidated()) {
                        session.addDiagnosticEvent(
                            "pump_canceled invalidated=${holder.isInvalidated()}" +
                                " message=${e.message}"
                        )
                        break
                    }
                    if (isInterruptedRead(e)) {
                        networkFailure = e
                        state = State.NETWORK_FAILED
                        break
                    }
                    consecutiveIoErrors++
                    if (consecutiveIoErrors >= MAX_CONSECUTIVE_IO_ERRORS) {
                        Log.w(TAG, "SABR pump network failure ${holder.videoId}", e)
                        networkFailure = e
                        state = State.NETWORK_FAILED
                        break
                    }
                    sleepQuietly(ERROR_RETRY_MS)
                } catch (e: SabrRecoverableException) {
                    Log.i(TAG, "SABR media failure: ${e.message}")
                    state = State.TERMINAL
                    holder.failTerminal(SabrLogicException("SABR media failure", e))
                    break
                } catch (e: ExtractionException) {
                    if (Thread.currentThread().isInterrupted || holder.isInvalidated()) {
                        Log.i(
                            TAG,
                            "SABR pump canceled video=${holder.videoId}" +
                                " invalidated=${holder.isInvalidated()} message=${e.message}"
                        )
                        holder.session.addDiagnosticEvent(
                            "pump_canceled invalidated=${holder.isInvalidated()}" +
                                " message=${e.message}"
                        )
                        break
                    }
                    Log.i(TAG, "SABR pump fatal: ${e.message}")
                    state = State.TERMINAL
                    holder.failTerminal(SabrLogicException("SABR logic failure", e))
                    break
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "SABR pump OOM; evicting session ${holder.videoId}", e)
                    state = State.TERMINAL
                    holder.failTerminal(SabrLogicException("SABR memory failure", e))
                    break
                } catch (e: Exception) {
                    // OkHttp's Kotlin internals can propagate a checked InterruptedException via
                    // a sneaky throw while an in-flight connect is canceled. Java does not include
                    // it in the declared downloader signature, so handle it at the pump boundary.
                    if (stopped || holder.isInvalidated() ||
                        Thread.currentThread().isInterrupted
                    ) {
                        Log.i(
                            TAG,
                            "SABR pump canceled video=${holder.videoId}" +
                                " invalidated=${holder.isInvalidated()}" +
                                " type=${e.javaClass.simpleName}"
                        )
                        break
                    }
                    Log.e(TAG, "SABR pump unexpected failure ${holder.videoId}", e)
                    state = State.TERMINAL
                    holder.failTerminal(SabrLogicException("SABR unexpected pump failure", e))
                    break
                }
            }
        } finally {
            if (clearCacheOnStop) {
                session.clearCache()
            }
            synchronized(this) {
                stopped = true
                if (state != State.TERMINAL && state != State.NETWORK_FAILED) {
                    state = State.STOPPED
                }
            }
        }
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun pumpOnceStreaming(): Int {
        try {
            val segmentCount = session.pumpOnceStreaming(localization)
            holder.recordDiagnosticsThrottled("pump segments=$segmentCount")
            return segmentCount
        } finally {
            lastRequestMs = System.currentTimeMillis()
        }
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun pumpOnceStreamingForStartup(): Int {
        try {
            val segmentCount = session.pumpOnceStreamingForStartup(localization)
            val remainingBackoffMs = session.getDemandBackoffRemainingMs()
            if (remainingBackoffMs > 0) {
                SabrBackoffCoordinator.getInstance().begin(
                    holder.getApplicationContext(), holder,
                    SystemClock.elapsedRealtime() + remainingBackoffMs
                )
            }
            holder.recordDiagnosticsThrottled("pump_startup segments=$segmentCount")
            return segmentCount
        } finally {
            lastRequestMs = System.currentTimeMillis()
        }
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun pumpOnceStreamingUntilCached(
        request: SabrSegmentRequest
    ): YoutubeSabrSession.DemandResponseResult {
        val result: YoutubeSabrSession.DemandResponseResult
        try {
            result = session.pumpOnceStreamingForDemand(localization, request)
            val remainingBackoffMs = session.getDemandBackoffRemainingMs()
            if (remainingBackoffMs > 0) {
                pauseDemandPolicyClocksForBackoff(remainingBackoffMs)
            }
            holder.recordDiagnosticsThrottled(
                "pump_until_cached itag=${request.format.itag}" +
                    " seq=${request.getSequenceNumber()}" +
                    " segments=${result.getSegmentCount()}" +
                    " targetTrackSegments=${result.getTargetTrackSegmentCount()}"
            )
        } finally {
            lastRequestMs = System.currentTimeMillis()
        }
        return result
    }

    private fun awaitDemandRetry(demand: SegmentDemand) {
        val remainingBackoffMs = session.getDemandBackoffRemainingMs()
        if (remainingBackoffMs > 0L) {
            SabrBackoffCoordinator.getInstance().begin(
                holder.getApplicationContext(), holder,
                SystemClock.elapsedRealtime() + remainingBackoffMs
            )
        }
        awaitWake(
            maxOf(
                remainingBackoffMs,
                if (demand.retryDelayMs > 0) demand.retryDelayMs.toLong() else IDLE_POLL_MS
            )
        )
    }

    private fun pauseDemandPolicyClocksForBackoff(remainingBackoffMs: Long) {
        val nowMs = System.currentTimeMillis()
        for (activeDemand in activeDemands.values) {
            activeDemand.pausePolicyClockForBackoff(nowMs, remainingBackoffMs)
        }
    }

    private fun targetReadaheadCushionMs(): Long {
        if (isSeekMode()) {
            return SEEK_READAHEAD_CUSHION_MS
        }
        if (isStartupBurst()) {
            return STARTUP_BURST_READAHEAD_CUSHION_MS
        }
        if (holder.hasUnstartedActiveReader()) {
            return STARTUP_READAHEAD_CUSHION_MS
        }
        val policy = session.getStreamState().getNextRequestPolicy() ?: return READAHEAD_CUSHION_MS
        val serverTargetMs = maxOf(
            policy.targetAudioReadaheadMs, policy.targetVideoReadaheadMs
        )
        if (serverTargetMs <= 0) {
            return READAHEAD_CUSHION_MS
        }
        return maxOf(
            MIN_SERVER_READAHEAD_CUSHION_MS,
            minOf(READAHEAD_CUSHION_MS, serverTargetMs.toLong())
        )
    }

    private fun startupRequestPlayerTimeMs(playerTimeMs: Long, edgeMs: Long): Long {
        if (!isStartupBurst()) {
            return playerTimeMs
        }
        return cappedServerAheadPlayerTimeMs(playerTimeMs, edgeMs)
    }

    private fun cappedServerAheadPlayerTimeMs(playerTimeMs: Long, edgeMs: Long): Long =
        maxOf(playerTimeMs, edgeMs - STARTUP_BURST_SERVER_AHEAD_MS)

    private fun isStartupBurst(): Boolean =
        startedAtMs > 0 && System.currentTimeMillis() - startedAtMs < STARTUP_BURST_MS

    private fun isHeartbeatDue(): Boolean {
        val policy = session.getStreamState().getNextRequestPolicy()
        val maximumMs = policy?.maxTimeSinceLastRequestMs ?: -1
        return maximumMs > 0 && lastRequestMs > 0 &&
            System.currentTimeMillis() - lastRequestMs >= maximumMs
    }

    private fun targetBackBufferMs(): Long {
        if (isSeekMode()) {
            return MIN_BACK_BUFFER_MS
        }
        val bitsPerSec = holder.videoFormat.bitrate.toLong() +
            maxOf(0, holder.audioFormat.bitrate)
        if (bitsPerSec <= 0) {
            return BACK_BUFFER_MS
        }
        val bytesPerMs = maxOf(1, bitsPerSec / 8 / 1000)
        return maxOf(MIN_BACK_BUFFER_MS, minOf(BACK_BUFFER_MS, BACK_BUFFER_BYTES / bytesPerMs))
    }

    private fun activateSeekMode() {
        seekModeUntilMs = System.currentTimeMillis() + SEEK_MODE_MS
    }

    private fun prepareInitialRequestPosition() {
        if (session.getRequestNumber() != 0) {
            return
        }
        val playerTimeMs = holder.getPlayerTimeMs()
        if (playerTimeMs <= 1_000) {
            return
        }
        session.addDiagnosticEvent(
            "pump_initialization_target itag=${holder.videoFormat.itag}" +
                " playerTimeMs=$playerTimeMs"
        )
        session.getStreamState().setPlayerTimeMs(playerTimeMs)
        session.getStreamState().setSelectVideoFormatBeforeAudio(true)
    }

    private fun isSeekMode(): Boolean = System.currentTimeMillis() < seekModeUntilMs

    private fun isSeekTargetCached(request: SabrSegmentRequest, positionMs: Long): Boolean {
        if (session.getCachedSegment(request) == null) {
            return false
        }
        if (request.isInitializationSegment()) {
            return true
        }
        val targetFormat = request.format
        val companionFormat: YoutubeSabrFormat = when (targetFormat.itag) {
            holder.videoFormat.itag -> holder.audioFormat
            holder.audioFormat.itag -> holder.videoFormat
            else -> return true
        }
        val companionTimeMs = if (positionMs >= 0) {
            positionMs
        } else {
            session.getStreamState()
                .getSegmentStartMs(targetFormat, request.getSequenceNumber())
        }
        val companionSequence = session.getStreamState()
            .getSegmentNumberAtOrAfterTimeMs(companionFormat, companionTimeMs)
        return session.getCachedSegment(
            SabrSegmentRequest.media(companionFormat, companionSequence)
        ) != null
    }

    private fun selectDemand(edgeMs: Long): SegmentDemand? {
        var selected: SegmentDemand? = null
        var selectedStartMs = Long.MAX_VALUE
        for (demand in activeDemands.values) {
            if (!holder.isReaderGenerationActive(demand.readerOwner, demand.readerGeneration) ||
                session.getCachedSegment(demand.request) != null
            ) {
                clearDemand(demand)
                continue
            }
            val startMs = session.getStreamState().getSegmentStartMs(
                demand.request.format, demand.request.getSequenceNumber()
            )
            val current = selected
            if (current == null || startMs < selectedStartMs ||
                (startMs == selectedStartMs && demand.createdAtMs < current.createdAtMs)
            ) {
                selected = demand
                selectedStartMs = startMs
            }
        }
        return selected
    }

    private fun clearDemand(demand: SegmentDemand) {
        activeDemands.remove(
            DemandKey.from(demand.request, demand.readerOwner, demand.readerGeneration)
        )
        if (activeDemands.isEmpty()) {
            SabrBackoffCoordinator.getInstance().clear(holder.getApplicationContext(), holder)
        }
    }

    @Throws(ExtractionException::class)
    private fun finishDemandAttempt(
        demand: SegmentDemand,
        result: YoutubeSabrSession.DemandResponseResult
    ): Boolean {
        if (!isDemandActive(demand)) {
            return true
        }
        if (!result.wasRequestPerformed()) {
            demand.retryDelayMs = 0
            return false
        }
        if (session.getCachedSegment(demand.request) != null) {
            clearDemand(demand)
            return true
        }
        // A control-only response is pacing/protocol state, not evidence that the server omitted
        // a demanded media segment. The ordinary response policy has already handled it; keeping
        // the demand counters unchanged also preserves the server backoff.
        if (result.getSegmentCount() == 0 && result.getReturnedSegments().isEmpty()) {
            demand.retryDelayMs = 0
            session.addDiagnosticEvent(
                "pump_demand_no_media itag=${demand.request.format.itag}" +
                    " seq=${demand.request.getSequenceNumber()}" +
                    " backoffMs=${session.getDemandBackoffRemainingMs()}"
            )
            return false
        }
        val nowMs = System.currentTimeMillis()
        demand.responsesWithoutDemandedSegment++
        val targetStartMs = session.getStreamState().getSegmentStartMs(
            demand.request.format, demand.request.getSequenceNumber()
        )
        val edgeMs = session.getStreamState().getMinBufferedEndMs()
        val decision = session.evaluateDemandResponse(
            SabrSessionPolicy.DemandResponseEvent(
                demand.request.format.itag,
                demand.request.getSequenceNumber(), targetStartMs, edgeMs,
                demand.policyState(nowMs), result.getSegmentCount(),
                result.getTargetTrackSegmentCount(), result.getReturnedSegments(),
                result.areReturnedSegmentsTruncated()
            )
        )
        demand.retryDelayMs = decision.getRetryDelayMs()
        val elapsedMs = demand.getPolicyElapsedMs(nowMs)
        session.addDiagnosticEvent(
            "pump_demand_omission itag=${demand.request.format.itag}" +
                " seq=${demand.request.getSequenceNumber()}" +
                " omissions=${demand.responsesWithoutDemandedSegment}" +
                " targetTrackSegments=${result.getTargetTrackSegmentCount()}" +
                " segments=${result.getSegmentCount()}" +
                " returned=${summarizeReturnedSegments(result)}" +
                " elapsedMs=$elapsedMs" +
                " outcome=${decision.getOutcome()}" +
                " retryDelayMs=${decision.getRetryDelayMs()}"
        )
        if (decision.getOutcome() ==
            SabrSessionPolicy.DemandOutcome.FAIL_REPEATED_TARGET_OMISSION
        ) {
            failDemand(
                demand,
                IOException(
                    "SABR response repeatedly omitted demanded segment" +
                        " itag=${demand.request.format.itag}" +
                        ", seq=${demand.request.getSequenceNumber()}" +
                        ", responses=${demand.responsesWithoutDemandedSegment}" +
                        ", elapsedMs=$elapsedMs"
                )
            )
            return true
        }
        if (decision.getOutcome() == SabrSessionPolicy.DemandOutcome.FAIL_NO_TARGET_MEDIA) {
            failDemand(
                demand,
                IOException(
                    "SABR demand timed out without target-track media" +
                        " itag=${demand.request.format.itag}" +
                        ", seq=${demand.request.getSequenceNumber()}" +
                        ", elapsedMs=$elapsedMs"
                )
            )
            return true
        }
        if (decision.getOutcome() != SabrSessionPolicy.DemandOutcome.CONTINUE) {
            throw IllegalStateException(
                "Unhandled SABR demand outcome ${decision.getOutcome()}"
            )
        }
        return false
    }

    private fun isDemandActive(demand: SegmentDemand): Boolean {
        val key = DemandKey.from(demand.request, demand.readerOwner, demand.readerGeneration)
        return activeDemands[key] === demand &&
            holder.isReaderGenerationActive(demand.readerOwner, demand.readerGeneration)
    }

    private fun failDemand(demand: SegmentDemand, failure: IOException) {
        val key = DemandKey.from(demand.request, demand.readerOwner, demand.readerGeneration)
        if (activeDemands.remove(key, demand)) {
            demandFailures[key] = failure
            session.addDiagnosticEvent(
                "pump_demand_failed itag=${demand.request.format.itag}" +
                    " seq=${demand.request.getSequenceNumber()}" +
                    " message=${failure.message}"
            )
        }
    }

    private class SegmentDemand(
        val request: SabrSegmentRequest,
        val readerOwner: Any,
        val readerGeneration: Long,
        val createdAtMs: Long
    ) {
        var responsesWithoutDemandedSegment = 0
        var recoveryCount = 0
        var retryDelayMs = 0
        private var policyCreatedAtMs: Long = createdAtMs
        private var policyBackoffUntilMs: Long = 0

        fun policyState(nowMs: Long): SabrSessionPolicy.DemandState =
            SabrSessionPolicy.DemandState(
                policyCreatedAtMs, nowMs, responsesWithoutDemandedSegment, recoveryCount
            )

        fun pausePolicyClockForBackoff(nowMs: Long, remainingBackoffMs: Long) {
            val backoffUntilMs = nowMs + remainingBackoffMs
            val unaccountedBackoffMs = backoffUntilMs - maxOf(nowMs, policyBackoffUntilMs)
            if (unaccountedBackoffMs > 0) {
                policyCreatedAtMs += unaccountedBackoffMs
                policyBackoffUntilMs = backoffUntilMs
            }
        }

        fun getPolicyElapsedMs(nowMs: Long): Long = maxOf(0, nowMs - policyCreatedAtMs)

        fun routeEvent(
            targetStartMs: Long,
            bufferedEdgeMs: Long,
            nowMs: Long
        ): SabrSessionPolicy.DemandRouteEvent =
            SabrSessionPolicy.DemandRouteEvent(
                request.format.itag, request.getSequenceNumber(), targetStartMs, bufferedEdgeMs,
                policyState(nowMs)
            )
    }

    private class DemandKey(
        private val itag: Int,
        private val sequenceNumber: Int,
        private val readerOwner: Any,
        private val readerGeneration: Long
    ) {
        private val ownerHash: Int = System.identityHashCode(readerOwner)

        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other !is DemandKey) {
                return false
            }
            return itag == other.itag &&
                sequenceNumber == other.sequenceNumber &&
                readerOwner === other.readerOwner &&
                readerGeneration == other.readerGeneration
        }

        override fun hashCode(): Int {
            var result = itag
            result = 31 * result + sequenceNumber
            result = 31 * result + ownerHash
            result = 31 * result + (readerGeneration xor (readerGeneration ushr 32)).toInt()
            return result
        }

        companion object {
            fun from(
                request: SabrSegmentRequest,
                readerOwner: Any,
                readerGeneration: Long
            ): DemandKey = DemandKey(
                request.format.itag, request.getSequenceNumber(), readerOwner, readerGeneration
            )
        }
    }

    private fun wake() {
        thread?.let { LockSupport.unpark(it) }
    }

    private fun awaitWake(timeoutMs: Long) {
        LockSupport.parkNanos(timeoutMs * 1_000_000L)
    }

    private companion object {
        private const val TAG = "SabrStreamPump"
        private const val IDLE_POLL_MS = 100L // server paced us / nothing new this round
        private const val ERROR_RETRY_MS = 1000L // transient network error
        private const val MAX_CONSECUTIVE_IO_ERRORS = 5

        // Must stay above the readahead cushion because Media3 stops reading while its buffer is
        // full.
        private const val IDLE_STOP_MS = 90_000L
        private const val READAHEAD_CUSHION_MS = 10_000L
        private const val STARTUP_READAHEAD_CUSHION_MS = 6_000L
        private const val STARTUP_BURST_READAHEAD_CUSHION_MS = 25_000L

        // Startup bursts need to fill enough media for exact seeks, but YouTube SABR starts
        // returning policy-only responses when the reported server-side readahead gets too large.
        // Cap only the request-time player timestamp so local throttling and eviction still use
        // the actual playhead.
        private const val STARTUP_BURST_SERVER_AHEAD_MS = 16_000L
        private const val STARTUP_BURST_MS = 25_000L
        private const val SEEK_READAHEAD_CUSHION_MS = 5_000L
        private const val SEEK_MODE_MS = 8_000L
        private const val MIN_SERVER_READAHEAD_CUSHION_MS = 3_000L

        // Use the session's cache ceiling as the single source of truth. A lower pump threshold
        // leaves a byte range where the pump is throttled but the session cannot evict, forcing
        // demand-time fetches.
        private val MAX_AHEAD_BYTES = YoutubeSabrSession.getMaxCacheBytes()

        // Keep a short rewind cushion in cache; deeper rewinds are refetched by repositioning the
        // session.
        private const val BACK_BUFFER_MS = 12_000L

        // Shrink the back-buffer when over budget so eviction can free enough data to keep
        // fetching.
        private const val MIN_BACK_BUFFER_MS = 2_000L
        private const val BACK_BUFFER_BYTES = 4L * 1024 * 1024

        private fun summarizeReturnedSegments(
            result: YoutubeSabrSession.DemandResponseResult
        ): String {
            val summary = StringBuilder("[")
            for (segment in result.getReturnedSegments()) {
                if (summary.length > 1) {
                    summary.append(',')
                }
                summary.append(segment.getItag()).append(':').append(segment.getSequenceNumber())
            }
            if (result.areReturnedSegmentsTruncated()) {
                summary.append(",...")
            }
            return summary.append(']').toString()
        }

        private fun sleepQuietly(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        private fun isInterruptedRead(error: IOException): Boolean {
            if (error !is InterruptedIOException) {
                return false
            }
            val message = error.message
            return Thread.currentThread().isInterrupted ||
                (message != null && message.startsWith("Interrupted"))
        }
    }
}
