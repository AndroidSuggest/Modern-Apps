package com.vayunmathur.messages.ui.setup
import com.vayunmathur.messages.R

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.messages.Route
import com.vayunmathur.messages.whatsapp.WhatsAppClient
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.res.stringResource

@Composable
fun WhatsAppLoginScreen(backStack: NavBackStack<Route>) {
    val state by WhatsAppClient.state.collectAsState()

    LaunchedEffect(Unit) {
        if (state is WhatsAppClient.State.NeedsSetup) {
            WhatsAppClient.startProvisioning()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.connect_whatsapp),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        when (val currentState = state) {
            is WhatsAppClient.State.NeedsSetup -> {
                Text(
                    text = stringResource(R.string.preparing_to_connect),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator()
            }

            is WhatsAppClient.State.AwaitingQrScan -> {
                Text(
                    text = stringResource(R.string.scan_this_qr_code_with_whatsapp_on_your),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.open_whatsapp_settings_linked_devices_li),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))

                QrCodeImage(
                    data = currentState.qrData,
                    modifier = Modifier.size(256.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.waiting_for_scan),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            is WhatsAppClient.State.Connecting -> {
                Text(
                    text = stringResource(R.string.connecting_to_whatsapp),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator()
            }

            is WhatsAppClient.State.Connected -> {
                Text(
                    text = stringResource(R.string.connected_successfully),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { backStack.pop() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.continue_action))
                }
            }

            is WhatsAppClient.State.Disconnected -> {
                Text(
                    text = stringResource(R.string.connection_failed),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = currentState.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { WhatsAppClient.startProvisioning() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.try_again))
                }
            }

            else -> {
                Text(
                    text = stringResource(R.string.initializing),
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun QrCodeImage(data: String, modifier: Modifier = Modifier) {
    val bitmap = try {
        val bitMatrix: BitMatrix = MultiFormatWriter().encode(
            data,
            BarcodeFormat.QR_CODE,
            512,
            512
        )
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bmp.setPixel(
                    x, y,
                    if (bitMatrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE
                )
            }
        }
        bmp.asImageBitmap()
    } catch (e: Exception) {
        null
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = stringResource(R.string.cd_whatsapp_qr_code),
            modifier = modifier
        )
    } else {
        Text(
            text = stringResource(R.string.failed_to_generate_qr_code),
            color = MaterialTheme.colorScheme.error
        )
    }
}
