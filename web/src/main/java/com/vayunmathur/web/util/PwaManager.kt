package com.vayunmathur.web.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Bitmap.Config
import android.net.Uri
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.vayunmathur.web.PwaActivity
import com.vayunmathur.web.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PwaPageInfo(
    val name: String,
    val iconUrl: String? = null,
    val themeColor: String? = null,
    val manifestUrl: String? = null,
    val hasManifest: Boolean = false,
)

object PwaShortcutManager {
    private const val TAG = "PwaShortcut"

    fun isPinSupported(context: Context): Boolean =
        ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    fun createShortcutIntent(context: Context, url: String, title: String?): Intent {
        return Intent(context, PwaActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(url)
            putExtra("pwa_url", url)
            putExtra("pwa_title", title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    fun buildShortcutId(url: String): String {
        // Stable id per origin + path hash
        return try {
            val origin = BrowserUtils.originFromUrl(url)
            "pwa_${origin.hashCode()}_${url.hashCode()}"
        } catch (_: Exception) {
            "pwa_${url.hashCode()}"
        }
    }

    suspend fun loadIconBitmap(context: Context, iconUrl: String?): Bitmap? {
        if (iconUrl.isNullOrBlank()) return null
        return withContext(Dispatchers.IO) {
            try {
                // Use Coil ImageLoader if available
                val loader = coil.ImageLoader(context)
                val req = coil.request.ImageRequest.Builder(context)
                    .data(iconUrl)
                    .allowHardware(false)
                    .size(192)
                    .build()
                val result = loader.execute(req)
                val drawable = result.drawable ?: return@withContext null
                val bmp = Bitmap.createBitmap(
                    drawable.intrinsicWidth.takeIf { it > 0 } ?: 192,
                    drawable.intrinsicHeight.takeIf { it > 0 } ?: 192,
                    Config.ARGB_8888
                )
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp
            } catch (e: Exception) {
                Log.w(TAG, "loadIconBitmap failed $iconUrl", e)
                null
            }
        }
    }

    fun generateTextIcon(context: Context, title: String, sizePx: Int = 192): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // Use a pleasant color derived from title hash
            val hash = title.hashCode()
            val r = 80 + (hash shr 16 and 0x7F)
            val g = 80 + (hash shr 8 and 0x7F)
            val b = 80 + (hash and 0x7F)
            color = android.graphics.Color.rgb(r, g, b)
        }
        // rounded rect background 28% corner approx
        val radius = sizePx * 0.28f
        canvas.drawRoundRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), radius, radius, bgPaint)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = sizePx * 0.5f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val letter = title.trim().firstOrNull()?.uppercase() ?: "W"
        val x = sizePx / 2f
        val y = sizePx / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(letter, x, y, textPaint)
        return bmp
    }

    suspend fun requestPinShortcut(
        context: Context,
        url: String,
        title: String,
        iconUrl: String?,
        themeColor: String? = null
    ): Boolean {
        if (!isPinSupported(context)) return false
        return withContext(Dispatchers.Main) {
            try {
                val id = buildShortcutId(url)
                val intent = createShortcutIntent(context, url, title)
                val shortLabel = title.take(12).ifBlank { BrowserUtils.hostFromUrl(url) }.take(10)
                val longLabel = title.ifBlank { url }.take(48)

                var iconBitmap: Bitmap? = null
                if (iconUrl != null) {
                    iconBitmap = loadIconBitmap(context, iconUrl)
                }
                if (iconBitmap == null) {
                    // Try favicon via /favicon.ico as fallback
                    try {
                        val origin = BrowserUtils.originFromUrl(url)
                        iconBitmap = loadIconBitmap(context, "$origin/favicon.ico")
                    } catch (_: Exception) {}
                }
                val finalBitmap = iconBitmap ?: generateTextIcon(context, title)

                val iconCompat = if (finalBitmap != null) {
                    IconCompat.createWithAdaptiveBitmap(finalBitmap)
                } else {
                    // fallback to launcher icon resource
                    IconCompat.createWithResource(context, R.mipmap.ic_launcher)
                }

                val shortcut = ShortcutInfoCompat.Builder(context, id)
                    .setShortLabel(shortLabel)
                    .setLongLabel(longLabel)
                    .setIntent(intent)
                    .setIcon(iconCompat)
                    .build()

                ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
            } catch (e: Exception) {
                Log.e(TAG, "requestPinShortcut failed", e)
                false
            }
        }
    }
}
