package com.vayunmathur.emergency.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.emergency.R
import com.vayunmathur.emergency.platform.SosActions
import com.vayunmathur.emergency.platform.SosCoordinator
import com.vayunmathur.emergency.platform.SosUiState
import com.vayunmathur.emergency.receiver.SosActionReceiver
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.Text
import kotlinx.coroutines.delay

/**
 * The SOS countdown screen.
 *
 * Mirrors GrapheneOS's `EmergencyActionActivity` + `EmergencyActionFragment`: opens only when
 * the gesture is enabled, ignores back press, counts down to placing the call, and hands the
 * countdown to [SosCountdownService][com.vayunmathur.emergency.service.SosCountdownService]
 * when dismissed without cancelling or finishing. Cancelling stops everything.
 *
 * Two substitutions: a large cancel button instead of the slide-to-confirm control (same
 * one-tap-out semantics, no custom gesture code to drift), and a text countdown instead of
 * the ring animation (motion helpers have no countdown-ring shape; the number is the signal).
 */
class SosActivity : ComponentActivity() {

    @Suppress("UNCHECKED_CAST")
    private val coordinator: SosCoordinator by lazy {
        val remaining = intent.getLongExtra(EXTRA_REMAINING_MS, -1)
        ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SosCoordinator(application, remaining) as T
            },
        )[SosCoordinator::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                val state by coordinator.state.collectAsStateWithLifecycle()
                SosScreen(state = state, actions = coordinator)
            }
        }
    }

    companion object {
        private const val EXTRA_REMAINING_MS = "STATE_MILLIS_LEFT"

        /** Opens the SOS screen counting down from [remainingMillis] (or the default). */
        fun intent(context: Context, remainingMillis: Long = -1): Intent =
            Intent(context, SosActivity::class.java)
                .putExtra(EXTRA_REMAINING_MS, remainingMillis)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** The countdown. Stateless apart from the ticking clock, which the screen owns. */
@Composable
fun SosScreen(state: SosUiState, actions: SosActions) {
    // Back press is deliberately ignored mid-countdown, like the original: leaving happens
    // through cancel (stops everything) or by backgrounding (hands to the service).
    BackHandler(enabled = true) { }

    if (!state.loaded) return

    if (!state.gestureEnabled) {
        SosMessage(text = stringResource(R.string.sos_not_enabled))
        return
    }

    var remaining by remember(state.totalMillis) { mutableLongStateOf(state.totalMillis) }
    var done by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // Screenshots render this screen with literal state; the countdown effect must not fire
    // there (no broadcast stack, no activity to finish).
    val inspection = LocalInspectionMode.current

    // Firing the call exactly once when the clock hits zero.
    if (!inspection) {
        LaunchedEffect(state.totalMillis) {
            val tick = context.resources.getInteger(R.integer.sos_count_down_interval).toLong()
            var left = state.totalMillis
            while (left > 0) {
                delay(tick.coerceAtMost(left))
                left -= tick.coerceAtMost(left)
                remaining = left.coerceAtLeast(0)
            }
            done = true
            context.sendBroadcast(SosActionReceiver.makeCallIntent(context))
            (context as? ComponentActivity)?.finish()
        }
    }

    // Dismissed (not cancelled, not finished): the countdown continues in the service.
    if (!inspection) {
        DisposableEffect(Unit) {
            onDispose {
                if (!done && remaining > 0) {
                    actions.continueInBackground(remaining)
                }
            }
        }
    }

    val seconds = ((remaining + 999) / 1000).toInt()
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.sos_title),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.sos_subtitle, state.number, seconds),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = {
            done = true
            actions.cancel()
            (context as? ComponentActivity)?.finish()
        }) {
            Text(stringResource(R.string.sos_cancel))
        }
    }
}

/** A centered message with no actions (gesture disabled, etc.). */
@Composable
private fun SosMessage(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = text, textAlign = TextAlign.Center)
    }
}
