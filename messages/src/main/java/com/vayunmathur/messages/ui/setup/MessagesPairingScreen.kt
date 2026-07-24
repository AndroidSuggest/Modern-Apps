package com.vayunmathur.messages.ui.setup

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.messages.R
import com.vayunmathur.messages.Route
import com.vayunmathur.messages.gmessages.GMessagesClient
import com.vayunmathur.messages.util.MessagesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Native QR-code pairing screen. Replaces the prior WebView-based
 * pairing UI.
 *
 * Flow:
 *   1. On first composition, call [GMessagesClient.startPair] which
 *      registers a phone-relay slot with the Google relay and returns
 *      the URL the user's phone should be pointed at.
 *   2. Encode that URL as a QR code via ZXing's [QRCodeWriter] (no
 *      external image; we generate the bitmap on a worker thread).
 *   3. Render the bitmap full-width.
 *   4. The user opens Google Messages on their phone (menu → Device
 *      pairing → Pair QR code scanner) and scans.
 *   5. The relay's long-poll connection pushes a Paired event,
 *      [GMessagesClient.state] flips to Connected, and this composable
 *      pops itself off the back stack.
 *
 * No WebView, no JavaScript shim, no anti-bot detection surface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesPairingScreen(
    backStack: NavBackStack<Route>,
    vm: MessagesViewModel,
) {
    val state by GMessagesClient.state.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }

    // Kick off pairing once.
    LaunchedEffect(Unit) {
        if (state !is GMessagesClient.State.Pairing &&
            state !is GMessagesClient.State.Connected
        ) {
            try {
                GMessagesClient.startPair()
            } catch (t: Throwable) {
                error = t.message ?: "Failed to start pairing"
            }
        }
    }

    // Auto-pop on success.
    LaunchedEffect(state) {
        if (state is GMessagesClient.State.Connected) {
            backStack.pop()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setup_messages_title)) },
                navigationIcon = { IconNavigation(backStack) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.setup_messages_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            when (val s = state) {
                is GMessagesClient.State.Pairing -> QrCard(s.qrUrl)
                GMessagesClient.State.Connected -> Text(
                    stringResource(R.string.paired),
                    fontWeight = FontWeight.SemiBold,
                )
                is GMessagesClient.State.Disconnected -> Text(
                    stringResource(R.string.setup_signal_disconnected, s.reason),
                    color = MaterialTheme.colorScheme.error,
                )
                GMessagesClient.State.Idle -> {
                    if (error != null) {
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                    } else {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.requesting_a_pairing_slot_from_google))
                    }
                }
            }
        }
    }
}

@Composable
private fun QrCard(url: String) {
    // Generating the QR bitmap is mildly expensive (~tens of ms for a
    // 512² matrix) — hop off the main thread so the screen frame isn't
    // dropped on first render.
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = withContext(Dispatchers.Default) { renderQr(url, sizePx = 768) }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .aspectRatio(1f),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
    ) {
        Box(Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
            val bm = bitmap
            if (bm == null) {
                CircularProgressIndicator()
            } else {
                Image(
                    bitmap = bm.asImageBitmap(),
                    contentDescription = stringResource(R.string.cd_pairing_qr_code),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private fun renderQr(content: String, sizePx: Int): Bitmap {
    val hints = mapOf(
        EncodeHintType.MARGIN to 1,
        // High EC so the QR survives a bit of glare / camera tilt on
        // the phone's scan.
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) {
        val row = y * sizePx
        for (x in 0 until sizePx) {
            pixels[row + x] = if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
}
