package com.vayunmathur.youpipe.util.sabr

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrMediaSegment
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

class SabrSegmentDataSource : DataSource {

    private var holder: SabrSessionStore.Holder?
    private val sessionHandle: SabrSessionHandle?
    private val readerOwner: Any
    private val fixedFormat: YoutubeSabrFormat?
    private val localization: Localization
    private val prependInit: Boolean

    private var uri: Uri? = null
    private var data: ByteArray? = null
    private var dataStream: InputStream? = null
    private var progressiveSegment: SabrMediaSegment? = null
    private var progressiveReaderGeneration: Long = -1
    private var progressiveDataEndPosition = -1
    private var bytesRemaining: Long = 0
    private var pos = 0
    private var opened = false

    @Volatile private var canceled = false

    constructor(
        holder: SabrSessionStore.Holder,
        readerOwner: Any,
        format: YoutubeSabrFormat,
        localization: Localization,
        prependInit: Boolean
    ) {
        this.holder = holder
        this.sessionHandle = null
        this.readerOwner = readerOwner
        this.fixedFormat = format
        this.localization = localization
        this.prependInit = prependInit
    }

    constructor(
        holder: SabrSessionStore.Holder,
        readerOwner: Any,
        localization: Localization,
        prependInit: Boolean
    ) {
        this.holder = holder
        this.sessionHandle = null
        this.readerOwner = readerOwner
        this.fixedFormat = null
        this.localization = localization
        this.prependInit = prependInit
    }

    internal constructor(
        sessionHandle: SabrSessionHandle,
        readerOwner: Any,
        localization: Localization,
        prependInit: Boolean
    ) {
        this.holder = null
        this.sessionHandle = sessionHandle
        this.readerOwner = readerOwner
        this.fixedFormat = null
        this.localization = localization
        this.prependInit = prependInit
    }

    override fun addTransferListener(transferListener: TransferListener) {
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        var currentHolder = holder
        if (currentHolder == null) {
            if (sessionHandle == null) {
                throw IOException("SABR data source has no session handle")
            }
            currentHolder = sessionHandle.acquireHolder()
            holder = currentHolder
        }
        uri = dataSpec.uri
        canceled = false
        closeDataStream()
        data = null
        progressiveSegment = null
        progressiveReaderGeneration = -1
        progressiveDataEndPosition = -1
        pos = maxOf(0, dataSpec.position).toInt()
        val request = requestFromUri(dataSpec.uri)
        val format = request.format
        val availableRemaining: Long
        val openedBytes: Int
        Log.d(
            TAG,
            "open video=${currentHolder.videoId} itag=${format.itag}" +
                " uri=${dataSpec.uri} prependInit=$prependInit"
        )
        if (request.isInitializationSegment()) {
            val initData = getInitializationData(format)
            data = initData
            availableRemaining = maxOf(0, initData.size - pos).toLong()
            openedBytes = initData.size
        } else if (prependInit) {
            val init = getInitializationData(format)
            val segment = awaitSegment(request)
            val media = segment?.getData() ?: ByteArray(0)
            val both = ByteArray(init.size + media.size)
            System.arraycopy(init, 0, both, 0, init.size)
            System.arraycopy(media, 0, both, init.size, media.size)
            data = both
            if (progressiveSegment != null) {
                progressiveDataEndPosition = both.size
            }
            availableRemaining = maxOf(0, both.size - pos).toLong()
            openedBytes = both.size
        } else {
            var segment = awaitSegment(request)
            if (segment != null) {
                try {
                    dataStream = segment.openStream()
                } catch (e: FileNotFoundException) {
                    Log.w(
                        TAG,
                        "Spool file vanished before open; refetching video=" +
                            "${currentHolder.videoId} itag=${format.itag}" +
                            " seq=${request.getSequenceNumber()}"
                    )
                    currentHolder.session.discardCachedSegment(request)
                    progressiveSegment = null
                    segment = awaitSegment(request)
                    if (segment != null) {
                        dataStream = segment.openStream()
                    }
                }
            }
            if (segment == null) {
                data = ByteArray(0)
                availableRemaining = 0
                openedBytes = 0
            } else {
                if (progressiveSegment != null) {
                    progressiveDataEndPosition = segment.length
                }
                val skipped = skipFully(dataStream!!, maxOf(0, dataSpec.position))
                pos = minOf(Int.MAX_VALUE.toLong(), skipped).toInt()
                availableRemaining = maxOf(0, segment.length - skipped)
                openedBytes = segment.length
            }
        }
        opened = true
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET) {
            availableRemaining
        } else {
            minOf(dataSpec.length, availableRemaining)
        }
        Log.d(
            TAG,
            "opened video=${currentHolder.videoId} itag=${format.itag}" +
                " bytes=$openedBytes remaining=$availableRemaining"
        )
        return bytesRemaining
    }

    @Throws(IOException::class)
    private fun getInitializationData(format: YoutubeSabrFormat): ByteArray {
        val currentHolder = holder!!
        val itag = format.itag
        currentHolder.getInitializationData(itag)?.let { return it }
        val segment =
            currentHolder.session.getCachedSegment(SabrSegmentRequest.initialization(format))
        if (segment != null) {
            val segmentData = segment.getData()
            currentHolder.setInitializationData(itag, segmentData)
            return segmentData
        }
        val loadedSegment = awaitSegment(SabrSegmentRequest.initialization(format))
        return loadedSegment?.getData() ?: ByteArray(0)
    }

    @Throws(IOException::class)
    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) {
            return 0
        }
        if (bytesRemaining <= 0) {
            return C.RESULT_END_OF_INPUT
        }
        val currentData = data
        if (currentData != null) {
            if (pos >= currentData.size) {
                return C.RESULT_END_OF_INPUT
            }
            val toCopy =
                minOf(minOf(length.toLong(), (currentData.size - pos).toLong()), bytesRemaining)
                    .toInt()
            System.arraycopy(currentData, pos, target, offset, toCopy)
            pos += toCopy
            bytesRemaining -= toCopy
            maybeAdvanceProgressiveReader()
            return toCopy
        }
        val stream = dataStream ?: return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        val read = stream.read(target, offset, toRead)
        if (read < 0) {
            bytesRemaining = 0
            return C.RESULT_END_OF_INPUT
        }
        pos = minOf(Int.MAX_VALUE.toLong(), pos.toLong() + read).toInt()
        bytesRemaining -= read
        maybeAdvanceProgressiveReader()
        return read
    }

    private fun maybeAdvanceProgressiveReader() {
        val segment = progressiveSegment
        val currentHolder = holder
        if (segment == null || progressiveDataEndPosition < 0 ||
            pos < progressiveDataEndPosition || !segment.isComplete() || currentHolder == null
        ) {
            return
        }
        val format = if (segment.header.itag == currentHolder.videoFormat.itag) {
            currentHolder.videoFormat
        } else {
            currentHolder.audioFormat
        }
        currentHolder.setReaderPositionMs(
            readerOwner, progressiveReaderGeneration, format.itag,
            segment.header.startMs + segment.header.durationMs
        )
        progressiveSegment = null
        progressiveReaderGeneration = -1
        progressiveDataEndPosition = -1
    }

    @Throws(IOException::class)
    private fun requestFromUri(u: Uri): SabrSegmentRequest {
        val format = formatFromUri(u)
        val seg = u.lastPathSegment ?: throw SabrLogicException("Bad SABR segment uri: $u")
        if ("init" == seg) {
            return SabrSegmentRequest.initialization(format)
        }
        return try {
            SabrSegmentRequest.media(format, seg.toInt())
        } catch (e: NumberFormatException) {
            throw SabrLogicException("Bad SABR segment uri: $u", e)
        }
    }

    @Throws(IOException::class)
    private fun formatFromUri(u: Uri): YoutubeSabrFormat {
        fixedFormat?.let { return it }
        val currentHolder = holder!!
        val host = u.host ?: throw SabrLogicException("Bad SABR segment uri without itag: $u")
        val itag = try {
            host.toInt()
        } catch (e: NumberFormatException) {
            throw SabrLogicException("Bad SABR segment itag in uri: $u", e)
        }
        if (currentHolder.videoFormat.itag == itag) {
            return currentHolder.videoFormat
        }
        if (currentHolder.audioFormat.itag == itag) {
            return currentHolder.audioFormat
        }
        throw SabrLogicException("Unknown SABR segment itag=$itag uri=$u")
    }

    @Throws(IOException::class)
    private fun awaitSegment(request: SabrSegmentRequest): SabrMediaSegment? {
        val holder = this.holder!!
        val format = request.format
        holder.throwIfTerminal()
        if (holder.isInvalidated()) {
            throw invalidatedException(request.format)
        }
        val pump = holder.getPump(localization)
        var readerGeneration = holder.getReaderGeneration(readerOwner)
        val waitStart = System.currentTimeMillis()
        var noProgressSinceMs = waitStart
        var mediaProgressVersion = holder.session.getMediaProgressVersion()
        var recoveryAtMs: Long = -1
        var lastRecoveryAtMs: Long = -1
        var loggedWait = false
        try {
            while (true) {
                if (canceled) {
                    throw IOException("SABR segment read canceled")
                }
                if (!request.isInitializationSegment()) {
                    val currentReaderGeneration = holder.getReaderGeneration(readerOwner)
                    if (readerGeneration < 0 && currentReaderGeneration >= 0) {
                        readerGeneration = currentReaderGeneration
                        noProgressSinceMs = System.currentTimeMillis()
                        mediaProgressVersion = holder.session.getMediaProgressVersion()
                    } else if (readerGeneration >= 0 &&
                        currentReaderGeneration != readerGeneration
                    ) {
                        throw InterruptedIOException(
                            "SABR reader demand superseded for itag=${format.itag}," +
                                " seq=${request.getSequenceNumber()}"
                        )
                    }
                }
                holder.throwIfTerminal()
                if (holder.isInvalidated()) {
                    throw invalidatedException(request.format)
                }
                if (holder.session.isBeyondEnd(request)) {
                    Log.d(
                        TAG,
                        "beyond end video=${holder.videoId} itag=${format.itag}" +
                            " seq=${request.getSequenceNumber()}"
                    )
                    holder.session.addDiagnosticEvent(
                        "beyond_end itag=${format.itag} seq=${request.getSequenceNumber()}"
                    )
                    return null
                }
                val demandFailure =
                    if (!request.isInitializationSegment() && readerGeneration >= 0) {
                        pump.takeDemandFailure(request, readerOwner, readerGeneration)
                    } else {
                        null
                    }
                if (demandFailure != null) {
                    throw demandFailure
                }
                val networkFailure = pump.takeNetworkFailure()
                if (networkFailure != null) {
                    throw networkFailure
                }
                if (request.isInitializationSegment()) {
                    pump.requestInitialization(format)
                } else {
                    pump.ensureStarted()
                }
                val segment: SabrMediaSegment? = if (request.isInitializationSegment()) {
                    pump.getCached(request)
                } else {
                    try {
                        holder.session.awaitReadableSegment(request, WAIT_MS)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("Interrupted waiting for SABR segment", e)
                    }
                }
                if (segment != null) {
                    Log.d(
                        TAG,
                        "cache hit video=${holder.videoId} itag=${format.itag}" +
                            " init=${request.isInitializationSegment()}" +
                            " seq=${request.getSequenceNumber()}" +
                            " bytes=${segment.length} disk=${segment.isDiskBacked()}"
                    )
                    if (!segment.header.isInitSegment()) {
                        if (segment.isComplete()) {
                            holder.setReaderPositionMs(
                                readerOwner, readerGeneration, format.itag,
                                segment.header.startMs + segment.header.durationMs
                            )
                        } else {
                            progressiveSegment = segment
                            progressiveReaderGeneration = readerGeneration
                        }
                    }
                    return segment
                }
                if (holder.session.isBeyondEnd(request)) {
                    Log.d(
                        TAG,
                        "beyond end video=${holder.videoId} itag=${format.itag}" +
                            " seq=${request.getSequenceNumber()}"
                    )
                    holder.session.addDiagnosticEvent(
                        "beyond_end itag=${format.itag} seq=${request.getSequenceNumber()}"
                    )
                    return null
                }
                if (!request.isInitializationSegment() && readerGeneration >= 0) {
                    pump.requestSegmentDemand(request, readerOwner, readerGeneration)
                }
                if (!loggedWait && System.currentTimeMillis() - waitStart > 1000) {
                    loggedWait = true
                    holder.session.addDiagnosticEvent(
                        "wait itag=${format.itag}" +
                            " init=${request.isInitializationSegment()}" +
                            " seq=${request.getSequenceNumber()}" +
                            " pump=${pump.getStateName()}" +
                            " edgeMs=${holder.session.getStreamState().getMinBufferedEndMs()}" +
                            " readerHeadMs=${holder.getReaderHeadMs()}" +
                            " readerTailMs=${holder.getReaderTailMs()}" +
                            " cachedBytes=${holder.session.getCachedBytes()}"
                    )
                    Log.d(
                        TAG,
                        "waiting video=${holder.videoId} itag=${format.itag}" +
                            " init=${request.isInitializationSegment()}" +
                            " seq=${request.getSequenceNumber()}" +
                            " edgeMs=${holder.session.getStreamState().getMinBufferedEndMs()}" +
                            " readerHeadMs=${holder.getReaderHeadMs()}"
                    )
                }
                val now = System.currentTimeMillis()
                val currentMediaProgressVersion = holder.session.getMediaProgressVersion()
                if (currentMediaProgressVersion != mediaProgressVersion) {
                    mediaProgressVersion = currentMediaProgressVersion
                    noProgressSinceMs = now
                    recoveryAtMs = -1
                    lastRecoveryAtMs = -1
                }
                if (holder.session.getDemandBackoffRemainingMs() > 0) {
                    // Server-directed pacing is not a playback stall. Keep polling so cancellation
                    // and reader replacement remain responsive, but do not let the local recovery
                    // watchdog reposition the session and attempt another request before the
                    // server deadline.
                    noProgressSinceMs = now
                    recoveryAtMs = -1
                    lastRecoveryAtMs = -1
                }
                if (now - noProgressSinceMs > RECOVERY_AFTER_NO_PROGRESS_MS &&
                    (lastRecoveryAtMs < 0 || now - lastRecoveryAtMs > RECOVERY_RETRY_MS) &&
                    pump.canRecover() &&
                    (request.isInitializationSegment() || readerGeneration >= 0)
                ) {
                    val recovery: String
                    if (request.isInitializationSegment()) {
                        recovery = "init"
                        pump.requestInitialization(format)
                    } else {
                        val edgeMs = holder.session.getStreamState().getMinBufferedEndMs()
                        val segStartMs = holder.session.getStreamState()
                            .getSegmentStartMs(format, request.getSequenceNumber())
                        if (segStartMs < edgeMs) {
                            recovery = "rewind"
                            holder.setReaderPositionMs(
                                readerOwner, readerGeneration, format.itag, segStartMs
                            )
                            pump.requestRefetchFrom(request)
                        } else if (segStartMs > edgeMs + FORWARD_SEEK_AHEAD_MS) {
                            recovery = "forward"
                            holder.setReaderPositionMs(
                                readerOwner, readerGeneration, format.itag, segStartMs
                            )
                            pump.requestForwardSeekTo(request)
                        } else {
                            recovery = "near_edge_refetch"
                            holder.setReaderPositionMs(
                                readerOwner, readerGeneration, format.itag, segStartMs
                            )
                            pump.requestRefetchFrom(request)
                        }
                    }
                    holder.session.addDiagnosticEvent(
                        "recovery type=$recovery itag=${format.itag}" +
                            " init=${request.isInitializationSegment()}" +
                            " seq=${request.getSequenceNumber()}" +
                            " pump=${pump.getStateName()}" +
                            " edgeMs=${holder.session.getStreamState().getMinBufferedEndMs()}"
                    )
                    if (recoveryAtMs < 0) {
                        recoveryAtMs = now
                    }
                    lastRecoveryAtMs = now
                }
                if (recoveryAtMs >= 0 && now - recoveryAtMs > RECOVERY_FAILURE_MS &&
                    pump.canRecover()
                ) {
                    val failure = SabrLogicException(
                        "SABR made no progress after recovery for itag=${format.itag}" +
                            ", init=${request.isInitializationSegment()}" +
                            ", seq=${request.getSequenceNumber()}" +
                            ", waitMs=${now - waitStart}" +
                            ", pump=${pump.getStateName()}" +
                            ", edgeMs=${holder.session.getStreamState().getMinBufferedEndMs()}" +
                            ", readerHeadMs=${holder.getReaderHeadMs()}" +
                            ", readerTailMs=${holder.getReaderTailMs()}" +
                            ", cachedBytes=${holder.session.getCachedBytes()}" +
                            ", trace=${holder.session.getDiagnosticTrace()}"
                    )
                    holder.failTerminal(failure)
                    throw failure
                }
                if (request.isInitializationSegment()) {
                    try {
                        Thread.sleep(WAIT_MS)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("Interrupted awaiting SABR initialization", e)
                    }
                }
            }
        } finally {
            if (!request.isInitializationSegment()) {
                pump.clearSegmentDemand(request, readerOwner, readerGeneration)
            }
        }
    }

    private fun invalidatedException(format: YoutubeSabrFormat): SabrLogicException {
        val currentHolder = holder!!
        return SabrLogicException(
            "SABR session invalidated for video=${currentHolder.videoId}" +
                ", itag=${format.itag}, ${currentHolder.getInvalidationDetails()}"
        )
    }

    @Throws(IOException::class)
    private fun closeDataStream() {
        dataStream?.let {
            it.close()
            dataStream = null
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        canceled = true
        data = null
        try {
            closeDataStream()
        } catch (e: IOException) {
            Log.w(TAG, "Could not close SABR segment stream", e)
        }
        opened = false
    }

    private companion object {
        private const val TAG = "SabrSegmentDataSource"

        private const val WAIT_MS = 250L
        private const val RECOVERY_AFTER_NO_PROGRESS_MS = 10_000L
        private const val RECOVERY_RETRY_MS = 10_000L
        private const val RECOVERY_FAILURE_MS = 30_000L
        private const val FORWARD_SEEK_AHEAD_MS = 30_000L

        @Throws(IOException::class)
        private fun skipFully(input: InputStream, requested: Long): Long {
            var remaining = requested
            val buffer = ByteArray(8192)
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                    continue
                }
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) {
                    break
                }
                remaining -= read
            }
            return requested - remaining
        }
    }
}
