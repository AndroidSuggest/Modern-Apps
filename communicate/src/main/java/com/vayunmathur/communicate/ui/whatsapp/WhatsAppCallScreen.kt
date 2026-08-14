package com.vayunmathur.communicate.ui.whatsapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vayunmathur.communicate.data.whatsapp.call.WhatsAppCallManager
import com.vayunmathur.communicate.data.whatsapp.call.WhatsAppCallPhase
import com.vayunmathur.library.ui.FilledIconButton
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconMic
import com.vayunmathur.library.ui.IconMicOff
import com.vayunmathur.library.ui.IconVolumeUp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import org.webrtc.SurfaceViewRenderer

/**
 * Full-screen in-app UI for a ringing / incoming / active WhatsApp call (Phase D 3e). Reads the
 * [WhatsAppCallManager] state flow and drives answer / decline / mute / speaker / hangup. Rendered
 * as an overlay by the app while a call is in progress. Mirrors `ui/call/CallScreen`.
 *
 * Video render surfaces (local/remote `SurfaceViewRenderer`) are wired via
 * [WhatsAppCallManager.attachVideoRenderers]; remote-track rendering is best-effort in this dev
 * scaffolding (client-to-client only).
 */
@Composable
fun WhatsAppCallScreen(onClose: () -> Unit) {
    val state by WhatsAppCallManager.state.collectAsState()

    LaunchedEffect(state.phase) {
        if (state.phase == WhatsAppCallPhase.Ended || state.phase == WhatsAppCallPhase.Idle) {
            WhatsAppCallManager.clearEnded()
            onClose()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.size(48.dp))
                Text(
                    text = state.peerName.ifEmpty { state.peerJid.substringBefore("@") },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = phaseLabel(state.phase, state.isVideo),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (state.isVideo && state.phase != WhatsAppCallPhase.Incoming) {
                    Spacer(Modifier.size(16.dp))
                    VideoSurfaces()
                }
            }

            when (state.phase) {
                WhatsAppCallPhase.Incoming -> IncomingControls()
                else -> InCallControls(muted = state.muted, speaker = state.speaker, video = state.isVideo)
            }
        }
    }
}

@Composable
private fun VideoSurfaces() {
    val remote = remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    val local = remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    DisposableEffect(Unit) {
        onDispose { WhatsAppCallManager.attachVideoRenderers(null, null) }
    }
    LaunchedEffect(remote.value, local.value) {
        if (remote.value != null || local.value != null) {
            WhatsAppCallManager.attachVideoRenderers(local.value, remote.value)
        }
    }

    Box(modifier = Modifier.fillMaxWidth().height(320.dp)) {
        AndroidView(
            factory = { ctx -> SurfaceViewRenderer(ctx).also { remote.value = it } },
            modifier = Modifier.fillMaxSize(),
        )
        AndroidView(
            factory = { ctx -> SurfaceViewRenderer(ctx).also { local.value = it } },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .width(96.dp)
                .height(128.dp),
        )
    }
}

@Composable
private fun IncomingControls() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        FilledIconButton(onClick = { WhatsAppCallManager.reject() }, modifier = Modifier.size(64.dp)) { IconClose() }
        FilledIconButton(onClick = { WhatsAppCallManager.answer() }, modifier = Modifier.size(64.dp)) { IconCall() }
    }
}

@Composable
private fun InCallControls(muted: Boolean, speaker: Boolean, video: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            FilledIconButton(onClick = { WhatsAppCallManager.setMuted(!muted) }, modifier = Modifier.size(64.dp)) {
                if (muted) IconMicOff() else IconMic()
            }
            FilledIconButton(onClick = { WhatsAppCallManager.setSpeaker(!speaker) }, modifier = Modifier.size(64.dp)) {
                IconVolumeUp()
            }
            if (video) {
                FilledIconButton(onClick = { WhatsAppCallManager.switchCamera() }, modifier = Modifier.size(64.dp)) {
                    IconCall()
                }
            }
        }
        Box(modifier = Modifier.padding(bottom = 32.dp)) {
            FilledIconButton(onClick = { WhatsAppCallManager.hangup() }, modifier = Modifier.size(64.dp)) { IconClose() }
        }
    }
}

private fun phaseLabel(phase: WhatsAppCallPhase, video: Boolean): String {
    val kind = if (video) "Video call" else "Voice call"
    return when (phase) {
        WhatsAppCallPhase.Outgoing -> "Calling…"
        WhatsAppCallPhase.Incoming -> "Incoming $kind"
        WhatsAppCallPhase.Connecting -> "Connecting…"
        WhatsAppCallPhase.Active -> kind
        WhatsAppCallPhase.Ended -> "Call ended"
        WhatsAppCallPhase.Idle -> ""
    }
}
