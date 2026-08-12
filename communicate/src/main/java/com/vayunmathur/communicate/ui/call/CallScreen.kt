package com.vayunmathur.communicate.ui.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.googlevoice.call.CallPhase
import com.vayunmathur.communicate.data.googlevoice.call.GoogleVoiceCallManager
import com.vayunmathur.library.ui.FilledIconButton
import com.vayunmathur.library.ui.IconBackspace
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconMic
import com.vayunmathur.library.ui.IconMicOff
import com.vayunmathur.library.ui.IconVolumeUp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

/**
 * Full-screen in-app UI for an active/ringing/incoming Google Voice VoIP call. Reads the
 * [GoogleVoiceCallManager] state flow and drives its mute / speaker / DTMF / answer / hangup
 * controls. Rendered as an overlay by the app when a call is in progress.
 */
@Composable
fun CallScreen(onClose: () -> Unit) {
    val state by GoogleVoiceCallManager.state.collectAsState()
    var showKeypad by remember { mutableStateOf(false) }

    LaunchedEffect(state.phase) {
        if (state.phase == CallPhase.Ended || state.phase == CallPhase.Idle) {
            GoogleVoiceCallManager.clearEnded()
            onClose()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.size(48.dp))
            Text(
                state.remoteNumber.ifBlank { stringResource(R.string.account_google_voice) },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                phaseLabel(state.phase),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.weight(1f))

            if (showKeypad) {
                DtmfKeypad(
                    onDigit = { GoogleVoiceCallManager.sendDtmf(it) },
                    onClose = { showKeypad = false },
                )
                Spacer(Modifier.size(16.dp))
            }

            if (state.phase == CallPhase.Incoming) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    CallControl(label = stringResource(R.string.call_decline), destructive = true, onClick = {
                        GoogleVoiceCallManager.reject()
                    }) { IconClose() }
                    CallControl(label = stringResource(R.string.call_answer), onClick = {
                        GoogleVoiceCallManager.answer()
                    }) { IconCall() }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    CallControl(
                        label = if (state.muted) stringResource(R.string.call_unmute) else stringResource(R.string.call_mute),
                        onClick = { GoogleVoiceCallManager.setMuted(!state.muted) },
                    ) { if (state.muted) IconMicOff() else IconMic() }
                    CallControl(label = stringResource(R.string.call_speaker), onClick = {
                        GoogleVoiceCallManager.setSpeaker(!state.speaker)
                    }) { IconVolumeUp() }
                    CallControl(label = stringResource(R.string.call_keypad), onClick = {
                        showKeypad = !showKeypad
                    }) { IconBackspace() }
                }
                Spacer(Modifier.size(24.dp))
                CallControl(label = stringResource(R.string.call_end), destructive = true, onClick = {
                    GoogleVoiceCallManager.hangup()
                }) { IconClose() }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun CallControl(
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(onClick = onClick, modifier = Modifier.size(64.dp)) { icon() }
        Spacer(Modifier.size(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DtmfKeypad(onDigit: (String) -> Unit, onClose: () -> Unit) {
    val keys = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("*", "0", "#"),
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        keys.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                row.forEach { digit ->
                    Surface(
                        onClick = { onDigit(digit) },
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(digit, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
        TextButton(onClick = onClose) { Text(stringResource(R.string.call_keypad)) }
    }
}

@Composable
private fun phaseLabel(phase: CallPhase): String = when (phase) {
    CallPhase.Idle -> stringResource(R.string.call_state_idle)
    CallPhase.Dialing -> stringResource(R.string.call_state_dialing)
    CallPhase.Ringing -> stringResource(R.string.call_state_ringing)
    CallPhase.Active -> stringResource(R.string.call_state_active)
    CallPhase.Incoming -> stringResource(R.string.call_state_incoming)
    CallPhase.Ended -> stringResource(R.string.call_state_ended)
}
