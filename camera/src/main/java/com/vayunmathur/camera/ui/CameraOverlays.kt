package com.vayunmathur.camera.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.util.Patterns
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.vayunmathur.camera.R
import com.vayunmathur.library.intents.euicc.EsimLink
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.IconBedtime
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Motion
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.animatedFloat
import com.vayunmathur.library.util.AppMessages

@Composable
internal fun GridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeWidth = 1.dp.toPx()
        val color = Color.White.copy(alpha = 0.3f)
        val thirdW = size.width / 3f
        val thirdH = size.height / 3f

        for (i in 1..2) {
            drawLine(color, Offset(thirdW * i, 0f), Offset(thirdW * i, size.height), strokeWidth)
            drawLine(color, Offset(0f, thirdH * i), Offset(size.width, thirdH * i), strokeWidth)
        }
    }
}

/**
 * Horizon indicator: a fixed horizontal reference plus a line that rotates with the device roll.
 * Both turn green when the device is within ±1.5° of level.
 */
@Composable
internal fun LevelOverlay(roll: Float, modifier: Modifier = Modifier) {
    val isLevel = kotlin.math.abs(roll) <= 1.5f
    val color = if (isLevel) Color(0xFF4CAF50) else Color.White
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val half = size.width * 0.18f
        val stroke = 2.dp.toPx()
        val gap = 10.dp.toPx()

        // Fixed reference ticks either side of centre.
        val refColor = Color.White.copy(alpha = 0.5f)
        drawLine(refColor, Offset(cx - half - gap, cy), Offset(cx - half, cy), stroke)
        drawLine(refColor, Offset(cx + half, cy), Offset(cx + half + gap, cy), stroke)

        // Rolling line rotated by -roll (screen rotates opposite to device tilt).
        val rad = Math.toRadians(-roll.toDouble())
        val dx = (half * kotlin.math.cos(rad)).toFloat()
        val dy = (half * kotlin.math.sin(rad)).toFloat()
        drawLine(color, Offset(cx - dx, cy - dy), Offset(cx + dx, cy + dy), stroke)
        drawCircle(color, 2.dp.toPx(), Offset(cx, cy))
    }
}

@Composable
internal fun NightModeButton(
    active: Boolean,
    onClick: () -> Unit,
    iconRotation: Float,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Color(0x99000000), RoundedCornerShape(24.dp))
            .selectedPill(active, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBedtime(Modifier.size(20.dp).rotate(iconRotation), if (active) Color.White else Color(0xFFBBBBBB))
        Text(
            text = stringResource(R.string.night),
            color = if (active) Color.White else Color(0xFFBBBBBB),
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
internal fun QrResultOverlay(text: String, onDismiss: () -> Unit, context: Context, modifier: Modifier = Modifier) {
    // eSIM activation codes (SGP.22 `LPA:1$<smdp>$<matchingId>`) get their own action:
    // hand the scanned code to the platform LPA via EuiccManager instead of treating
    // it as a URL or plain text to copy.
    val trimmed = text.trim()
    val isEsim = isEsimActivationCode(trimmed)
    Column(
        modifier = modifier
            .padding(16.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainer,
                RoundedCornerShape(16.dp)
            )
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            if (isEsim) stringResource(R.string.esim_qr_result) else stringResource(R.string.qr_result),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        // Passkey (FIDO hybrid/caBLE) QR codes decode to a "FIDO:/..." URI. Treat it as an
        // openable URL alongside normal web links. Intent-filter scheme matching is case-SENSITIVE,
        // so the "FIDO" scheme must be lowercased (normalizeScheme) or nothing will handle it.
        // eSIM activation codes are neither: they go to the platform LPA, not a browser.
        val isFidoUri = text.startsWith("FIDO:", ignoreCase = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isEsim) {
                Button(onClick = {
                    // Standard eSIM handoff: build the universal provisioning link
                    // (https://esimsetup.android.com/esim_qrcode_provisioning?carddata=…)
                    // from the scanned code and fire ACTION_VIEW. The intent is pinned
                    // to our euicc app: a plain VIEW would otherwise resolve to the
                    // browser (seen on-device), which cannot provision anything, and
                    // we cannot claim verified App Links for a domain we don't own.
                    // The data format stays standard — any handler could serve it.
                    val link = EsimLink.provisioningUri(trimmed)
                    val view = Intent(Intent.ACTION_VIEW, link)
                        .setPackage(EsimLink.LPA_PACKAGE)
                    try {
                        context.startActivity(view)
                        onDismiss()
                    } catch (e: ActivityNotFoundException) {
                        Log.w("CameraOverlays", "No app to handle eSIM provisioning", e)
                        AppMessages.show(context.getString(R.string.no_app_to_add_esim))
                    } catch (e: SecurityException) {
                        Log.w("CameraOverlays", "No app to handle eSIM provisioning", e)
                        AppMessages.show(context.getString(R.string.no_app_to_add_esim))
                    }
                }) {
                    Text(stringResource(R.string.add_esim))
                }
            } else if (isFidoUri || Patterns.WEB_URL.matcher(text).matches()) {
                Button(onClick = {
                    val url = if (!isFidoUri && !text.startsWith("http")) "https://$text" else text
                    val uri = url.toUri().normalizeScheme()
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (e: ActivityNotFoundException) {
                        Log.w("CameraOverlays", "No app to open URL", e)
                        AppMessages.show(context.getString(R.string.no_app_to_open_url))
                    }
                }) {
                    Text(stringResource(R.string.open_url))
                }
            }
            FilledTonalButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("QR", text))
            }) {
                Text(stringResource(R.string.copy_text))
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    }
}

/**
 * Whether [text] is an SGP.22 eSIM activation code (`LPA:1$<smdp>$<matchingId>`,
 * prefix and trailing fields optional — see euicc's `parse_activation_code`).
 *
 * Same standard as the euicc scanner's `looksLikeActivationCode`: a bare
 * `1$<smdp>$...` code has no readable tag, so only the `$`-delimited shape
 * marks it.
 */
internal fun isEsimActivationCode(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.startsWith("LPA:", ignoreCase = true)) return true
    if (!trimmed.contains('$')) return false
    val parts = trimmed.split('$')
    // Version + SM-DP+ + matching ID at minimum.
    return parts.size >= 3 && parts[1].isNotBlank()
}

@Composable
internal fun RecordingIndicator(durationSec: Long, modifier: Modifier = Modifier) {
    val dotAlpha = animatedFloat(
        target = if ((durationSec % 2) == 0L) 1f else 0.3f,
        spec = Motion.over(500)
    )

    val minutes = durationSec / 60
    val seconds = durationSec % 60
    val timeText = "%d:%02d".format(minutes, seconds)

    Row(
        modifier = modifier
            .background(Color(0xCC000000), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .graphicsLayer { alpha = dotAlpha }
                .background(Color.Red, CircleShape)
        )
        Text(
            text = timeText,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
internal fun LongExposureOverlay(
    progress: Float,
    remainingText: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(80.dp),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.2f),
                strokeWidth = 4.dp
            )
            Text(
                text = remainingText,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
