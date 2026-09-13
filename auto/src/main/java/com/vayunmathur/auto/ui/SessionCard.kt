package com.vayunmathur.auto.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.vayunmathur.auto.R
import com.vayunmathur.auto.platform.NowPlayingInfo
import com.vayunmathur.auto.protocol.VideoFocus
import com.vayunmathur.auto.protocol.gal.AudioFocusState
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
    // Raw 0x8008 mode string (e.g. VIDEO_FOCUS_PROJECTED_NO_INPUT_FOCUS): the
    // arbitrated rows below are authoritative; this names the exact wire mode
    // for debugging. Null until the first indication.
    val focusLine = session.focusMode ?: stringResource(R.string.session_focus_none)
    val videoFocusLine = when (session.videoFocus) {
        VideoFocus.PROJECTED -> stringResource(R.string.session_focus_projected)
        VideoFocus.NATIVE -> stringResource(R.string.session_focus_native)
        VideoFocus.NONE -> stringResource(R.string.session_focus_none)
    }
    Card(modifier.fillMaxWidth()) {
        Column {
            ListItem(
                headlineContent = { Text(stringResource(R.string.session_headline)) },
                supportingContent = { Text(videoLine) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.session_focus)) },
                supportingContent = { Text(videoFocusLine) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.session_focus_wire)) },
                supportingContent = { Text(focusLine) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.session_audio_focus)) },
                supportingContent = { Text(session.audioFocus.name) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.session_input_allowed)) },
                supportingContent = {
                    Text(
                        stringResource(
                            if (session.inputAllowed) R.string.session_input_allowed_yes
                            else R.string.session_input_allowed_no,
                        ),
                    )
                },
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
            ListItem(
                headlineContent = { Text(stringResource(R.string.session_rates)) },
                supportingContent = {
                    Text(
                        stringResource(
                            R.string.session_rates_value,
                            session.encodedFps,
                            session.ackFps,
                        ),
                    )
                },
            )
            AckAgeRow(lastAckAt = session.lastAckAt)
            ListItem(
                headlineContent = { Text(stringResource(R.string.session_ack_seq)) },
                supportingContent = {
                    Text(
                        session.lastAckSeq?.let {
                            stringResource(R.string.session_ack_seq_value, it)
                        } ?: stringResource(R.string.session_ack_seq_none),
                    )
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.session_latency)) },
                supportingContent = {
                    Text(
                        session.avgEncodeLatencyUs?.let {
                            stringResource(R.string.session_latency_value, it)
                        } ?: stringResource(R.string.session_latency_none),
                    )
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.session_drains)) },
                supportingContent = {
                    Text(stringResource(R.string.session_drains_value, session.encoderDrains))
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.session_surface)) },
                supportingContent = {
                    Text(
                        stringResource(
                            if (session.surfaceValid) R.string.session_surface_valid
                            else R.string.session_surface_invalid,
                        ),
                    )
                },
            )
            SessionElapsedRow(sessionStartedAt = session.sessionStartedAt)
            NowPlayingRow(nowPlaying = session.nowPlaying)
            MessagingRow(
                threads = session.threadsPosted,
                messages = session.messagesPosted,
                replies = session.repliesReceived,
            )
            AudioRow(
                sinkStatus = session.sinkStatus,
                bytesSent = session.audioBytesSent,
                musicCaptured = session.musicBytesCaptured,
                micTurns = session.micTurns,
                micAcks = session.micAcks,
                ttsSpoken = session.ttsSpoken,
            )
            InputRow(
                touches = session.touchesInjected,
                keys = session.keysInjected,
                scrolls = session.scrollsInjected,
                dropped = session.inputDropped,
            )
            CredentialRow(daysLeft = session.credentialDaysLeft)
        }
    }
}

/**
 * What ch8 did this session: touch frames, keys and scrolls injected, plus
 * reports dropped for lack of input focus. Counters reset per socket, like
 * the frame/ack counts above.
 */
@Composable
private fun InputRow(touches: Long, keys: Long, scrolls: Long, dropped: Long) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.session_input_touches)) },
        supportingContent = { Text(stringResource(R.string.session_input_touches_value, touches)) },
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.session_input_keys)) },
        supportingContent = { Text(stringResource(R.string.session_input_keys_value, keys)) },
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.session_input_scrolls)) },
        supportingContent = { Text(stringResource(R.string.session_input_scrolls_value, scrolls)) },
    )
    if (dropped > 0) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.session_input_dropped)) },
            supportingContent = { Text(stringResource(R.string.session_input_dropped_value, dropped)) },
        )
    }
}

/**
 * What the on-device media session is playing: track, artist, playback state.
 * Null until the session reports; kept after disconnect like the video line so
 * the last-known state stays visible between sessions.
 */
@Composable
private fun NowPlayingRow(nowPlaying: NowPlayingInfo?) {
    if (nowPlaying == null) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.session_now_playing)) },
            supportingContent = { Text(stringResource(R.string.session_now_playing_none)) },
        )
        return
    }
    val title = nowPlaying.title ?: stringResource(R.string.session_now_playing_unknown)
    val artist = nowPlaying.artist ?: stringResource(R.string.session_now_playing_unknown_artist)
    val state = stringResource(
        if (nowPlaying.playing) R.string.session_now_playing_playing
        else R.string.session_now_playing_paused,
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.session_now_playing)) },
        supportingContent = {
            Text(stringResource(R.string.session_now_playing_value, title, artist, state))
        },
    )
}

/**
 * What ch14 did this session: thread snapshots, message bodies, head-unit replies.
 * Counters reset per socket, like the frame/ack counts above.
 */
@Composable
private fun MessagingRow(threads: Long, messages: Long, replies: Long) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.session_messaging_threads)) },
        supportingContent = { Text(stringResource(R.string.session_messaging_threads_value, threads)) },
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.session_messaging_messages)) },
        supportingContent = { Text(stringResource(R.string.session_messaging_messages_value, messages)) },
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.session_messaging_replies)) },
        supportingContent = { Text(stringResource(R.string.session_messaging_replies_value, replies)) },
    )
}

/**
 * GAL leaf expiry: quiet count while far out, escalating warnings at 90/30/7 days,
 * expired notice at zero. Thresholds live here next to the rows they drive so a
 * rotation of the warning policy is one edit.
 */
@Composable
private fun CredentialRow(daysLeft: Long?) {
    if (daysLeft == null) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.session_credential)) },
            supportingContent = { Text(stringResource(R.string.session_credential_unknown)) },
        )
        return
    }
    val text = when {
        daysLeft < 0 -> stringResource(R.string.session_credential_expired)
        daysLeft <= CRITICAL_DAYS -> pluralStringResource(
            R.plurals.session_credential_critical,
            daysLeft.coerceToQuantity(),
            daysLeft,
        )
        daysLeft <= URGENT_DAYS -> pluralStringResource(
            R.plurals.session_credential_urgent,
            daysLeft.coerceToQuantity(),
            daysLeft,
        )
        daysLeft <= WARNING_DAYS -> pluralStringResource(
            R.plurals.session_credential_warning,
            daysLeft.coerceToQuantity(),
            daysLeft,
        )
        else -> pluralStringResource(
            R.plurals.session_credential_days,
            daysLeft.coerceToQuantity(),
            daysLeft,
        )
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.session_credential)) },
        supportingContent = { Text(text) },
    )
}

/**
 * Live age of the last head-unit ack, recomposing each second from the wall-clock
 * timestamp; a dash while no ack has arrived. Reuses the session clock pattern so
 * only this row recomposes, not the whole card.
 */
@Composable
private fun AckAgeRow(lastAckAt: Long?) {
    val now = rememberClock(1.seconds)
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    tick = now()
    if (lastAckAt == null) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.session_ack_age)) },
            supportingContent = { Text(stringResource(R.string.session_ack_age_none)) },
        )
        return
    }
    val age = formatAge(tick - lastAckAt)
    ListItem(
        headlineContent = { Text(stringResource(R.string.session_ack_age)) },
        supportingContent = { Text(stringResource(R.string.session_ack_age_value, age)) },
    )
}

@Composable
private fun formatAge(millis: Long): String {
    val clamped = millis.coerceAtLeast(0)
    return if (clamped < 10_000) stringResource(R.string.session_ack_age_secs, clamped / 1_000.0)
    else stringResource(R.string.session_ack_age_secs_whole, (clamped / 1_000).toInt())
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

/** Days of GAL leaf validity left at which the session card starts warning. */
private const val WARNING_DAYS = 90L

/** Days left at which the warning turns urgent. */
private const val URGENT_DAYS = 30L

/** Days left at which the warning names re-extraction explicitly. */
private const val CRITICAL_DAYS = 7L

/**
 * Quantity for the day-count plurals. Count is always non-negative here (expiry
 * is handled above) and far below [Int.MAX_VALUE]; the clamp is belt-and-braces
 * against a raw `toInt` silently wrapping on absurd input.
 */
private fun Long.coerceToQuantity(): Int = coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
