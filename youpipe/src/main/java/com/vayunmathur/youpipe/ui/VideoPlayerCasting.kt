package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.IconCastConnected
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.youpipe.R

internal const val HISTORY_UPSERT_INTERVAL_MS = 5000L
internal const val CONTROLS_AUTO_HIDE_DELAY_MS = 2000L

/**
 * The fallback frame height asked of Cast, used only when the stream does not report its own size.
 *
 * A request, not a decision: Cast clamps it to what the TV reported it can decode and to what this
 * phone's encoder will take, and answers with the real numbers.
 */
private const val CAST_REQUEST_HEIGHT = 1080

/**
 * The frame size to ask Cast for, taken from the stream that is actually playing.
 *
 * **This used to be `CAST_REQUEST_HEIGHT * aspectRatio` by `CAST_REQUEST_HEIGHT`, which asked for
 * 1920x1080 for every video ever cast.** `aspectRatio` is a ratio: it carries shape and no magnitude,
 * so a 360p stream was asked to fill 1080 lines and a 4K one was thrown away on the way out. The real
 * numbers were in scope at the call site the whole time, and YouPipe deliberately does not filter
 * streams above 1080p, so there was nothing to gain from the cap.
 *
 * **Both dimensions are rounded down to even.** 4:2:0 chroma cannot represent an odd width or height,
 * and several encoders refuse such a size outright rather than rounding it themselves.
 *
 * A stream that reports no size falls back to the old fixed height in the measured aspect ratio -
 * that path is a guess either way, and the receiver letterboxes, so the shape is ours to choose.
 */
internal fun castRequestSize(stream: VideoStream, aspectRatio: Float): Pair<Int, Int> {
    if (stream.width > 0 && stream.height > 0) return stream.width.evenDown() to stream.height.evenDown()
    return (CAST_REQUEST_HEIGHT * aspectRatio).toInt().evenDown() to CAST_REQUEST_HEIGHT
}

private fun Int.evenDown(): Int = (this - (this and 1)).coerceAtLeast(2)

/**
 * What the player shows once the video is on the TV.
 *
 * There is one video output and it is now the TV, so there is nothing to draw here - and this is also
 * what the user expects to see after casting something, rather than the same video twice.
 */
@Composable
internal fun CastingPanel(
    receiverName: String,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconCastConnected(tint = Color.White, modifier = Modifier.size(48.dp))
        Text(
            text = stringResource(R.string.cast_playing_on, receiverName),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Button(onClick = onStop, modifier = Modifier.padding(top = 16.dp)) {
            Text(stringResource(R.string.cast_stop))
        }
    }
}

/** Formats a tempo multiplier for display: whole values as "2", fractional as "1.5". */
internal fun formatTempo(speed: Float): String {
    val rounded = Math.round(speed * 100f) / 100f
    return if (rounded % 1f == 0f) rounded.toInt().toString()
    else rounded.toString().trimEnd('0').trimEnd('.')
}
