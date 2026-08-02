package com.vayunmathur.messages.ui.setup

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.messages.R
import com.vayunmathur.messages.Route
import com.vayunmathur.messages.signal.SignalClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalLoginScreen(backStack: NavBackStack<Route>) {
    val state by SignalClient.state.collectAsState()

    LaunchedEffect(state) {
        if (state is SignalClient.State.Connected) backStack.pop()
    }

    AppScaffold(
        title = stringResource(R.string.setup_signal_title),
        backStack = backStack,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val s = state) {
                is SignalClient.State.Idle,
                is SignalClient.State.NeedsSetup -> {
                    Text(
                        stringResource(R.string.setup_signal_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { SignalClient.startProvisioning() },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 14.dp),
                    ) {
                        Text(stringResource(R.string.setup_signal_link), fontWeight = FontWeight.SemiBold)
                    }
                }

                is SignalClient.State.Connecting -> {
                    Text(
                        stringResource(R.string.setup_signal_connecting),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    CircularProgressIndicator(
                        modifier = Modifier.height(40.dp),
                        strokeWidth = 3.dp,
                    )
                }

                is SignalClient.State.AwaitingQrScan -> {
                    Text(
                        stringResource(R.string.setup_signal_scan_prompt),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    val bitmap = remember(s.qrUrl) { generateQrBitmap(s.qrUrl, 512) }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.cd_qr_code),
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .aspectRatio(1f),
                        )
                    }
                }

                is SignalClient.State.Connected -> {
                    Text(stringResource(R.string.connected_to_signal))
                }

                is SignalClient.State.Disconnected -> {
                    Text(
                        stringResource(R.string.setup_signal_disconnected, s.reason),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        onClick = { SignalClient.start() },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 14.dp),
                    ) {
                        Text(stringResource(R.string.retry), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

private fun generateQrBitmap(content: String, size: Int): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        bitmap
    } catch (_: Exception) {
        null
    }
}
