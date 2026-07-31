package com.vayunmathur.youpipe.util.sabr

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.vayunmathur.youpipe.YouPipeApplication
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrPoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrClientProfile
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrProbe
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrStreamState
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.Collections
import java.util.Deque
import java.util.IdentityHashMap
import java.util.Objects
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

object SabrSessionStore {

    private const val TAG = "SabrSessionStore"

    private val SESSIONS: MutableMap<SessionKey, Holder> = ConcurrentHashMap()
    private val PREFERRED_AUDIO: MutableMap<String, String> = ConcurrentHashMap()

    // Extractor-derived SABR metadata handed off from the ViewModel to the PlaybackService by
    // video id. Populated when the loaded streams are SABR; consumed by createSourceSpec.
    private val EXTRACTOR_INFO: MutableMap<String, YoutubeSabrInfo> = ConcurrentHashMap()

    // Active MediaPeriods own leases. MediaSources outside the playback window are lightweight and
    // therefore do not prevent old sessions from being trimmed.
    // Mutated only under the class lock.
    private const val MAX_SESSIONS = 3
    private const val MAX_BOOTSTRAP_CACHE_ENTRIES = 32
    private val ORDER: Deque<SessionKey> = ArrayDeque()

    private val BOOTSTRAP_EXECUTOR: ExecutorService = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "SabrNativeBootstrap").apply { isDaemon = true }
    }
    private val INITIALIZATION_EXECUTOR: ExecutorService =
        Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "SabrAdaptiveInitialization").apply { isDaemon = true }
        }
    private val TOKEN_EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SabrTokenPrewarm").apply { isDaemon = true }
    }

    private val BOOTSTRAP_IN_FLIGHT: MutableMap<String, Future<BootstrapResult>> =
        ConcurrentHashMap()
    private val BOOTSTRAP_BACKOFFS: MutableMap<String, BootstrapBackoffState> = ConcurrentHashMap()
    private val BOOTSTRAP_CACHE: MutableMap<String, BootstrapResult> = Collections.synchronizedMap(
        object : LinkedHashMap<String, BootstrapResult>(
            MAX_BOOTSTRAP_CACHE_ENTRIES + 1, 0.75f, true
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, BootstrapResult>
            ): Boolean {
                if (size > MAX_BOOTSTRAP_CACHE_ENTRIES) {
                    eldest.value.discardPreparedSession()
                    return true
                }
                return false
            }
        }
    )
    private val TOKEN_IN_FLIGHT: MutableMap<String, Future<ByteArray?>> = ConcurrentHashMap()

    @Volatile private var sharedProvider: LocalDomPoTokenProvider? = null

    /** Hands the extractor-derived SABR metadata for a video id to the playback path. */
    @JvmStatic
    fun putExtractorInfo(videoId: String, info: YoutubeSabrInfo) {
        EXTRACTOR_INFO[videoId] = info
    }

    @JvmStatic
    fun getExtractorInfo(videoId: String): YoutubeSabrInfo? = EXTRACTOR_INFO[videoId]

    /**
     * PipePipe-style detail-page prewarm: as soon as a SABR stream is selected, warm BOTH the
     * content PoToken and the SABR bootstrap for the selected video itag, using the same format
     * selection and cache key that [createSourceSpec] will use at play time. This makes the
     * first play effectively instant (the bootstrap is served from the cache / in-flight future
     * rather than being started when the user presses play).
     *
     * Mirrors PipePipe's `SabrSessionStore.prewarm(...)` which calls both `startTokenWarmup` and
     * `startBootstrap`. Warming only the token (without the bootstrap) is NOT enough and
     * previously regressed playback. Non-blocking: the work runs on background executors and is
     * deduplicated per video id / bootstrap key.
     *
     * @param selectedVideoItag the itag of the SABR video stream the user is about to play; must
     *                          match what [createSourceSpec] is later called with so the
     *                          bootstrap cache key lines up
     */
    @JvmStatic
    fun prewarm(context: Context, videoId: String, selectedVideoItag: Int) {
        val info = EXTRACTOR_INFO[videoId]
        if (info == null || !isUsableExtractorInfo(info, videoId)) {
            return
        }
        val audioFormat = pickAudioFormat(info, PREFERRED_AUDIO[videoId])
        val videoFormat = pickVideoFormat(info, selectedVideoItag)
        if (audioFormat == null || videoFormat == null) {
            return
        }
        val appContext = context.applicationContext
        val localization = Localization("en", "US")
        startTokenWarmup(appContext, info, audioFormat, videoFormat)
        startBootstrap(appContext, info, audioFormat, videoFormat, localization)
    }

    internal class SessionKey(
        private val sourceId: Long,
        val videoId: String,
        info: YoutubeSabrInfo,
        audioFormat: YoutubeSabrFormat,
        videoFormat: YoutubeSabrFormat
    ) {
        private val videoItag: Int = videoFormat.itag
        private val audioItag: Int = audioFormat.itag
        private val audioTrackId: String = audioFormat.audioTrackId.orEmpty()
        private val profile: YoutubeSabrClientProfile = info.profile

        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other !is SessionKey) {
                return false
            }
            return sourceId == other.sourceId &&
                videoItag == other.videoItag &&
                audioItag == other.audioItag &&
                videoId == other.videoId &&
                audioTrackId == other.audioTrackId &&
                profile == other.profile
        }

        override fun hashCode(): Int =
            Objects.hash(sourceId, videoId, videoItag, audioItag, audioTrackId, profile)
    }

    private class BootstrapResult(
        audioInitialization: ByteArray,
        videoInitialization: ByteArray,
        preparedSession: YoutubeSabrSession?
    ) {
        val audioInitialization: ByteArray = audioInitialization.clone()
        val videoInitialization: ByteArray = videoInitialization.clone()
        private val preparedSession = AtomicReference(preparedSession)

        fun takePreparedSession(): YoutubeSabrSession? = preparedSession.getAndSet(null)

        fun discardPreparedSession() {
            preparedSession.getAndSet(null)?.clearCache()
        }
    }

    private class BootstrapBackoffState(
        context: Context,
        private val videoId: String
    ) : YoutubeSabrSession.BackoffListener {
        private val appContext: Context = context.applicationContext
        private var deadlineElapsedMs = SabrBackoffCoordinator.NO_DEADLINE
        private var waiters = 0

        @Synchronized
        override fun onBackoffStarted(durationMs: Int) {
            deadlineElapsedMs = SystemClock.elapsedRealtime() + durationMs
            Log.i(
                TAG,
                "bootstrap_backoff_start video=$videoId durationMs=$durationMs waiters=$waiters"
            )
            if (waiters > 0) {
                SabrBackoffCoordinator.getInstance()
                    .beginPlaybackWait(appContext, this, deadlineElapsedMs)
            }
        }

        @Synchronized
        override fun onBackoffFinished() {
            Log.i(TAG, "bootstrap_backoff_finish video=$videoId waiters=$waiters")
            deadlineElapsedMs = SabrBackoffCoordinator.NO_DEADLINE
            SabrBackoffCoordinator.getInstance().clear(appContext, this)
        }

        @Synchronized
        fun beginWaiting() {
            waiters++
            if (deadlineElapsedMs > SystemClock.elapsedRealtime()) {
                SabrBackoffCoordinator.getInstance()
                    .beginPlaybackWait(appContext, this, deadlineElapsedMs)
            }
        }

        @Synchronized
        fun endWaiting() {
            waiters = maxOf(0, waiters - 1)
            if (waiters == 0) {
                SabrBackoffCoordinator.getInstance().clear(appContext, this)
            }
        }

        @Synchronized
        fun cancel() {
            waiters = 0
            deadlineElapsedMs = SabrBackoffCoordinator.NO_DEADLINE
            SabrBackoffCoordinator.getInstance().clear(appContext, this)
        }
    }

    class Lease internal constructor(
        private val key: SessionKey,
        private val holder: Holder
    ) : AutoCloseable {
        private val closed = AtomicBoolean()

        fun getHolder(): Holder = holder

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                releaseLease(key, holder)
            }
        }
    }

    private fun provider(context: Context): LocalDomPoTokenProvider {
        var p = sharedProvider
        if (p == null) {
            synchronized(SabrSessionStore::class.java) {
                p = sharedProvider
                if (p == null) {
                    p = LocalDomPoTokenProvider.shared(context.applicationContext)
                    sharedProvider = p
                }
            }
        }
        return p!!
    }

    class Holder {
        private val key: SessionKey
        private val appContext: Context

        @JvmField val videoId: String
        @JvmField val info: YoutubeSabrInfo
        @JvmField val session: YoutubeSabrSession
        @JvmField val audioFormat: YoutubeSabrFormat
        @JvmField val videoFormat: YoutubeSabrFormat

        // Playback position is only a hint. Pump and eviction use reader positions.
        @Volatile private var playerTimeMs: Long = 0
        private val readerPositions: MutableMap<Int, Long> = ConcurrentHashMap()
        private val activeTrackModes: MutableMap<Any, Int> = IdentityHashMap()
        private val initializationData: MutableMap<Int, ByteArray> = ConcurrentHashMap()
        private val bootstrapInitializationData: MutableMap<Int, ByteArray> = ConcurrentHashMap()

        // Tracks currently selected by ExoPlayer. Background/audio-only playback disables the
        // video renderer, so requiring a video reader position there pins the SABR cache at the
        // beginning.
        private val activeReaderItags: MutableSet<Int> =
            Collections.newSetFromMap(ConcurrentHashMap())
        internal val leaseReferences = AtomicInteger()
        private var readerOwner: Any? = null
        private var readerGeneration: Long = 0

        @Volatile private var pump: SabrStreamPump? = null
        @Volatile private var invalidated = false
        @Volatile private var stopReason: String? = null
        @Volatile private var terminalFailure: SabrLogicException? = null
        private var lastDiagnosticsAtMs: Long = 0
        private var lastDiagnosticsPeakCachedBytes: Long = 0

        internal constructor(
            appContext: Context,
            videoId: String,
            info: YoutubeSabrInfo,
            session: YoutubeSabrSession,
            audioFormat: YoutubeSabrFormat,
            videoFormat: YoutubeSabrFormat
        ) {
            this.key = SessionKey(0, videoId, info, audioFormat, videoFormat)
            this.appContext = appContext.applicationContext
            this.videoId = videoId
            this.info = info
            this.session = session
            this.audioFormat = audioFormat
            this.videoFormat = videoFormat
            attachBackoffListener()
        }

        internal constructor(
            appContext: Context,
            spec: SabrSourceSpec,
            session: YoutubeSabrSession
        ) {
            this.key = SessionKey(
                spec.sourceId, spec.videoId, spec.info, spec.audioFormat, spec.videoFormat
            )
            this.appContext = appContext.applicationContext
            this.videoId = spec.videoId
            this.info = spec.info
            this.session = session
            this.audioFormat = spec.audioFormat
            this.videoFormat = spec.videoFormat
            retainBootstrapInitialization(spec, audioFormat)
            retainBootstrapInitialization(spec, videoFormat)
            attachBackoffListener()
        }

        private fun attachBackoffListener() {
            session.setBackoffListener(object : YoutubeSabrSession.BackoffListener {
                override fun onBackoffStarted(durationMs: Int) {
                    Log.i(TAG, "backoff_start video=$videoId durationMs=$durationMs")
                    SabrBackoffCoordinator.getInstance().begin(
                        appContext, this@Holder, SystemClock.elapsedRealtime() + durationMs
                    )
                }

                override fun onBackoffFinished() {
                    Log.i(TAG, "backoff_finish video=$videoId")
                    SabrBackoffCoordinator.getInstance().clear(appContext, this@Holder)
                }
            })
        }

        fun getPlayerTimeMs(): Long = playerTimeMs

        internal fun getApplicationContext(): Context = appContext

        internal fun setPlayerTimeMs(playerTimeMs: Long) {
            this.playerTimeMs = playerTimeMs
        }

        /** A data source reports how far it has read (last served segment end, ms). */
        @Synchronized
        fun setReaderPositionMs(owner: Any, generation: Long, itag: Int, ms: Long) {
            if (readerOwner === owner && readerGeneration == generation) {
                readerPositions[itag] = ms
            }
        }

        internal fun setActiveTracks(owner: Any, videoActive: Boolean, audioActive: Boolean) {
            val trim: Boolean
            synchronized(this) {
                val mode = (if (videoActive) 1 else 0) or (if (audioActive) 2 else 0)
                if (mode == 0) {
                    activeTrackModes.remove(owner)
                    if (readerOwner === owner) {
                        readerOwner = activeTrackModes.keys.firstOrNull()
                        readerGeneration++
                        readerPositions.clear()
                    }
                } else {
                    activeTrackModes[owner] = mode
                    if (readerOwner !== owner) {
                        readerOwner = owner
                        readerGeneration++
                        readerPositions.clear()
                    }
                }
                applyActiveTracks()
                trim = activeTrackModes.isEmpty()
            }
            if (trim) {
                trimSessions(null)
            }
        }

        internal fun releaseTracks(owner: Any) {
            synchronized(this) {
                activeTrackModes.remove(owner)
                if (readerOwner === owner) {
                    readerOwner = activeTrackModes.keys.firstOrNull()
                    readerGeneration++
                    readerPositions.clear()
                }
                applyActiveTracks()
            }
            trimSessions(null)
        }

        @Synchronized
        internal fun advanceReaderGeneration(owner: Any) {
            if (readerOwner === owner) {
                readerGeneration++
                readerPositions.clear()
            }
        }

        @Synchronized
        internal fun getReaderGeneration(owner: Any): Long =
            if (readerOwner === owner) readerGeneration else -1

        @Synchronized
        internal fun isReaderGenerationActive(owner: Any, generation: Long): Boolean =
            readerOwner === owner && readerGeneration == generation

        @Synchronized
        private fun anchorReaderPositionMs(positionMs: Long) {
            if (readerOwner == null || activeReaderItags.isEmpty()) {
                return
            }
            for (itag in activeReaderItags) {
                readerPositions[itag] = positionMs
            }
        }

        internal fun requestSeek(positionMs: Long, localization: Localization) {
            val previousPlayerTimeMs = playerTimeMs
            val backward = positionMs < previousPlayerTimeMs
            setPlayerTimeMs(positionMs)
            recordDiagnostics("seek positionMs=$positionMs backward=$backward")
            anchorReaderPositionMs(positionMs)
            session.getStreamState().setSelectVideoFormatBeforeAudio(positionMs > 1_000)
            if (positionMs <= 1_000 && previousPlayerTimeMs <= 1_000) {
                return
            }
            // Media3 may seek within its sample queue; still reposition the SABR session when the
            // target audio/video segments are not cached.
            val targetFormat = videoFormat
            val sequence = session.getStreamState()
                .getSegmentNumberAtOrAfterTimeMs(targetFormat, positionMs)
            val request = SabrSegmentRequest.media(targetFormat, sequence)
            val audioSequence = session.getStreamState()
                .getSegmentNumberAtOrAfterTimeMs(audioFormat, positionMs)
            val audioRequest = SabrSegmentRequest.media(audioFormat, audioSequence)
            if (session.getCachedSegment(request) == null ||
                session.getCachedSegment(audioRequest) == null
            ) {
                getPump(localization).requestSeekTo(request, backward, positionMs)
            } else {
                getPump(localization).noteSeekWithinCache()
            }
        }

        @Synchronized
        internal fun hasActiveTracks(): Boolean = activeTrackModes.isNotEmpty()

        internal fun getInitializationData(itag: Int): ByteArray? {
            val cached = initializationData[itag]
            if (cached != null) {
                return cached
            }
            val bootstrap = bootstrapInitializationData[itag]
            if (bootstrap != null) {
                initializationData[itag] = bootstrap
                session.addDiagnosticEvent("bootstrap_init_restore itag=$itag")
            }
            return bootstrap
        }

        internal fun setInitializationData(itag: Int, data: ByteArray) {
            initializationData[itag] = data
        }

        private fun retainBootstrapInitialization(
            spec: SabrSourceSpec,
            format: YoutubeSabrFormat
        ) {
            val data = spec.getInitializationData(format.itag)
            if (data != null) {
                bootstrapInitializationData[format.itag] = data
            }
        }

        internal fun retainLease() {
            leaseReferences.incrementAndGet()
        }

        internal fun hasLeaseReferences(): Boolean = leaseReferences.get() > 0

        private fun applyActiveTracks() {
            var videoActive = false
            var audioActive = false
            for (mode in activeTrackModes.values) {
                videoActive = videoActive || (mode and 1) != 0
                audioActive = audioActive || (mode and 2) != 0
            }
            setTrackActive(videoFormat.itag, videoActive)
            setTrackActive(audioFormat.itag, audioActive)
            if (videoActive || audioActive) {
                session.getStreamState().setActiveTrackTypes(videoActive, audioActive)
            }
        }

        private fun setTrackActive(itag: Int, active: Boolean) {
            if (active) {
                activeReaderItags.add(itag)
                return
            }
            activeReaderItags.remove(itag)
            readerPositions.remove(itag)
        }

        fun getReaderHeadMs(): Long {
            var head = 0L
            for (itag in activeReaderItags) {
                val position = readerPositions[itag]
                if (position != null) {
                    head = maxOf(head, position)
                }
            }
            return head
        }

        /**
         * Zero until every selected track has read something, otherwise eviction can drop unread
         * data.
         */
        fun getReaderTailMs(): Long {
            if (activeReaderItags.isEmpty()) {
                return 0
            }
            var tail = Long.MAX_VALUE
            for (itag in activeReaderItags) {
                val position = readerPositions[itag] ?: return 0
                tail = minOf(tail, position)
            }
            return if (tail == Long.MAX_VALUE) 0 else tail
        }

        fun hasUnstartedActiveReader(): Boolean {
            if (activeReaderItags.isEmpty()) {
                return false
            }
            for (itag in activeReaderItags) {
                if (!readerPositions.containsKey(itag)) {
                    return true
                }
            }
            return false
        }

        @Synchronized
        internal fun getPump(localization: Localization): SabrStreamPump {
            var current = pump
            if (current == null) {
                current = SabrStreamPump(session, this, localization)
                pump = current
            }
            return current
        }

        internal fun isInvalidated(): Boolean = invalidated

        internal fun getInvalidationDetails(): String =
            "reason=$stopReason, leases=${leaseReferences.get()}" +
                ", trace=${session.getDiagnosticTrace()}"

        internal fun failTerminal(failure: SabrLogicException) {
            terminalFailure = failure
            recordDiagnostics("terminal_failure message=${failure.message}")
            evict(key, this, "terminal_failure message=${failure.message}", false)
        }

        @Throws(SabrLogicException::class)
        internal fun throwIfTerminal() {
            terminalFailure?.let { throw it }
        }

        internal fun stop(reason: String) {
            SabrBackoffCoordinator.getInstance().clear(appContext, this)
            session.setBackoffListener(null)
            Log.w(
                TAG,
                "stop video=$videoId reason=$reason leases=${leaseReferences.get()}" +
                    " activeTracks=${hasActiveTracks()}" +
                    " pump=${pump?.getStateName() ?: "none"}"
            )
            recordDiagnostics("stop reason=$reason")
            stopReason = reason
            session.addDiagnosticEvent(
                "session_stop reason=$reason leases=${leaseReferences.get()}" +
                    " activeTracks=${hasActiveTracks()}"
            )
            invalidated = true
            synchronized(this) {
                activeTrackModes.clear()
                readerOwner = null
                readerGeneration++
                readerPositions.clear()
                applyActiveTracks()
            }
            val streamPump = pump
            pump = null
            if (streamPump != null) {
                streamPump.stop()
            } else {
                session.clearCache()
            }
        }

        internal fun isBeyondEnd(request: SabrSegmentRequest): Boolean =
            session.isBeyondEnd(request)

        internal fun recordDiagnostics(event: String) {
            SabrPlaybackDiagnostics.record(appContext, this, event)
            lastDiagnosticsAtMs = System.currentTimeMillis()
            lastDiagnosticsPeakCachedBytes = session.getPeakCachedBytes()
        }

        internal fun recordDiagnosticsThrottled(event: String) {
            val now = System.currentTimeMillis()
            val peakCachedBytes = session.getPeakCachedBytes()
            if (now - lastDiagnosticsAtMs >= 5_000 ||
                peakCachedBytes != lastDiagnosticsPeakCachedBytes
            ) {
                recordDiagnostics(event)
            }
        }
    }

    @JvmStatic
    fun updatePlayerTime(videoId: String, playerTimeMs: Long) {
        if (playerTimeMs < 0) {
            return
        }
        for ((key, holder) in SESSIONS) {
            if (key.videoId == videoId && holder.hasLeaseReferences()) {
                holder.setPlayerTimeMs(playerTimeMs)
                holder.recordDiagnosticsThrottled("progress")
            }
        }
    }

    @JvmStatic
    fun updatePlaybackRate(videoId: String, playbackRate: Float) {
        for ((key, holder) in SESSIONS) {
            if (key.videoId == videoId && holder.hasLeaseReferences()) {
                holder.session.getStreamState().setPlaybackRate(playbackRate)
            }
        }
    }

    @JvmStatic
    fun setPreferredAudioTrack(videoId: String, audioTrackId: String?) {
        if (audioTrackId == null) {
            PREFERRED_AUDIO.remove(videoId)
        } else {
            PREFERRED_AUDIO[videoId] = audioTrackId
        }
    }

    @JvmStatic
    @Throws(IOException::class, ExtractionException::class)
    fun createSourceSpec(
        videoId: String,
        preferredVideoItag: Int,
        extractorInfo: YoutubeSabrInfo?
    ): SabrSourceSpec = createSourceSpec(videoId, preferredVideoItag, 0, null, extractorInfo)

    @JvmStatic
    @Throws(IOException::class, ExtractionException::class)
    fun createSourceSpec(
        videoId: String,
        preferredVideoItag: Int,
        preferredAudioItag: Int,
        preferredAudioTrackIdOverride: String?,
        extractorInfo: YoutubeSabrInfo?
    ): SabrSourceSpec {
        PlaybackStartupTrace.markForVideoId(videoId, "sabr_source_spec_started")
        val appContext = YouPipeApplication.appContext
        val preferredAudioTrackId = preferredAudioTrackIdOverride ?: PREFERRED_AUDIO[videoId]
        val localization = Localization("en", "US")
        val contentCountry = ContentCountry("US")
        val info = if (isUsableExtractorInfo(extractorInfo, videoId)) {
            extractorInfo!!
        } else {
            youtubeSabrProbeFetch(videoId, localization, contentCountry)
        }
        val audioFormat = pickAudioFormat(info, preferredAudioTrackId, preferredAudioItag)
        val videoFormat = pickVideoFormat(info, preferredVideoItag)
        if (audioFormat == null || videoFormat == null) {
            throw IOException("SABR: could not select audio/video formats for $videoId")
        }
        // A detail-page prewarm may already have completed the canonical SABR bootstrap. Never
        // publish a DASH manifest until both exact indexes have been parsed from SABR init data.
        startTokenWarmup(appContext, info, audioFormat, videoFormat)
        val bootstrapKey = bootstrapKey(info, audioFormat, videoFormat)
        val bootstrapFuture = startBootstrap(
            appContext, info, audioFormat, videoFormat, localization
        )
        val bootstrap = awaitBootstrap(bootstrapKey, bootstrapFuture, videoId)
        PlaybackStartupTrace.markForVideoId(videoId, "sabr_source_spec_ready")
        return SabrSourceSpec(
            videoId, info, audioFormat, videoFormat, localization,
            bootstrap.audioInitialization, bootstrap.videoInitialization,
            bootstrap.takePreparedSession()
        )
    }

    private fun startBootstrap(
        context: Context,
        info: YoutubeSabrInfo,
        audioFormat: YoutubeSabrFormat,
        videoFormat: YoutubeSabrFormat,
        localization: Localization
    ): Future<BootstrapResult> {
        val key = bootstrapKey(info, audioFormat, videoFormat)
        val cached = BOOTSTRAP_CACHE[key]
        if (cached != null) {
            PlaybackStartupTrace.markForVideoId(info.videoId, "sabr_audio_init_ready")
            PlaybackStartupTrace.markForVideoId(info.videoId, "sabr_video_init_ready")
            val completed = FutureTask(Callable { cached })
            completed.run()
            return completed
        }
        val backoffState = BootstrapBackoffState(context, info.videoId)
        val created = object : FutureTask<BootstrapResult>(
            Callable {
                cacheBootstrap(
                    key,
                    createPreparation(
                        context, info, audioFormat, videoFormat, localization, backoffState
                    )
                )
            }
        ) {
            override fun done() {
                PlaybackStartupTrace.markForVideoId(info.videoId, "sabr_audio_init_ready")
                PlaybackStartupTrace.markForVideoId(info.videoId, "sabr_video_init_ready")
            }
        }
        val existing = BOOTSTRAP_IN_FLIGHT.putIfAbsent(key, created)
        if (existing != null) {
            return existing
        }
        BOOTSTRAP_BACKOFFS[key] = backoffState
        PlaybackStartupTrace.markForVideoId(info.videoId, "sabr_bootstrap_started")
        BOOTSTRAP_EXECUTOR.execute(created)
        return created
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun createBootstrap(
        context: Context,
        info: YoutubeSabrInfo,
        audioFormat: YoutubeSabrFormat,
        videoFormat: YoutubeSabrFormat,
        localization: Localization,
        backoffState: BootstrapBackoffState
    ): BootstrapResult {
        val sessionProvider = provider(context)
        val spoolDirectory = File(
            context.applicationContext.cacheDir,
            "sabr-bootstrap/${info.videoId}-${System.nanoTime()}"
        )
        // Policy host construction boundary. With blank config, createSessionHost() returns the
        // builtin BuiltinSabrSessionPolicy host, so runtime behavior is unchanged.
        val sessionPolicyHost = SabrPolicyRuntime.createSessionHost()
        val session = YoutubeSabrSession(
            info, audioFormat, videoFormat, sessionProvider, spoolDirectory, sessionPolicyHost
        )
        session.setBackoffListener(backoffState)
        var handedOff = false
        try {
            attachPoToken(info.videoId, info, sessionProvider, session)
            try {
                session.bootstrapInitialization(localization)
            } catch (firstFailure: IOException) {
                attachPoToken(info.videoId, info, sessionProvider, session)
                session.bootstrapInitialization(localization)
            }
            val audio = session.getCachedSegment(
                SabrSegmentRequest.initialization(audioFormat)
            )
            val video = session.getCachedSegment(
                SabrSegmentRequest.initialization(videoFormat)
            )
            if (audio == null || video == null) {
                throw SabrLogicException(
                    "SABR bootstrap completed without cached init segments video=${info.videoId}"
                )
            }
            handedOff = true
            return BootstrapResult(audio.getData(), video.getData(), session)
        } finally {
            session.setBackoffListener(null)
            if (!handedOff) {
                session.clearCache()
            }
        }
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun createPreparation(
        context: Context,
        info: YoutubeSabrInfo,
        audioFormat: YoutubeSabrFormat,
        videoFormat: YoutubeSabrFormat,
        localization: Localization,
        backoffState: BootstrapBackoffState
    ): BootstrapResult {
        val tokenProvider = provider(context)
        val poToken = awaitWarmedToken(
            info.videoId, info, tokenProvider, YoutubeSabrStreamState(audioFormat, videoFormat)
        )
        if (poToken == null || poToken.isEmpty()) {
            throw SabrLogicException(
                "SABR PO token provider returned no token for video=${info.videoId}"
            )
        }
        return try {
            val result = createAdaptiveInitialization(
                info, audioFormat, videoFormat, localization, poToken
            )
            Log.i(
                TAG,
                "adaptive initialization ready video=${info.videoId}" +
                    " audioItag=${audioFormat.itag} videoItag=${videoFormat.itag}"
            )
            result
        } catch (adaptiveFailure: IOException) {
            Log.i(
                TAG,
                "adaptive initialization unavailable video=${info.videoId}" +
                    ", falling back to native SABR: ${adaptiveFailure.message}"
            )
            createBootstrap(context, info, audioFormat, videoFormat, localization, backoffState)
        }
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun createAdaptiveInitialization(
        info: YoutubeSabrInfo,
        audioFormat: YoutubeSabrFormat,
        videoFormat: YoutubeSabrFormat,
        localization: Localization,
        poToken: ByteArray
    ): BootstrapResult {
        // Policy host construction boundary; blank config ⇒ builtin host (see createBootstrap).
        val session = YoutubeSabrSession(
            info, audioFormat, videoFormat, null, null, SabrPolicyRuntime.createSessionHost()
        )
        val audio: Future<ByteArray> = INITIALIZATION_EXECUTOR.submit(
            Callable { session.fetchInitializationData(audioFormat, localization, 2_000, poToken) }
        )
        val video: Future<ByteArray> = INITIALIZATION_EXECUTOR.submit(
            Callable { session.fetchInitializationData(videoFormat, localization, 2_000, poToken) }
        )
        try {
            val audioData = audio.get()
            val videoData = video.get()
            return BootstrapResult(audioData, videoData, null)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted fetching adaptive SABR initialization", e)
        } catch (e: ExecutionException) {
            audio.cancel(true)
            video.cancel(true)
            when (val cause = e.cause) {
                is ExtractionException -> throw cause
                is IOException -> throw cause
                else -> throw IOException("Could not fetch adaptive SABR initialization", cause)
            }
        }
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun awaitBootstrap(
        key: String,
        future: Future<BootstrapResult>,
        videoId: String
    ): BootstrapResult {
        val backoffState = BOOTSTRAP_BACKOFFS[key]
        backoffState?.beginWaiting()
        try {
            return future.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted awaiting SABR bootstrap for $videoId", e)
        } catch (e: ExecutionException) {
            when (val cause = e.cause) {
                is IOException -> throw cause
                is ExtractionException -> throw cause
                else -> throw IOException("Could not bootstrap SABR for $videoId", cause)
            }
        } finally {
            if (backoffState != null) {
                backoffState.endWaiting()
                BOOTSTRAP_BACKOFFS.remove(key, backoffState)
            }
            BOOTSTRAP_IN_FLIGHT.remove(key, future)
        }
    }

    private fun bootstrapKey(
        info: YoutubeSabrInfo,
        audioFormat: YoutubeSabrFormat,
        videoFormat: YoutubeSabrFormat
    ): String =
        "${info.videoId}#${info.profile}#" +
            "${audioFormat.itag}:${audioFormat.lastModified}#" +
            "${videoFormat.itag}:${videoFormat.lastModified}"

    private fun cacheBootstrap(key: String, result: BootstrapResult): BootstrapResult {
        BOOTSTRAP_CACHE[key] = result
        return result
    }

    private fun startTokenWarmup(
        context: Context,
        info: YoutubeSabrInfo,
        audioFormat: YoutubeSabrFormat,
        videoFormat: YoutubeSabrFormat
    ) {
        val videoId = info.videoId
        val created = object : FutureTask<ByteArray?>(
            Callable {
                provider(context).getPoToken(
                    info, YoutubeSabrStreamState(audioFormat, videoFormat)
                )
            }
        ) {
            override fun done() {
                TOKEN_IN_FLIGHT.remove(videoId, this)
            }
        }
        if (TOKEN_IN_FLIGHT.putIfAbsent(videoId, created) == null) {
            TOKEN_EXECUTOR.execute(created)
        }
    }

    @Throws(IOException::class, ExtractionException::class)
    internal fun acquire(context: Context, spec: SabrSourceSpec): Lease {
        val key = SessionKey(
            spec.sourceId, spec.videoId, spec.info, spec.audioFormat, spec.videoFormat
        )
        // Resolve the shared provider before taking the session-store monitor. A token prewarm may
        // be initializing the same provider and acquire() must never wait for it while holding the
        // monitor that provider() itself needs.
        val sessionProvider = provider(context)
        synchronized(SabrSessionStore::class.java) {
            val current = SESSIONS[key]
            if (current != null) {
                current.retainLease()
                current.recordDiagnosticsThrottled("session_reuse")
                return Lease(key, current)
            }
            val spoolDirectory = File(
                context.applicationContext.cacheDir,
                "sabr-segments/${spec.videoId}-${System.nanoTime()}"
            )
            val preparedSession = spec.takePreparedSession()
            val session: YoutubeSabrSession
            if (preparedSession != null) {
                session = preparedSession
                session.addDiagnosticEvent("bootstrap_session_handoff")
            } else {
                // Policy host construction boundary; blank config ⇒ builtin host.
                session = YoutubeSabrSession(
                    spec.info, spec.audioFormat, spec.videoFormat, sessionProvider,
                    spoolDirectory, SabrPolicyRuntime.createSessionHost()
                )
                attachPoToken(spec.videoId, spec.info, sessionProvider, session)
            }
            val holder = Holder(context, spec, session)
            seedInitializationData(holder, spec, spec.audioFormat)
            seedInitializationData(holder, spec, spec.videoFormat)
            SESSIONS[key] = holder
            ORDER.remove(key)
            ORDER.addLast(key)
            holder.retainLease()
            trimSessions(key)
            holder.recordDiagnostics("session_create")
            return Lease(key, holder)
        }
    }

    private fun seedInitializationData(
        holder: Holder,
        spec: SabrSourceSpec,
        format: YoutubeSabrFormat
    ) {
        val data = spec.getInitializationData(format.itag)
        if (data != null) {
            holder.setInitializationData(format.itag, data)
            holder.session.getStreamState().ingestInitializationData(format, data)
        }
    }

    private fun releaseLease(key: SessionKey, holder: Holder) {
        val references = holder.leaseReferences.decrementAndGet()
        if (references <= 0) {
            evict(key, holder, "leases_released count=$references", true)
        }
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun attachPoToken(
        videoId: String,
        info: YoutubeSabrInfo,
        provider: SabrPoTokenProvider,
        session: YoutubeSabrSession
    ) {
        try {
            val token = awaitWarmedToken(videoId, info, provider, session.getStreamState())
            if (token == null || token.isEmpty()) {
                throw SabrLogicException(
                    "SABR PO token provider returned no token for video=$videoId"
                )
            }
            session.getStreamState().setPoToken(token)
            session.addDiagnosticEvent("token_attach bytes=${token.size}")
        } catch (e: IOException) {
            Log.w(TAG, "PO token attach failed video=$videoId", e)
            session.addDiagnosticEvent(
                "token_attach_failed type=${e.javaClass.simpleName} message=${e.message}"
            )
            throw e
        } catch (e: ExtractionException) {
            Log.w(TAG, "PO token attach failed video=$videoId", e)
            session.addDiagnosticEvent(
                "token_attach_failed type=${e.javaClass.simpleName} message=${e.message}"
            )
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "PO token attach failed video=$videoId", e)
            session.addDiagnosticEvent(
                "token_attach_failed type=${e.javaClass.simpleName} message=${e.message}"
            )
            throw SabrLogicException("SABR PO token attach failed for video=$videoId", e)
        }
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun awaitWarmedToken(
        videoId: String,
        info: YoutubeSabrInfo,
        provider: SabrPoTokenProvider,
        state: YoutubeSabrStreamState
    ): ByteArray? {
        val future = TOKEN_IN_FLIGHT[videoId]
        if (future == null) {
            PlaybackStartupTrace.markForVideoId(videoId, "sabr_token_mint_started")
            val token = provider.getPoToken(info, state)
            PlaybackStartupTrace.markForVideoId(videoId, "sabr_token_ready")
            return token
        }
        try {
            PlaybackStartupTrace.markForVideoId(videoId, "sabr_token_wait_started")
            val token = future.get()
            PlaybackStartupTrace.markForVideoId(videoId, "sabr_token_ready")
            return token
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted awaiting SABR token for $videoId", e)
        } catch (e: ExecutionException) {
            when (val cause = e.cause) {
                is IOException -> throw cause
                is ExtractionException -> throw cause
                else -> throw IOException("Could not prewarm SABR token for $videoId", cause)
            }
        } finally {
            TOKEN_IN_FLIGHT.remove(videoId, future)
        }
    }

    private fun isUsableExtractorInfo(info: YoutubeSabrInfo?, videoId: String): Boolean =
        info != null &&
            videoId == info.videoId &&
            !info.serverAbrStreamingUrl.isNullOrEmpty() &&
            info.getFormats().isNotEmpty()

    @Throws(IOException::class, ExtractionException::class)
    private fun youtubeSabrProbeFetch(
        videoId: String,
        localization: Localization,
        contentCountry: ContentCountry
    ): YoutubeSabrInfo = YoutubeSabrProbe.fetchSabrInfo(
        videoId, YoutubeSabrClientProfile.WEB, localization, contentCountry
    )

    private fun pickAudioFormat(
        info: YoutubeSabrInfo,
        preferredTrackId: String?
    ): YoutubeSabrFormat? = pickAudioFormat(info, preferredTrackId, 0)

    private fun pickAudioFormat(
        info: YoutubeSabrInfo,
        preferredTrackId: String?,
        preferredAudioItag: Int
    ): YoutubeSabrFormat? {
        // Direct itag selection (from UI: sabr://videoId?a=itag) takes precedence.
        if (preferredAudioItag > 0) {
            for (f in info.getFormats()) {
                if (f.isAudio && f.itag == preferredAudioItag) {
                    return f
                }
            }
        }
        if (preferredTrackId == null) {
            return info.findBestAudioFormat()
        }
        var best: YoutubeSabrFormat? = null
        for (f in info.getFormats()) {
            if (!f.isAudio) {
                continue
            }
            if (preferredTrackId != f.audioTrackId) {
                continue
            }
            if (best == null || f.bitrate > best.bitrate) {
                best = f
            }
        }
        return best ?: info.findBestAudioFormat()
    }

    private fun pickVideoFormat(
        info: YoutubeSabrInfo,
        preferredItag: Int
    ): YoutubeSabrFormat? {
        if (preferredItag > 0) {
            for (f in info.getFormats()) {
                if (f.isVideo && f.itag == preferredItag) {
                    return f
                }
            }
        }
        return info.findLowestVideoFormat()
    }

    @JvmStatic
    fun evict(videoId: String) {
        val holders = ArrayList<Holder>()
        synchronized(SabrSessionStore::class.java) {
            val iterator = SESSIONS.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.videoId == videoId) {
                    holders.add(entry.value)
                    ORDER.remove(entry.key)
                    iterator.remove()
                }
            }
        }
        for (holder in holders) {
            holder.stop("explicit")
        }
    }

    /** Reset SABR-only caches before a cold benchmark trial. Not used by playback code. */
    @JvmStatic
    fun clearBenchmarkCaches(context: Context, videoId: String) {
        evict(videoId)
        for ((key, future) in BOOTSTRAP_IN_FLIGHT) {
            if (key.startsWith("$videoId#")) {
                future.cancel(true)
                BOOTSTRAP_IN_FLIGHT.remove(key, future)
                BOOTSTRAP_BACKOFFS.remove(key)?.cancel()
            }
        }
        synchronized(BOOTSTRAP_CACHE) {
            val iterator = BOOTSTRAP_CACHE.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.startsWith("$videoId#")) {
                    entry.value.discardPreparedSession()
                    iterator.remove()
                }
            }
        }
        TOKEN_IN_FLIGHT.remove(videoId)?.cancel(true)
        provider(context).clearCachedToken(videoId)
    }

    private fun trimSessions(protectedKey: SessionKey?) {
        while (true) {
            val holder: Holder?
            synchronized(SabrSessionStore::class.java) {
                if (ORDER.size <= MAX_SESSIONS) {
                    return
                }
                var candidate: SessionKey? = null
                for (key in ORDER) {
                    val current = SESSIONS[key]
                    if (key != protectedKey && current != null &&
                        !current.hasActiveTracks() && !current.hasLeaseReferences()
                    ) {
                        candidate = key
                        break
                    }
                }
                if (candidate == null) {
                    return
                }
                holder = SESSIONS.remove(candidate)
                ORDER.remove(candidate)
            }
            holder?.stop("session_trim protectedVideo=${protectedKey?.videoId}")
        }
    }

    private fun evict(
        key: SessionKey,
        expectedHolder: Holder?,
        reason: String,
        requireNoLeaseReferences: Boolean
    ) {
        val holder: Holder?
        synchronized(SabrSessionStore::class.java) {
            holder = SESSIONS[key]
            if (holder == null ||
                (expectedHolder != null && holder !== expectedHolder) ||
                (requireNoLeaseReferences && holder.hasLeaseReferences())
            ) {
                return
            }
            SESSIONS.remove(key)
            ORDER.remove(key)
        }
        holder?.stop(reason)
    }
}
