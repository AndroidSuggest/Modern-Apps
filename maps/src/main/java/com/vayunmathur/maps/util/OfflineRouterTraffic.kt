package com.vayunmathur.maps.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Live-traffic display state, extracted from [OfflineRouter] to keep that
 * file under the length limit.
 *
 * The native side owns routing speeds entirely ([OfflineRouter.updateTrafficNative]);
 * this object owns only what the renderer draws: the per-component colour
 * table plus the version counter the prefetch path bumps. [OfflineRouter]
 * keeps the JNI surface (native methods, `fetchTrafficData` reverse
 * callback) — native code resolves those by class and member name — and
 * delegates here for everything pure Kotlin.
 */
internal object OfflineRouterTraffic {
    /**
     * Live per-component traffic for **display**, as a flat id→ratio table.
     *
     * Separate from the big-edge speeds that feed the native updater
     * (routing/ETA, which stay entirely native): these component-level values
     * are pushed to the renderer as an id→colour table by the map layer,
     * which resolves the colour from the theme. [ids] and [ratioPct] are
     * parallel; [ratioPct] is the wire's `u8 = round(speedRatio*100)`, where
     * `0` means "no data" (the consumer skips those). The renderer draws a
     * colour only for ids present here and present in a resident tile, so ids
     * for squares that scrolled off are harmless — which is why this is an
     * accumulating session cache rather than something pruned on every pan
     * (see [trafficComponents]).
     */
    class TrafficComponents(val ids: LongArray, val ratioPct: ByteArray) {
        companion object {
            val EMPTY = TrafficComponents(LongArray(0), ByteArray(0))
        }
    }

    /**
     * Component traffic keyed by the packed 1° square it was fetched for. The
     * native prefetch dedups squares for the whole session, so a square is
     * fetched once and kept: dropping it here would leave a re-panned area
     * permanently uncoloured with no way to refetch. Cleared only on
     * [onReload] (a graph swap can renumber ids).
     */
    private val componentBySquare =
            java.util.concurrent.ConcurrentHashMap<Int, TrafficComponents>()

    /**
     * Last published merge, for the unchanged-content skip in [republish].
     * Volatile (not confined): fetches complete on IO threads, so the
     * read-modify-publish must tolerate races — the worst case is one
     * redundant publish, never a missed one.
     */
    @Volatile
    private var lastPublished: TrafficComponents? = null

    private val _trafficComponents = MutableStateFlow(TrafficComponents.EMPTY)

    /**
     * The merged component table across every fetched square, republished
     * whenever a fetch lands. The map layer collects this, converts each
     * ratio to an ARGB colour against the current palette, and pushes
     * `id→colour` to the renderer.
     */
    val trafficComponents = _trafficComponents.asStateFlow()

    private val _trafficVersion = MutableStateFlow(0)
    val trafficVersion = _trafficVersion.asStateFlow()

    private var trafficUpdateJob: kotlinx.coroutines.Job? = null
    private val trafficScope = CoroutineScope(Dispatchers.IO)

    /** Run [block] on the traffic IO scope (fetch completions live here). */
    fun launch(block: suspend () -> Unit) {
        trafficScope.launch { block() }
    }

    /** Store one square's component table and republish the merge. */
    fun storeSquare(packedSquare: Int, components: TrafficComponents) {
        componentBySquare[packedSquare] = components
        republish()
    }

    /** Concatenate the per-square tables into one flat snapshot and publish it. */
    private fun republish() {
        val squares = componentBySquare.values.toList()
        val total = squares.sumOf { it.ids.size }
        val ids = LongArray(total)
        val ratios = ByteArray(total)
        var off = 0
        for (s in squares) {
            System.arraycopy(s.ids, 0, ids, off, s.ids.size)
            System.arraycopy(s.ratioPct, 0, ratios, off, s.ratioPct.size)
            off += s.ids.size
        }
        // Skip the publish when the merged content is unchanged: every fetch
        // lands here, and a fresh object wakes the trafficComponents
        // collectors (table rebuild + renderer re-push) even when a refetch
        // returned identical bytes. Content-compare is O(n) against the same
        // concat that already ran, far cheaper than the downstream rebuild.
        val last = lastPublished
        if (last != null && last.ids.contentEquals(ids) && last.ratioPct.contentEquals(ratios)) {
            return
        }
        val snapshot = TrafficComponents(ids, ratios)
        lastPublished = snapshot
        _trafficComponents.value = snapshot
    }

    fun notifyTrafficUpdated() {
        trafficUpdateJob?.cancel()
        trafficUpdateJob = trafficScope.launch {
            _trafficVersion.value++
        }
    }

    /** Drops the display cache; called by [OfflineRouter.reload]. */
    fun onReload() {
        componentBySquare.clear()
        lastPublished = null
        _trafficComponents.value = TrafficComponents.EMPTY
    }
}
