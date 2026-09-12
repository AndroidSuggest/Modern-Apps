package com.vayunmathur.auto.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.auto.R
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberClock
import kotlin.time.Duration.Companion.seconds

/** What the projection session is doing right now: video, focus, counts, elapsed time. */
@Composable
fun SessionCard(session: SessionSnapshot, modifier: Modifier = Modifier) {
    val videoLine = session.video?.let {
        stringResource(R.string.session_video, it.width, it.height, it.frameRate, it.configIndex)
    } ?: stringResource(R.string.session_video_none)
    val focusLine = session.focusMode ?: stringResource(R.string.session_focus_none)
    Card(modifier.fillMaxWidth()) {
        Column {
            ListItem(
                headlineContent = { Text(stringResource(R.string.session_headline)) },
                supportingContent = { Text(videoLine) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.session_focus)) },
                supportingContent = { Text(focusLine) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.session_frames)) },
                supportingContent = {
                    Text(
                        stringResource(
                            R.string.session_frames_value,
                            session.framesSent,
                            session.acksSeen,
                        ),
                    )
                },
            )
            if (session.ackMismatches > 0) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.session_ack_mismatch)) },
                    supportingContent = {
                        Text(
                            stringResource(R.string.session_ack_mismatch_value, session.ackMismatches),
                        )
                    },
                )
            }
            SessionElapsedRow(sessionStartedAt = session.sessionStartedAt)
        }
    }
}

/** Live mm:ss counter since the session became active; a dash when there is no session. */
@Composable
private fun SessionElapsedRow(sessionStartedAt: Long?) {
    val now = rememberClock(1.seconds)
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Invoke the clock getter inside the row that displays it, so only this row
    // recomposes each second rather than the whole screen.
    tick = now()
    val elapsed = sessionStartedAt?.let { formatElapsed(tick - it) }
        ?: stringResource(R.string.session_elapsed_none)
    ListItem(
        headlineContent = { Text(stringResource(R.string.session_elapsed)) },
        supportingContent = { Text(elapsed) },
    )
}

private fun formatElapsed(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0) / 1000).toInt()
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
