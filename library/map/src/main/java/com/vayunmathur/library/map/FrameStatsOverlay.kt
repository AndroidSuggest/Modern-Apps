package com.vayunmathur.library.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The native step names in [MapNative.lastFrameStepTimesNanos] order.
 *
 * A mirror of the `Step::ALL` short names in `timing.rs` — adding a step there means
 * appending here. Counted rather than zipped: on drift ([times] shorter or longer) the
 * table shows `n/a` rows rather than mislabelling them.
 */
private val STEP_NAMES = listOf(
    "jni", "upl", "sel", "fen", "ret", "acq", "rec", "plc",
    "ter", "flt", "car", "bld", "sym", "trf", "arw", "msk",
    "ral", "ovl", "sub", "prs",
)

/**
 * The per-step frame-time table for the debug overlay: one monospace row per native step,
 * last frame's ms, polled from [MapNative.lastFrameStepTimesNanos].
 *
 * Off by default ([enabled]) so release pays nothing: no poll, no composition, no JNI. The
 * poll runs at ~3 Hz through [renderer]'s live handle and never touches the frame loop — it
 * reads the native copy rather than asking for a frame, so the overlay cannot keep the map
 * awake or change its pacing.
 *
 * @param renderer the live renderer to poll; a zero handle (no surface yet) reads as `n/a`.
 */
@Composable
fun FrameStatsOverlay(
    renderer: SurfaceMapRenderer,
    modifier: Modifier = Modifier,
    enabled: Boolean = false,
) {
    if (!enabled) return
    var times by remember { mutableStateOf(LongArray(0)) }
    LaunchedEffect(renderer) {
        while (isActive) {
            val handle = renderer.handle
            times = if (handle != 0L) {
                try {
                    MapNative.lastFrameStepTimesNanos(handle)
                } catch (_: Throwable) {
                    LongArray(0)
                }
            } else {
                LongArray(0)
            }
            delay(POLL_MILLIS)
        }
    }
    Box(
        modifier
            .background(Color(0xC0000000))
            .padding(6.dp),
    ) {
        Column {
            // Strings first, composables second: the rows are plain data, emitted below.
            for (i in STEP_NAMES.indices) {
                val name = STEP_NAMES[i]
                val ms = times.getOrNull(i)?.let { it / 1_000_000.0 }
                // One decimal, fixed width-ish: the table must not jitter while panning.
                val text = if (ms == null) "$name  n/a" else "$name %5.1f".format(ms)
                BasicText(text, style = TextStyle(color = Color.White, fontSize = 10.sp))
            }
        }
    }
}

/** How often the overlay polls the native copy: ~3 Hz, slow enough to cost nothing. */
private const val POLL_MILLIS = 333L
